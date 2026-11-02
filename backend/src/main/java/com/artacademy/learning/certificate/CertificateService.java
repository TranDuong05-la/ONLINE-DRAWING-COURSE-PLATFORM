package com.artacademy.learning.certificate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.Course;
import com.artacademy.course.CourseRepository;
import com.artacademy.learning.Certificate;
import com.artacademy.learning.CertificateRepository;
import com.artacademy.learning.Errors;
import com.artacademy.learning.LearnerProfileService;
import com.artacademy.learning.notification.LearningNotification;
import com.artacademy.learning.notification.NotificationRequest;
import com.artacademy.learning.storage.FileStorage;

/**
 * Cấp và quản lý chứng nhận hoàn thành.
 *
 * <p>Thứ tự bắt buộc: (1) tạo PDF và LƯU chứng nhận vào DB trong transaction đang khóa enrollment,
 * (2) phát sự kiện, (3) SAU KHI commit mới gọi dịch vụ email của A ({@link CertificateEmailService}).
 * Vì vậy email lỗi không làm mất chứng nhận; học viên vẫn tải được trong app và gửi lại email được.
 */
@Service
public class CertificateService {
	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	private final CertificateRepository certificates;
	private final CourseRepository courses;
	private final LearnerProfileService profiles;
	private final FileStorage storage;
	private final CertificateLinkTokens tokens;
	private final ApplicationEventPublisher events;
	private final SecureRandom random = new SecureRandom();
	private final String secret;
	private final int maxAttempts;
	private final long cooldownSeconds;
	private final long linkTtlSeconds;
	private final long emailLinkTtlSeconds;
	private final String publicBaseUrl;

	public CertificateService(CertificateRepository certificates, CourseRepository courses,
			LearnerProfileService profiles, FileStorage storage, CertificateLinkTokens tokens,
			ApplicationEventPublisher events, @Value("${app.jwt.secret}") String secret,
			@Value("${app.certificate.email-max-attempts:5}") int maxAttempts,
			@Value("${app.certificate.email-cooldown-seconds:300}") long cooldownSeconds,
			@Value("${app.certificate.download-link-ttl-seconds:300}") long linkTtlSeconds,
			@Value("${app.certificate.email-link-ttl-seconds:604800}") long emailLinkTtlSeconds,
			@Value("${app.public-base-url:http://10.0.2.2:8080}") String publicBaseUrl) {
		this.certificates = certificates;
		this.courses = courses;
		this.profiles = profiles;
		this.storage = storage;
		this.tokens = tokens;
		this.events = events;
		this.secret = secret;
		this.maxAttempts = maxAttempts;
		this.cooldownSeconds = cooldownSeconds;
		this.linkTtlSeconds = linkTtlSeconds;
		this.emailLinkTtlSeconds = emailLinkTtlSeconds;
		this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
				: publicBaseUrl;
	}

	/**
	 * Idempotent: đã có thì trả lại chứng nhận cũ. Phải chạy trong transaction của người gọi; transaction đó đã
	 * khóa dòng enrollment nên các lần hoàn thành đồng thời chạy lần lượt. Unique constraint là lớp chặn cuối.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Certificate issueIfMissing(Integer learnerProfileId, Long courseId) {
		var existing = certificates.findByLearnerProfileIdAndCourseId(learnerProfileId, courseId);
		if (existing.isPresent()) {
			return existing.get();
		}
		Course course = courses.findById(courseId).orElseThrow(() -> Errors.notFound("Không tìm thấy khóa học"));
		String learnerName = profiles.displayName(learnerProfileId);
		LocalDateTime now = LocalDateTime.now().withNano(0);
		String code = newCode(now);
		String hash = sign(code + "|" + learnerProfileId + "|" + courseId + "|" + now);
		byte[] pdf = CertificatePdf.generate(learnerName, course.getTitle(), code, now, hash.substring(0, 24));
		String key = storage.save("certificates", pdf, "pdf");
		Certificate c = new Certificate();
		c.setCertificateCode(code);
		c.setLearnerProfileId(learnerProfileId);
		c.setCourseId(courseId);
		c.setLearnerName(truncate(learnerName, 100));
		c.setCourseTitle(truncate(course.getTitle(), 200));
		c.setPdfKey(key);
		c.setSignatureHash(hash);
		c.setIssuedAt(now);
		try {
			c = certificates.saveAndFlush(c);
		} catch (DataIntegrityViolationException e) {
			storage.deleteQuietly(key);
			throw e;
		}
		// Chỉ phát tín hiệu. Email được gửi SAU KHI commit (CertificateEmailService, @TransactionalEventListener).
		events.publishEvent(new CertificateIssuedEvent(c.getId()));
		events.publishEvent(NotificationRequest.toUser(profiles.userIdOf(learnerProfileId),
				LearningNotification.Type.CERTIFICATE, "CERT:" + c.getId(), "Bạn đã nhận chứng nhận hoàn thành",
				c.getCourseTitle() + "\nMã: " + c.getCertificateCode(), courseId, null, c.getId()));
		return c;
	}

	@Transactional(readOnly = true)
	public List<Certificate> listMine(Integer userId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		return profileId == null ? List.of() : certificates.findByLearnerProfileIdOrderByIssuedAtDesc(profileId);
	}

	/** Chứng nhận của chính user; của người khác trả 404 để không lộ là có tồn tại. */
	@Transactional(readOnly = true)
	public Certificate getOwned(Integer userId, Long certificateId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		return certificates.findById(certificateId).filter(c -> c.getLearnerProfileId().equals(profileId))
				.orElseThrow(() -> Errors.notFound("Không tìm thấy chứng nhận"));
	}

