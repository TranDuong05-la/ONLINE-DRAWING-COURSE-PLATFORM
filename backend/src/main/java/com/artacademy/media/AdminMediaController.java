package com.artacademy.media;

import com.artacademy.common.BadRequestException;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cho admin xem thử video/PDF vừa upload (link thô /uploads/xxx.mp4 không còn tải được). Chỉ ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/media")
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class AdminMediaController {

    public record SignedMedia(String url, long expiresInSeconds) {
    }

    private final MediaUrlSigner signer;

    public AdminMediaController(MediaUrlSigner signer) {
        this.signer = signer;
    }

    @GetMapping("/sign")
    @Operation(summary = "Lấy link có hạn để xem thử video/PDF đã upload",
            description = "path là giá trị đã lưu, ví dụ /uploads/abc.mp4. Link ngoài (http, https) và ảnh được trả nguyên.")
    public ApiResponse<SignedMedia> sign(@RequestParam String path) {
        if (path == null || path.isBlank()) {
            throw new BadRequestException("Thiếu đường dẫn file");
        }
        return ApiResponse.success("OK", new SignedMedia(signer.signIfProtected(path.trim()), signer.ttlSeconds()));
    }
}