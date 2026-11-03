package com.artacademy.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

public final class TextUtils {

    private TextUtils() {
    }

    /** "Vẽ màu nước cơ bản" -> "ve-mau-nuoc-co-ban" (bỏ dấu tiếng Việt, dùng làm slug). */
    public static String slugify(String text) {
        if (text == null) {
            return "khoa-hoc";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")      // bỏ các dấu đã tách ra
                .replace('đ', 'd').replace('Đ', 'D'); // đ không tách được bằng Normalizer nên phải thay tay
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.isEmpty()) {
            return "khoa-hoc";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Mã tự sinh, ví dụ "KH-3F9A1C2B". */
    public static String randomCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** Thoát ký tự đặc biệt của LIKE để người dùng gõ % hay _ thì vẫn tìm đúng chữ đó. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
    }
}
