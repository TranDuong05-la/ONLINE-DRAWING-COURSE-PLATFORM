package com.artacademy.learning;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.learning.certificate.CertificateService;

/** Không cần đăng nhập, nhưng chỉ mở được bằng token ký số ngắn hạn cấp cho chủ chứng nhận. */
@RestController
@RequestMapping("/api/files/certificates")
public class CertificateFileController {
	private final CertificateService service;

	public CertificateFileController(CertificateService service) {
		this.service = service;
	}

	@GetMapping("/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam String token) {
		Certificate c = service.byFileToken(id, token);
		return LearningController.pdfResponse(c, service.pdfBytes(c));
	}
}
