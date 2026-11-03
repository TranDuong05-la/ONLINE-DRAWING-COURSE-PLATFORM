package com.artacademy.learning.certificate;

/** Cổng gửi email chứng nhận. Bản chạy thật dùng dịch vụ email của A; test thay bằng bản giả. */
public interface CertificateMailer {
	/** Ném exception khi gửi thất bại (để C ghi email_status = FAILED). */
	void send(String to, String fullName, String courseTitle, String certificateCode, String downloadUrl,
			String issueDate);
}
