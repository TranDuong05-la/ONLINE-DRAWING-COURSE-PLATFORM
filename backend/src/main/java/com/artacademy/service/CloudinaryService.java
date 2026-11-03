package com.artacademy.service;

import com.artacademy.exception.AppException;
import com.cloudinary.Cloudinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
public class CloudinaryService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryService.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    private final Cloudinary cloudinary;

    public CloudinaryService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Upload an avatar image file to Cloudinary under the avatars folder.
     */
    public String uploadAvatar(MultipartFile file) {
        return uploadImage(file, "artacademy/avatars");
    }

    /**
     * Upload an image file to Cloudinary with default images folder.
     */
    public String uploadImage(MultipartFile file) {
        return uploadImage(file, "artacademy/images");
    }

    /**
     * Core method to validate and upload an image to Cloudinary.
     *
     * @param file   The MultipartFile uploaded by client
     * @param folder The destination folder in Cloudinary
     * @return The secure URL of the uploaded image
     */
    public String uploadImage(MultipartFile file, String folder) {
        validateImageFile(file);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    Map.of(
                            "folder", folder != null && !folder.isBlank() ? folder : "artacademy/uploads",
                            "resource_type", "image"
                    )
            );

            Object secureUrl = uploadResult.get("secure_url");
            if (secureUrl == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Không nhận được URL từ dịch vụ lưu trữ ảnh");
            }
            return secureUrl.toString();
        } catch (AppException e) {
            throw e;
        } catch (Exception ex) {
            log.error("Failed to upload image to Cloudinary: {}", ex.getMessage(), ex);
            throw new AppException(HttpStatus.BAD_REQUEST, "Tải ảnh lên thất bại: " + ex.getMessage());
        }
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tệp hình ảnh không được để trống");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Kích thước ảnh không được vượt quá 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Chỉ chấp nhận tệp định dạng hình ảnh (JPEG, PNG, WEBP, GIF, ...)");
        }
    }
}
