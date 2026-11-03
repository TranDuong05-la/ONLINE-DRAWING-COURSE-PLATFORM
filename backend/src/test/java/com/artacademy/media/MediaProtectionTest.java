package com.artacademy.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseStatus;
import com.artacademy.course.integration.CourseContentService;
import com.artacademy.course.integration.LessonContent;
import com.artacademy.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Video và PDF của khóa học KHÔNG còn công khai: chỉ tải được qua link có chữ ký, có hạn, do API cấp cho người có quyền
 * xem (học thử, đã đăng ký khóa qua C, hoặc admin). Ảnh bìa vẫn công khai.
 */
class MediaProtectionTest extends IntegrationTestBase {

    private static final Pattern LINK = Pattern.compile("^/media/([^?]+)\\?exp=(\\d+)&sig=([A-Za-z0-9_-]+)$");

    @Autowired MediaUrlSigner signer;
    @Autowired CourseContentService contentService;
    @Value("${app.upload.dir}") String uploadDir;
    @Value("${app.media.signing-secret:${app.jwt.secret}}") String secret;

    private void writeFile(String name, byte[] data) throws Exception {
        Path dir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), data);
    }

    private static Matcher parse(String link) {
        Matcher m = LINK.matcher(link);
        assertTrue(m.matches(), "không đúng dạng link có chữ ký: " + link);
        return m;
    }

    // ------------------------------------------------------------------ /uploads: chỉ ảnh công khai

    @Test
    void uploads_videoAndPdf_areBlockedForEveryone_evenWhenTheFileExists() throws Exception {
        writeFile("blocked.mp4", new byte[]{1, 2, 3});
        writeFile("blocked.pdf", new byte[]{1, 2, 3});

        for (String path : new String[]{"/uploads/blocked.mp4", "/uploads/blocked.pdf", "/uploads/BLOCKED.MP4"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());                          // chưa đăng nhập
            mvc.perform(get(path).with(user("hocvien@test.local").roles("USER")))
                    .andExpect(status().isForbidden());                                          // đã đăng nhập thường
            mvc.perform(get(path).with(admin())).andExpect(status().isForbidden());             // kể cả admin
        }
    }

    @Test
    void uploads_otherFileTypes_areBlockedToo() throws Exception {
        writeFile("note.txt", new byte[]{1});
        mvc.perform(get("/uploads/note.txt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/uploads/")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploads_images_stayPublic() throws Exception {
        writeFile("cover.png", new byte[]{9, 8, 7});
        writeFile("cover.jpg", new byte[]{9, 8, 7});
        writeFile("cover.webp", new byte[]{9, 8, 7});

        for (String name : new String[]{"cover.png", "cover.jpg", "cover.webp"}) {
            mvc.perform(get("/uploads/" + name)).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ /media: link có chữ ký

    @Test
    void signedLink_worksWithoutLogin_supportsRange_andIsNotCacheable() throws Exception {
        writeFile("movie.mp4", "0123456789".getBytes());
        String link = signer.signFile("movie.mp4");

        MvcResult full = mvc.perform(get(link))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertEquals("0123456789", full.getResponse().getContentAsString());
        mvc.perform(get(link).header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"));
    }

    @Test
    void signedLink_servesPdfWithPdfContentType() throws Exception {
        writeFile("doc.pdf", new byte[]{1, 2, 3});

        mvc.perform(get(signer.signFile("doc.pdf")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void signedLink_rejectsTamperedSignatureOrExpiry() throws Exception {
        writeFile("a.mp4", new byte[]{1});
        Matcher m = parse(signer.signFile("a.mp4"));
        String exp = m.group(2);
        String sig = m.group(3);

        // sai chữ ký (đổi ký tự đầu)
        String badSig = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
        mvc.perform(get("/media/a.mp4?exp=" + exp + "&sig=" + badSig)).andExpect(status().isForbidden());
        // kéo dài hạn mà không có chữ ký mới
        mvc.perform(get("/media/a.mp4?exp=" + (Long.parseLong(exp) + 3600) + "&sig=" + sig))
                .andExpect(status().isForbidden());
        // thiếu tham số
        mvc.perform(get("/media/a.mp4")).andExpect(status().isForbidden());
        mvc.perform(get("/media/a.mp4?exp=" + exp)).andExpect(status().isForbidden());
        mvc.perform(get("/media/a.mp4?sig=" + sig)).andExpect(status().isForbidden());
        // chữ ký hợp lệ nhưng đang đúng
        mvc.perform(get("/media/a.mp4?exp=" + exp + "&sig=" + sig)).andExpect(status().isOk());
    }

    @Test
    void signedLink_isBoundToItsOwnFile() throws Exception {
        writeFile("a.mp4", new byte[]{1});
        writeFile("b.mp4", new byte[]{2});
        Matcher forA = parse(signer.signFile("a.mp4"));

        mvc.perform(get("/media/b.mp4?exp=" + forA.group(2) + "&sig=" + forA.group(3)))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedLink_expiresAfterTheTtl() throws Exception {
        writeFile("old.mp4", new byte[]{1});
        // Link được ký cách đây 1 giờ với hạn 60 giây: nay đã hết hạn
        MediaUrlSigner past = new MediaUrlSigner(secret, 60,
                Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));

        mvc.perform(get(past.signFile("old.mp4"))).andExpect(status().isForbidden());
    }

    @Test
    void signedLink_usesTheConfiguredTtl_defaultTwoHours() {
        long before = Instant.now().getEpochSecond();
        long exp = Long.parseLong(parse(signer.signFile("t.mp4")).group(2));

        assertEquals(7200, signer.ttlSeconds());
        assertTrue(exp >= before + 7200 && exp <= before + 7200 + 5, "exp = " + exp);
    }

    @Test
    void signedLink_forAMissingFile_is404_andForAnUnsupportedFileIs403() throws Exception {
        mvc.perform(get(signer.signFile("khong-co.mp4"))).andExpect(status().isNotFound());
        // .txt không bao giờ được phục vụ, dù chữ ký có đúng kiểu gì
        Matcher m = parse(signer.signFile("khong-co.mp4"));
        mvc.perform(get("/media/note.txt?exp=" + m.group(2) + "&sig=" + m.group(3))).andExpect(status().isForbidden());
    }

    @Test
    void signer_rejectsUnsafeNames() {
        long exp = Instant.now().getEpochSecond() + 600;
        for (String bad : new String[]{"../a.mp4", "a/../b.mp4", "dir/a.mp4", "a.txt", ".mp4", "", "a.mp4.exe"}) {
            assertFalse(signer.isSafeFileName(bad), bad);
            assertFalse(signer.verify(bad, exp, "x"), bad);
        }
        assertTrue(signer.isSafeFileName("3f2a9c10-aaaa-bbbb-cccc-1234567890ab.mp4"));
    }

    // ------------------------------------------------------------------ phân biệt file cần ký

    @Test
    void signIfProtected_signsOnlyServerVideoAndPdf() {
        assertTrue(signer.signIfProtected("/uploads/a.mp4").startsWith("/media/a.mp4?exp="));
        assertTrue(signer.signIfProtected("/uploads/A.PDF").startsWith("/media/A.PDF?exp="));
        // Ảnh, link ngoài, rỗng: giữ nguyên
        assertEquals("/uploads/a.png", signer.signIfProtected("/uploads/a.png"));
        assertEquals("https://example.com/v.mp4", signer.signIfProtected("https://example.com/v.mp4"));
        assertEquals("", signer.signIfProtected(""));
        assertNull(signer.signIfProtected(null));
        // Đường dẫn lạ không được "lọt" vào chữ ký
        assertEquals("/uploads/../a.mp4", signer.signIfProtected("/uploads/../a.mp4"));
        assertEquals("/uploads/dir/a.mp4", signer.signIfProtected("/uploads/dir/a.mp4"));
        assertFalse(signer.isProtectedUpload(null));
        assertTrue(signer.isProtectedUpload("/uploads/a.mp4"));
        assertFalse(signer.isProtectedUpload("/uploads/a.png"));
    }

    // ------------------------------------------------------------------ API công khai: không lộ đường dẫn thô

    @Test
    void publicCourseDetail_signsThePreviewVideo_andNeverExposesRawUploadPaths() throws Exception {
        writeFile("preview.mp4", "PREVIEW".getBytes());
        writeFile("secret.mp4", "SECRET".getBytes());
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule m = newModule(c, "M", 1, false);
        newLesson(m, "Học thử trên server", 1, "/uploads/preview.mp4", true, false);
        newLesson(m, "Bài trả phí", 2, "/uploads/secret.mp4", false, false);
        newLesson(m, "Học thử link ngoài", 3, VIDEO, true, false);

        MvcResult res = getJson("/api/courses/" + c.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].lessons[1].previewVideoUrl").doesNotExist())
                .andExpect(jsonPath("$.data.modules[0].lessons[2].previewVideoUrl").value(VIDEO))
                .andReturn();
        String body = res.getResponse().getContentAsString();

        String previewUrl = json.readTree(body).at("/data/modules/0/lessons/0/previewVideoUrl").asText();
        assertTrue(previewUrl.startsWith("/media/preview.mp4?exp="), previewUrl);
        assertFalse(body.contains("/uploads/"), "API công khai không được lộ đường dẫn thô /uploads/");
        assertFalse(body.contains("secret.mp4"), "không lộ tên file của bài trả phí");
        // Người xem học thử mở được link đó
        assertEquals("PREVIEW", mvc.perform(get(previewUrl)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ C: nội dung bài học cho người đã đăng ký

    @Test
    void contentService_returnsSignedLinksForServerFiles_andKeepsExternalLinks() throws Exception {
        writeFile("lesson.mp4", "LESSON".getBytes());
        writeFile("slide.pdf", "SLIDE".getBytes());
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule m = newModule(c, "M", 1, false);
        CourseLesson local = newLesson(m, "Bài trên server", 1, "/uploads/lesson.mp4", false, false);
        local.setMaterialUrl("/uploads/slide.pdf");
        lessonRepository.save(local);
        CourseLesson external = newLesson(m, "Bài link ngoài", 2, VIDEO, false, false);

        LessonContent content = contentService.getLessonContent(local.getId());

        assertTrue(content.videoUrl().startsWith("/media/lesson.mp4?exp="), content.videoUrl());
        assertTrue(content.documentUrl().startsWith("/media/slide.pdf?exp="), content.documentUrl());
        assertEquals("LESSON", mvc.perform(get(content.videoUrl())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertEquals("SLIDE", mvc.perform(get(content.documentUrl())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertEquals(VIDEO, contentService.getLessonContent(external.getId()).videoUrl());
        assertNull(contentService.getLessonContent(external.getId()).documentUrl());
    }

    // ------------------------------------------------------------------ admin xem thử video vừa upload

    @Test
    void adminSign_isOnlyForAdmins_andSignsServerFilesOnly() throws Exception {
        writeFile("adm.mp4", "ADMIN".getBytes());

        mvc.perform(get("/api/admin/media/sign").param("path", "/uploads/adm.mp4"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/media/sign").param("path", "/uploads/adm.mp4")
                        .with(user("hocvien@test.local").roles("USER")))
                .andExpect(status().isForbidden());

        MvcResult res = mvc.perform(get("/api/admin/media/sign").param("path", "/uploads/adm.mp4").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(7200))
                .andReturn();
        String url = json.readTree(res.getResponse().getContentAsString()).at("/data/url").asText();
        assertTrue(url.startsWith("/media/adm.mp4?exp="), url);
        assertEquals("ADMIN", mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());

        mvc.perform(get("/api/admin/media/sign").param("path", "https://example.com/v.mp4").with(admin()))
                .andExpect(jsonPath("$.data.url").value("https://example.com/v.mp4"));
        mvc.perform(get("/api/admin/media/sign").param("path", "/uploads/cover.png").with(admin()))
                .andExpect(jsonPath("$.data.url").value("/uploads/cover.png"));
        mvc.perform(get("/api/admin/media/sign").param("path", " ").with(admin())).andExpect(status().isBadRequest());
    }
}
