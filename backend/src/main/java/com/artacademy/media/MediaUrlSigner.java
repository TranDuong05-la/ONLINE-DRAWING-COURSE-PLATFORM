package com.artacademy.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tạo và kiểm tra LINK CÓ CHỮ KÝ cho video/PDF của khóa học.
 *
 * Vì sao cần: file video/pdf không còn được phục vụ công khai ở /uploads/**. Người có quyền xem (đã đăng ký khóa,
 * học thử, hoặc admin) nhận từ API một link dạng /media/{file}?exp=...&sig=... có hạn (mặc định 2 giờ). Link tự chứa
 * "giấy phép", nên video_player và trình duyệt tải được mà không cần gửi kèm token đăng nhập.
 *
 * Quyền xem được kiểm tra Ở NƠI CẤP LINK (C kiểm tra đã đăng ký trước khi gọi CourseContentService; B tự cấp link cho
 * bài học thử và cho admin). Ảnh (jpg, png, webp) vẫn công khai ở /uploads/**. Link http(s) bên ngoài giữ nguyên.
 */
@Component
public class MediaUrlSigner {

    public static final String MEDIA_PREFIX = "/media/";

    /** File video/pdf do B lưu: /uploads/{tên}.mp4 hoặc .pdf (tên không chứa dấu / hay ký tự lạ). */
    private static final Pattern PROTECTED_UPLOAD = Pattern.compile("^/uploads/([A-Za-z0-9._-]+\\.(?:mp4|pdf))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:mp4|pdf)$",
            Pattern.CASE_INSENSITIVE);

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public MediaUrlSigner(@Value("${app.media.signing-secret:${app.jwt.secret}}") String secret,
                          @Value("${app.media.url-ttl-seconds:7200}") long ttlSeconds) {
        this(secret, ttlSeconds, Clock.systemUTC());
    }

    /** Dùng cho test: truyền đồng hồ giả để thử link hết hạn. */
    public MediaUrlSigner(String secret, long ttlSeconds, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Thiếu khóa ký link media (app.media.signing-secret hoặc app.jwt.secret)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** true nếu đây là file video/pdf do B lưu trên server (cần ký). Ảnh và link ngoài thì false. */
    public boolean isProtectedUpload(String storedUrl) {
        return storedUrl != null && PROTECTED_UPLOAD.matcher(storedUrl.trim()).matches();
    }

    /**
     * Link dùng được trong ttlSeconds nếu là file video/pdf trên server; ngược lại (link ngoài, ảnh, rỗng) trả nguyên giá trị.
     * Chỉ gọi hàm này SAU KHI đã kiểm tra người dùng được phép xem.
     */
    public String signIfProtected(String storedUrl) {
        if (storedUrl == null) {
            return null;
        }
        var matcher = PROTECTED_UPLOAD.matcher(storedUrl.trim());
        return matcher.matches() ? signFile(matcher.group(1)) : storedUrl;
    }

    public String signFile(String fileName) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        return MEDIA_PREFIX + fileName + "?exp=" + exp + "&sig=" + signature(fileName, exp);
    }

    /** Tên file hợp lệ để phục vụ: chỉ .mp4/.pdf, không có dấu phân cách đường dẫn hay "..". */
    public boolean isSafeFileName(String fileName) {
        return fileName != null && !fileName.contains("..") && SAFE_FILE_NAME.matcher(fileName).matches();
    }

    /** Chữ ký đúng cho đúng file này và chưa hết hạn. */
    public boolean verify(String fileName, Long exp, String sig) {
        if (!isSafeFileName(fileName) || exp == null || sig == null || exp <= clock.instant().getEpochSecond()) {
            return false;
        }
        byte[] expected = signature(fileName, exp).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    /** Đuôi file (mp4/pdf) viết thường, để chọn Content-Type. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String signature(String fileName, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((fileName + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được link media", e);
        }
    }
}