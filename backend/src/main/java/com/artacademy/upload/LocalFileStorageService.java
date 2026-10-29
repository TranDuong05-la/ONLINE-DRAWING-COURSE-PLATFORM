package com.artacademy.upload;

import com.artacademy.common.ApiException;
import com.artacademy.common.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalFileStorageService implements FileStorageService {

    private static final long MB = 1024L * 1024L;

    /** Quy tắc cho từng đuôi file: loại nội dung phải khớp và dung lượng tối đa. */
    private record Rule(String mimePrefix, long maxBytes) {
    }

    private static final Map<String, Rule> RULES = Map.of(
            "jpg", new Rule("image/", 5 * MB),
            "jpeg", new Rule("image/", 5 * MB),
            "png", new Rule("image/", 5 * MB),
            "webp", new Rule("image/", 5 * MB),
            "mp4", new Rule("video/", 200 * MB),
            "pdf", new Rule("application/pdf", 20 * MB));

    private final Path uploadDir;

    public LocalFileStorageService(@Value("${app.upload.dir:uploads}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Chưa chọn file để upload");
        }
        String ext = extensionOf(file.getOriginalFilename());
        Rule rule = RULES.get(ext);
        if (rule == null) {
            throw new BadRequestException("Chỉ nhận file jpg, png, webp, mp4 hoặc pdf");
        }
        // Một số máy gửi application/octet-stream nên chỉ kiểm tra khi có loại nội dung cụ thể
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !contentType.equalsIgnoreCase("application/octet-stream")
                && !contentType.toLowerCase(Locale.ROOT).startsWith(rule.mimePrefix())) {
            throw new BadRequestException("Loại nội dung của file không khớp với đuôi ." + ext);
        }
        if (file.getSize() > rule.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File ." + ext + " vượt quá giới hạn " + (rule.maxBytes() / MB) + "MB");
        }

        // Tên file lưu là UUID ngẫu nhiên: không đoán được link và không bị lỗi tên tiếng Việt / ký tự lạ
        String storedName = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(uploadDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, uploadDir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lưu được file, vui lòng thử lại");
        }
        return "/uploads/" + storedName;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
