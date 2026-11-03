package com.artacademy.upload;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.media.MediaUrlSigner;
import com.artacademy.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** Test upload ảnh/video/pdf. Upload cần ADMIN; ảnh phục vụ công khai ở /uploads/**, video và pdf chỉ qua link có chữ ký ở /media/**. */
@WithMockUser(username = "admin@test.local", roles = "ADMIN")
class UploadApiTest extends IntegrationTestBase {

    @Autowired MediaUrlSigner signer;

    private MockMultipartFile file(String name, String contentType, byte[] data) {
        return new MockMultipartFile("file", name, contentType, data);
    }

    @Test
    void upload_acceptsAllowedTypes_andReturnsRelativeUrl() throws Exception {
        String[][] cases = {
                {"anh.jpg", "image/jpeg", ".jpg"}, {"anh.PNG", "image/png", ".png"},
                {"anh.webp", "image/webp", ".webp"}, {"video.mp4", "video/mp4", ".mp4"},
                {"tai-lieu.pdf", "application/pdf", ".pdf"}};
        for (String[] c : cases) {
            mvc.perform(multipart("/api/admin/uploads").file(file(c[0], c[1], new byte[]{1, 2, 3})))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.url").value(startsWith("/uploads/")))
                    .andExpect(jsonPath("$.data.url").value(endsWith(c[2])));
        }
    }

    @Test
    void upload_rejectsUnsupportedTypes() throws Exception {
        mvc.perform(multipart("/api/admin/uploads").file(file("virus.exe", "application/octet-stream", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ nhận file jpg, png, webp, mp4 hoặc pdf"));
        mvc.perform(multipart("/api/admin/uploads").file(file("khong-duoi", "image/png", new byte[]{1})))
                .andExpect(status().isBadRequest());
        // đuôi .jpg nhưng thực chất là loại nội dung khác
        mvc.perform(multipart("/api/admin/uploads").file(file("gia.jpg", "application/pdf", new byte[]{1})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_rejectsEmptyOrMissingFile() throws Exception {
        mvc.perform(multipart("/api/admin/uploads").file(file("anh.png", "image/png", new byte[0])))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/uploads")).andExpect(status().isBadRequest());
    }

    @Test
    void upload_enforcesSizeLimitPerType() throws Exception {
        byte[] sixMb = new byte[6 * 1024 * 1024];
        // ảnh tối đa 5MB
        mvc.perform(multipart("/api/admin/uploads").file(file("to.png", "image/png", sixMb)))
                .andExpect(status().isPayloadTooLarge());
        // cùng dung lượng đó thì video vẫn được (giới hạn mặc định 2GB)
        mvc.perform(multipart("/api/admin/uploads").file(file("ok.mp4", "video/mp4", sixMb)))
                .andExpect(status().isOk());
        // pdf tối đa 20MB
        mvc.perform(multipart("/api/admin/uploads")
                        .file(file("to.pdf", "application/pdf", new byte[21 * 1024 * 1024])))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void uploadedVideo_isServedOnlyThroughASignedLink_andSupportsRangeRequests() throws Exception {
        byte[] data = "0123456789".getBytes();
        MvcResult up = mvc.perform(multipart("/api/admin/uploads").file(file("v.mp4", "video/mp4", data)))
                .andExpect(status().isOk()).andReturn();
        String stored = json.readTree(up.getResponse().getContentAsString()).get("data").get("url").asText();

        // Đường dẫn thô /uploads/xxx.mp4 không còn tải được (kể cả khi đang là admin)
        mvc.perform(get(stored)).andExpect(status().isForbidden());

        // Link có chữ ký thì tải được, và video_player cần Range để tua
        String url = signer.signIfProtected(stored);
        MvcResult full = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        assertArrayEquals(data, full.getResponse().getContentAsByteArray());
        mvc.perform(get(url).header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"));
    }

    @Test
    void uploadedImage_isServedPublicly() throws Exception {
        byte[] data = new byte[]{9, 8, 7, 6};
        MvcResult up = mvc.perform(multipart("/api/admin/uploads").file(file("bia.png", "image/png", data)))
                .andExpect(status().isOk()).andReturn();
        String stored = json.readTree(up.getResponse().getContentAsString()).get("data").get("url").asText();

        MvcResult image = mvc.perform(get(stored).with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isOk()).andReturn();
        assertArrayEquals(data, image.getResponse().getContentAsByteArray());
    }
}