	/** Admin: danh sách chứng nhận đã cấp (mọi học viên). */
	@Transactional(readOnly = true)
	public org.springframework.data.domain.Page<Certificate> searchAll(Long courseId, String q, int page, int size) {
		String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
		return certificates.search(courseId, like,
				org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
	}

	/** Admin mở PDF của bất kỳ chứng nhận nào. */
	@Transactional(readOnly = true)
	public String adminDownloadLink(Long certificateId) {
		certificates.findById(certificateId).orElseThrow(() -> Errors.notFound("Không tìm thấy chứng nhận"));
		return link(certificateId, linkTtlSeconds);
	}

	public byte[] pdfBytes(Certificate c) {
		return storage.read(c.getPdfKey());
	}

	/** Link ngắn hạn để trình xem PDF mở file mà không cần header đăng nhập của app. */
	public String downloadLink(Integer userId, Long certificateId) {
		return link(getOwned(userId, certificateId).getId(), linkTtlSeconds);
	}

	/** Link dài hạn hơn để đặt trong email. */
	public String emailLink(Long certificateId) {
		return link(certificateId, emailLinkTtlSeconds);
	}

	private String link(Long certificateId, long ttlSeconds) {
		String token = tokens.issueCertificateFileToken(certificateId, Duration.ofSeconds(ttlSeconds));
		return publicBaseUrl + "/api/files/certificates/" + certificateId + "/pdf?token=" + token;
	}

	@Transactional(readOnly = true)
	public Certificate byFileToken(Long certificateId, String token) {
		Long id = tokens.parseCertificateFileToken(token == null ? "" : token);
		if (id == null || !id.equals(certificateId)) {
			throw Errors.forbidden("Liên kết tải không hợp lệ hoặc đã hết hạn");
		}
		return certificates.findById(id).orElseThrow(() -> Errors.notFound("Không tìm thấy chứng nhận"));
	}

	public boolean canResend(Certificate c) {
		return c.getEmailAttempts() < maxAttempts;
	}

	/**
	 * Giữ trước một lượt gửi email (đếm và đóng dấu thời gian) TRƯỚC khi gửi, nên bấm gửi lại liên tục hay đồng
	 * thời cũng không làm ngập máy chủ mail.
	 */
	@Transactional
	public Certificate reserveEmailAttempt(Long certificateId, boolean enforceLimits) {
		Certificate c = certificates.findById(certificateId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy chứng nhận"));
		LocalDateTime now = LocalDateTime.now();
		if (enforceLimits) {
			if (c.getEmailAttempts() >= maxAttempts) {
				throw Errors.tooManyRequests("Đã đạt giới hạn " + maxAttempts + " lần gửi email");
			}
			if (c.getLastEmailAttemptAt() != null
					&& c.getLastEmailAttemptAt().plusSeconds(cooldownSeconds).isAfter(now)) {
				long wait = Duration.between(now, c.getLastEmailAttemptAt().plusSeconds(cooldownSeconds)).getSeconds()
						+ 1;
				throw Errors.tooManyRequests("Vui lòng thử lại sau " + wait + " giây");
			}
		}
		c.setEmailAttempts(c.getEmailAttempts() + 1);
		c.setLastEmailAttemptAt(now);
		c.setEmailStatus(Certificate.EmailStatus.PENDING);
		return certificates.save(c);
	}

	@Transactional
	public void recordEmailResult(Long certificateId, boolean ok, String error) {
		Certificate c = certificates.findById(certificateId).orElseThrow();
		if (ok) {
			c.setEmailStatus(Certificate.EmailStatus.SENT);
			c.setEmailSentAt(LocalDateTime.now());
			c.setEmailError(null);
		} else {
			c.setEmailStatus(Certificate.EmailStatus.FAILED);
			c.setEmailError(truncate(error == null ? "Lỗi không xác định" : error, 250));
		}
		certificates.save(c);
	}

	private String newCode(LocalDateTime now) {
		for (int i = 0; i < 10; i++) {
			StringBuilder sb = new StringBuilder("ART-CERT-").append(now.getYear()).append('-');
			for (int j = 0; j < 8; j++) {
				sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
			}
			String code = sb.toString();
			if (!certificates.existsByCertificateCode(code)) {
				return code;
			}
		}
		throw new IllegalStateException("Không thể tạo mã chứng nhận duy nhất");
	}

	private String sign(String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String truncate(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
	}
}
