package com.artacademy.media;

import com.artacademy.common.ApiException;
import com.artacademy.common.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phục vụ video/PDF của khóa học qua link có chữ ký ({@link MediaUrlSigner}). Không cần đăng nhập vì chữ ký chính là
 * giấy phép; hết hạn hoặc sai chữ ký thì 403. Hỗ trợ header Range (video_player cần để tua).
 */
@RestController
public class MediaController {

    private final MediaUrlSigner signer;
    private final Path uploadDir;

    public MediaController(MediaUrlSigner signer, @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.signer = signer;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/media/{fileName}")
    public ResponseEntity<Resource> serve(@PathVariable String fileName,
                                          @RequestParam(required = false) Long exp,
                                          @RequestParam(required = false) String sig) {
        if (!signer.verify(fileName, exp, sig)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Link không hợp lệ hoặc đã hết hạn");
        }
        Path file = uploadDir.resolve(fileName).normalize();
        if (!file.startsWith(uploadDir) || !Files.isRegularFile(file)) {
            throw new NotFoundException("Không tìm thấy file");
        }
        MediaType type = "pdf".equals(MediaUrlSigner.extensionOf(fileName))
                ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("video/mp4");
        return ResponseEntity.ok()
                .contentType(type)
                // Không cho trình duyệt/proxy lưu lại: link có hạn nên không để bản sao sống lâu hơn
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}