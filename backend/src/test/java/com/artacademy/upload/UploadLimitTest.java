package com.artacademy.upload;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

/**
 * Giới hạn dung lượng video cấu hình được (app.upload.max-video-mb). Ở đây hạ xuống 8MB để thử bằng file nhỏ;
 * giá trị mặc định là 2GB (kiểm tra ở {@link #defaultVideoLimitIsTwoGigabytes}).
 */
@WithMockUser(username = "admin@test.local", roles = "ADMIN")
@TestPropertySource(properties = "app.upload.max-video-mb=8")
class UploadLimitTest extends IntegrationTestBase {

    @Autowired LocalFileStorageService storage;

    private MockMultipartFile video(int megabytes) {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[megabytes * 1024 * 1024]);
    }

    @Test
    void videoOverTheConfiguredLimit_isRejectedWith413AndTheLimitInTheMessage() throws Exception {
        mvc.perform(multipart("/api/admin/uploads").file(video(9)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(containsString("8MB")));
    }

    @Test
    void videoUnderTheConfiguredLimit_isAccepted() throws Exception {
        mvc.perform(multipart("/api/admin/uploads").file(video(7)))
                .andExpect(status().isOk());
    }

    @Test
    void imageAndPdfLimitsAreIndependentOfTheVideoLimit() {
        assertEquals(8L * 1024 * 1024, storage.maxBytesFor("mp4"));
        assertEquals(5L * 1024 * 1024, storage.maxBytesFor("png"));
        assertEquals(20L * 1024 * 1024, storage.maxBytesFor("pdf"));
        assertEquals(0, storage.maxBytesFor("exe"));
    }

    @Test
    void defaultVideoLimitIsTwoGigabytes() {
        // Cách đọc cấu hình mặc định mà không cần tạo lại Spring context
        LocalFileStorageService defaults = new LocalFileStorageService("target/test-uploads", 2048);
        assertEquals(2L * 1024 * 1024 * 1024, defaults.maxBytesFor("mp4"));
        assertEquals("2GB", LocalFileStorageService.describe(defaults.maxBytesFor("mp4")));
        assertEquals("5MB", LocalFileStorageService.describe(5L * 1024 * 1024));
    }
}
