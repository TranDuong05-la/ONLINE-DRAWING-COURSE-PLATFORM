package com.artacademy.upload;

import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload ảnh/video/PDF cho admin. Chỉ ADMIN (SecurityConfig). File phục vụ công khai ở /uploads/**.
 */
@RestController
@RequestMapping("/api/admin/uploads")
@Tag(name = "Admin - Uploads", description = "Upload ảnh bìa, video, PDF (chỉ ADMIN)")
public class UploadController {

    public record UploadResponse(String url) {
    }

    private final FileStorageService storage;

    public UploadController(FileStorageService storage) {
        this.storage = storage;
    }

    /** multipart/form-data, field tên "file". data = { "url": "/uploads/xxx.mp4" }. */
    @PostMapping
    @Operation(summary = "Upload file", description = "jpg/png/webp tối đa 5MB, pdf 20MB, mp4 200MB.")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Đã tải lên", new UploadResponse(storage.store(file)));
    }
}
