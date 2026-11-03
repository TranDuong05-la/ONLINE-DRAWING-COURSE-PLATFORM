package com.artacademy.upload;

import org.springframework.web.multipart.MultipartFile;

/**
 * Nơi lưu file upload. Hiện lưu trên ổ đĩa server (LocalFileStorageService).
 * Nhóm muốn dùng Cloudinary thì chỉ cần viết thêm một class implements interface này, nơi gọi không phải sửa.
 */
public interface FileStorageService {

    /** Lưu file và trả về URL để lưu vào database, ví dụ "/uploads/3f2a....mp4". */
    String store(MultipartFile file);
}
