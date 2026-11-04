package com.artacademy.payment.vnpay;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trang VNPay chuyển trình duyệt về sau khi thanh toán (vnp_ReturnUrl), CHỈ bật khi chạy trên máy cá nhân
 * ({@code app.payment.vnpay.allow-local-http-return=true}).
 *
 * <p>Khi chạy local, máy chủ VNPay không gọi IPN về localhost/10.0.2.2 được. Trang này nhận đúng bộ tham số đã ký
 * mà VNPay gắn vào return URL, kiểm tra chữ ký và đối soát bằng CHÍNH luồng IPN ({@link VnPayCallbackVerifier} +
 * {@link VnPayIpnReconciliationService}, idempotent). Vì vậy giả mạo tham số không qua được kiểm tra chữ ký, và
 * VNPay gửi IPN thật sau đó (khi deploy) cũng không bị ghi nhận hai lần. Môi trường thật để cờ này tắt và dùng IPN.
 */
@RestController
@RequestMapping("/api/payments/vnpay")
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = { "enabled", "allow-local-http-return" }, havingValue = "true")
public final class VnPayReturnController {
	private final VnPayCallbackVerifier verifier;
	private final VnPayIpnReconciliationService reconciliation;

	public VnPayReturnController(VnPayCallbackVerifier verifier, VnPayIpnReconciliationService reconciliation) {
		this.verifier = Objects.requireNonNull(verifier);
		this.reconciliation = Objects.requireNonNull(reconciliation);
	}

	@GetMapping(path = "/return", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> returnPage(@RequestParam MultiValueMap<String, String> parameters) {
		Map<String, List<String>> preserved = VnPayIpnController.preserveBoundedParameters(parameters);
		if (preserved == null) {
			return page(false, "Liên kết thanh toán không hợp lệ.");
		}
		final VerifiedVnPayCallback verified;
		try {
			verified = verifier.verify(preserved);
		} catch (RuntimeException invalid) {
			return page(false, "Không xác thực được kết quả thanh toán từ VNPay.");
		}
		final IpnReconciliationResult result;
		try {
			result = reconciliation.reconcile(verified);
		} catch (RuntimeException unavailable) {
			return page(false, "Hệ thống đang bận, chưa ghi nhận được kết quả. Vui lòng mở lại ứng dụng sau ít phút.");
		}
		boolean paid = "00".equals(first(preserved, "vnp_ResponseCode"))
				&& "00".equals(first(preserved, "vnp_TransactionStatus"));
		if (paid && (result.classification() == IpnClassification.VALID_ON_TIME_SUCCESS
				|| result.classification() == IpnClassification.DUPLICATE_CALLBACK)) {
			return page(true, "Thanh toán thành công. Quay lại ứng dụng ArtNest, khóa học sẽ có trong mục \"Khóa của tôi\".");
		}
		if (!paid) {
			return page(false, "Giao dịch chưa hoàn tất hoặc đã bị hủy. Bạn có thể thanh toán lại trong ứng dụng.");
		}
		return page(false, "Đã nhận kết quả từ VNPay nhưng cần nhân viên kiểm tra thêm (mã: "
				+ result.classification().name() + ").");
	}

	private static String first(Map<String, List<String>> p, String key) {
		List<String> v = p.get(key);
		return v == null || v.isEmpty() ? null : v.getFirst();
	}

	private static ResponseEntity<String> page(boolean ok, String message) {
		String color = ok ? "#2D6A4F" : "#B45309";
		String title = ok ? "Thanh toán thành công" : "Chưa hoàn tất thanh toán";
		String html = """
				<!doctype html><html lang="vi"><head><meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title>
				<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#F8F9FA;margin:0;
				display:flex;min-height:100vh;align-items:center;justify-content:center;padding:16px}
				.card{background:#fff;border-radius:16px;padding:28px;max-width:420px;box-shadow:0 4px 20px rgba(0,0,0,.08);text-align:center}
				h1{color:%s;font-size:22px;margin:0 0 12px}p{color:#334155;line-height:1.5;margin:0}</style></head>
				<body><div class="card"><h1>%s</h1><p>%s</p></div></body></html>
				""".formatted(title, color, title, escape(message));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML).body(html);
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
