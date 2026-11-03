package com.artacademy.common;

/** Quy tắc chung cho các trường chứa đường dẫn file (ảnh bìa, video, tài liệu). */
public final class UrlRules {

    private UrlRules() {
    }

    /**
     * Chấp nhận đường dẫn tương đối của file đã upload ("/uploads/abc.mp4") hoặc link ngoài (http/https).
     * Chuỗi rỗng đổi thành null.
     */
    public static String normalize(String url, String label) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String v = url.trim();
        if (v.length() > 500) {
            throw new BadRequestException(label + " quá dài (tối đa 500 ký tự)");
        }
        boolean ok = v.startsWith("/uploads/") || v.startsWith("http://") || v.startsWith("https://");
        if (!ok) {
            throw new BadRequestException(label + " phải là link http(s) hoặc đường dẫn bắt đầu bằng /uploads/");
        }
        return v;
    }
}
