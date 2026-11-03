package com.artacademy.upload;

import com.artacademy.common.ApiException;
import com.artacademy.common.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lưu file upload trên ổ đĩa của server. Giới hạn: ảnh 5MB, pdf 20MB, video (mp4) mặc định 2GB
 * (đổi bằng app.upload.max-video-mb). File lớn không nằm trong RAM: Spring ghi file tạm ra đĩa rồi chuyển vào thư mục upload.
 * Nhược điểm của cách này: đứt mạng giữa chừng phải tải lại từ đầu, file chỉ nằm trên một máy; muốn khác thì viết
 * class khác implements FileStorageService (ví dụ dịch vụ lưu trữ đám mây).
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private static final long MB = 1024L * 1024L;

    /** Quy tắc cho từng đuôi file: loại nội dung phải khớp và dung lượng tối đa. */
    private record Rule(String mimePrefix, long maxBytes) {
    }

    private final Map<String, Rule> rules = new HashMap<>();
    private final Path uploadDir;

    public LocalFileStorageService(@Value("${app.upload.dir:uploads}") String uploadDir,
                                   @Value("${app.upload.max-video-mb:2048}") long maxVideoMb) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        for (String image : new String[]{"jpg", "jpeg", "png", "webp"}) {
            rules.put(image, new Rule("image/", 5 * MB));
        }
        rules.put("mp4", new Rule("video/", maxVideoMb * MB));
        rules.put("pdf", new Rule("application/pdf", 20 * MB));
    }

    /** Dung lượng tối đa (byte) cho đuôi file, 0 nếu đuôi đó không được nhận. */
    public long maxBytesFor(String extension) {
        Rule rule = rules.get(extension.toLowerCase(Locale.ROOT));
        return rule == null ? 0 : rule.maxBytes();
    }

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Chưa chọn file để upload");
        }
        String ext = extensionOf(file.getOriginalFilename());
        Rule rule = rules.get(ext);
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
                    "File ." + ext + " vượt quá giới hạn " + describe(rule.maxBytes()));
        }

        // Tên file lưu là UUID ngẫu nhiên: không đoán được link và không bị lỗi tên tiếng Việt / ký tự lạ
        String storedName = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(uploadDir);
            // transferTo chuyển thẳng file tạm sang đích (nếu cùng ổ đĩa), tránh chép lại cả file nhiều GB
            file.transferTo(uploadDir.resolve(storedName));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lưu được file, vui lòng thử lại");
        }
        return "/uploads/" + storedName;
    }

    /** 5242880 -> "5MB", 2147483648 -> "2GB". */
    static String describe(long bytes) {
        long mb = bytes / MB;
        return mb >= 1024 && mb % 1024 == 0 ? (mb / 1024) + "GB" : mb + "MB";
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
