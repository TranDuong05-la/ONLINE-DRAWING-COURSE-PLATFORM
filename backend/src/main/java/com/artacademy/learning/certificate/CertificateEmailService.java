package com.artacademy.learning.certificate;

import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.artacademy.learning.Certificate;
import com.artacademy.learning.CertificateRepository;
import com.artacademy.repository.UserProfileRepository;

/**
 * Gửi email chứng nhận NGOÀI mọi transaction DB, sau khi chứng nhận đã được lưu.
 * Lỗi chỉ đổi email_status = FAILED; chứng nhận và trạng thái hoàn thành giữ nguyên, gửi lại được.
 */
@Service
public class CertificateEmailService {
	private static final Logger log = LoggerFactory.getLogger(CertificateEmailService.class);

	private final CertificateService certificateService;
	private final CertificateRepository certificates;
	private final UserProfileRepository profiles;
	private final CertificateMailer mailer;
	private final TransactionTemplate readTx;

	public CertificateEmailService(CertificateService certificateService, CertificateRepository certificates,
			UserProfileRepository profiles, CertificateMailer mailer, PlatformTransactionManager tx) {
		this.certificateService = certificateService;
		this.certificates = certificates;
		this.profiles = profiles;
		this.mailer = mailer;
		this.readTx = new TransactionTemplate(tx);
		this.readTx.setReadOnly(true);
	}

	/** Lần gửi đầu: chạy ở luồng nền sau khi transaction cấp chứng nhận đã commit. */
	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onIssued(CertificateIssuedEvent event) {
		try {
			certificateService.reserveEmailAttempt(event.certificateId(), false);
		} catch (RuntimeException e) {
			log.warn("Could not reserve email attempt for certificate {}: {}", event.certificateId(), e.getMessage());
			return;
		}
		deliver(event.certificateId());
	}

	/** Gửi lại: người gọi đã giữ lượt gửi (và đã kiểm tra giới hạn). */
	@Async
	public void deliverAsync(Long certificateId) {
		deliver(certificateId);
	}

	/** Gửi đồng bộ, không bao giờ ném lỗi. Public để test gọi trực tiếp. */
	public void deliver(Long certificateId) {
		try {
			Recipient r = recipient(certificateId);
			mailer.send(r.email(), r.certificate().getLearnerName(), r.certificate().getCourseTitle(),
					r.certificate().getCertificateCode(), certificateService.emailLink(certificateId),
					r.certificate().getIssuedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
			certificateService.recordEmailResult(certificateId, true, null);
		} catch (Exception e) {
			log.warn("Certificate email {} failed: {}", certificateId, e.getMessage());
			try {
				certificateService.recordEmailResult(certificateId, false, e.getMessage());
			} catch (Exception inner) {
				log.error("Could not record email failure for certificate {}", certificateId, inner);
			}
		}
	}

	private record Recipient(Certificate certificate, String email) {
	}

	/** Đọc dữ liệu cần gửi trong một transaction ngắn (profile.user là quan hệ LAZY), rồi gửi ngoài transaction. */

	private Recipient recipient(Long certificateId) {
		return readTx.execute(s -> loadRecipient(certificateId));
	}

	private Recipient loadRecipient(Long certificateId) {
		Certificate c = certificates.findById(certificateId).orElseThrow();
		var profile = profiles.findById(c.getLearnerProfileId()).orElseThrow();
		return new Recipient(c, profile.getUser().getEmail());
	}
}
