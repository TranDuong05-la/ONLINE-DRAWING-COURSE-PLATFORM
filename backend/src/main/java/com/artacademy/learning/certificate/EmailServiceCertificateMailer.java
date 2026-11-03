package com.artacademy.learning.certificate;

import org.springframework.stereotype.Component;

import com.artacademy.service.EmailService;

/** Gửi email chứng nhận bằng EmailService của A (template email/certificate.html). */
@Component
public class EmailServiceCertificateMailer implements CertificateMailer {
	private final EmailService emailService;

	public EmailServiceCertificateMailer(EmailService emailService) {
		this.emailService = emailService;
	}

	@Override
	public void send(String to, String fullName, String courseTitle, String certificateCode, String downloadUrl,
			String issueDate) {
		emailService.sendCertificateEmail(to, fullName, courseTitle, certificateCode, downloadUrl, issueDate);
	}
}
