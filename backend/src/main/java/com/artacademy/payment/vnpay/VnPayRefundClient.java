package com.artacademy.payment.vnpay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Gọi API hoàn tiền của VNPay (merchant_webapi, vnp_Command=refund, phiên bản 2.1.0).
 *
 * <p>Chữ ký gửi đi: HMAC-SHA512 trên các trường nối bằng "|" theo đúng thứ tự tài liệu VNPay. Phản hồi chỉ được
 * tin khi chữ ký phản hồi khớp và mã merchant / mã giao dịch / số tiền trùng với yêu cầu. Lỗi mạng hoặc phản hồi
 * không xác thực được trả về {@link Outcome#UNKNOWN} (không được coi là thất bại để tránh hoàn tiền hai lần).
 */
@Component
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
public class VnPayRefundClient {
	private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VN);
	private static final ObjectMapper JSON = new ObjectMapper();

	public enum Outcome { SUCCESS, DEFINITIVE_FAILURE, UNKNOWN }

	/**
	 * @param requestId     duy nhất cho mỗi lần gọi (≤ 32 ký tự), dùng làm khóa chống gọi trùng phía VNPay
	 * @param txnRef        vnp_TxnRef của giao dịch thanh toán gốc
	 * @param transactionNo vnp_TransactionNo VNPay cấp cho giao dịch gốc
	 * @param paymentCreatedAt thời điểm tạo giao dịch thanh toán gốc (vnp_CreateDate của lệnh pay)
	 * @param fullRefund    true: hoàn toàn bộ (02), false: hoàn một phần (03)
	 */
	public record RefundCommand(String requestId, String txnRef, String transactionNo, long amountVnd,
			Instant paymentCreatedAt, boolean fullRefund, String createdBy, String orderInfo) {
	}

	public record RefundResult(Outcome outcome, String responseCode, String message, String providerReference,
			String responseFingerprint) {
	}

	private final VnPayProperties properties;
	private final HttpClient http;
	private final String apiUrl;

	public VnPayRefundClient(VnPayProperties properties,
			@Value("${app.payment.vnpay.refund-api-url:https://sandbox.vnpayment.vn/merchant_webapi/api/transaction}") String apiUrl) {
		this.properties = Objects.requireNonNull(properties);
		this.apiUrl = apiUrl;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	public RefundResult refund(RefundCommand c) {
		properties.validateReady();
		Map<String, String> body = new LinkedHashMap<>();
		body.put("vnp_RequestId", c.requestId());
		body.put("vnp_Version", "2.1.0");
		body.put("vnp_Command", "refund");
		body.put("vnp_TmnCode", properties.getTmnCode());
		body.put("vnp_TransactionType", c.fullRefund() ? "02" : "03");
		body.put("vnp_TxnRef", c.txnRef());
		body.put("vnp_Amount", String.valueOf(Math.multiplyExact(c.amountVnd(), 100L)));
		body.put("vnp_TransactionNo", c.transactionNo() == null ? "0" : c.transactionNo());
		body.put("vnp_TransactionDate", DATE.format(c.paymentCreatedAt()));
		body.put("vnp_CreateBy", c.createdBy());
		body.put("vnp_CreateDate", DATE.format(Instant.now()));
		body.put("vnp_IpAddr", "127.0.0.1");
		body.put("vnp_OrderInfo", c.orderInfo());
		body.put("vnp_SecureHash", sign(String.join("|", body.get("vnp_RequestId"), body.get("vnp_Version"),
				body.get("vnp_Command"), body.get("vnp_TmnCode"), body.get("vnp_TransactionType"), body.get("vnp_TxnRef"),
				body.get("vnp_Amount"), body.get("vnp_TransactionNo"), body.get("vnp_TransactionDate"),
				body.get("vnp_CreateBy"), body.get("vnp_CreateDate"), body.get("vnp_IpAddr"), body.get("vnp_OrderInfo"))));

		final String raw;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(20))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			raw = response.body();
			if (response.statusCode() / 100 != 2 || raw == null || raw.isBlank()) {
				return unknown("HTTP " + response.statusCode(), raw);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return unknown("Interrupted", null);
		} catch (Exception e) {
			return unknown("Network error", null);
		}
		return interpret(c, body, raw);
	}

	RefundResult interpret(RefundCommand c, Map<String, String> sent, String raw) {
		final JsonNode r;
		try {
			r = JSON.readTree(raw);
		} catch (Exception e) {
			return unknown("Unreadable response", raw);
		}
		String code = text(r, "vnp_ResponseCode");
		String message = text(r, "vnp_Message");
		String expected = sign(String.join("|", text(r, "vnp_ResponseId"), text(r, "vnp_Command"), code, message,
				text(r, "vnp_TmnCode"), text(r, "vnp_TxnRef"), text(r, "vnp_Amount"), text(r, "vnp_BankCode"),
				text(r, "vnp_PayDate"), text(r, "vnp_TransactionNo"), text(r, "vnp_TransactionType"),
				text(r, "vnp_TransactionStatus"), text(r, "vnp_OrderInfo")));
		String provided = text(r, "vnp_SecureHash");
		String fingerprint = sha256(raw);
		boolean signed = !provided.isEmpty() && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
		if (!signed) {
			// Phản hồi lỗi định dạng của VNPay đôi khi không có chữ ký: không đủ tin cậy để kết luận.
			return new RefundResult(Outcome.UNKNOWN, code, "Unsigned or invalid VNPay response: " + message, null, fingerprint);
		}
		boolean sameTarget = properties.getTmnCode().equals(text(r, "vnp_TmnCode"))
				&& sent.get("vnp_TxnRef").equals(text(r, "vnp_TxnRef"));
		if ("00".equals(code)) {
			if (!sameTarget || !sent.get("vnp_Amount").equals(text(r, "vnp_Amount"))) {
				return new RefundResult(Outcome.UNKNOWN, code, "VNPay success does not match the request", null, fingerprint);
			}
			String reference = text(r, "vnp_TransactionNo");
			return new RefundResult(Outcome.SUCCESS, code, message,
					"vnpay-refund:" + (reference.isEmpty() ? c.requestId() : reference), fingerprint);
		}
		// 99: lỗi không xác định / đang xử lý phía VNPay -> chưa kết luận được.
		if ("99".equals(code) || code.isEmpty()) {
			return new RefundResult(Outcome.UNKNOWN, code, message, null, fingerprint);
		}
		return new RefundResult(Outcome.DEFINITIVE_FAILURE, code, message, null, fingerprint);
	}

	private RefundResult unknown(String message, String raw) {
		return new RefundResult(Outcome.UNKNOWN, "", message, null, sha256(raw == null ? message : raw));
	}

	private String sign(String data) {
		return VnPaySignature.hmacSha512Hex(properties.getSecretKey(), data);
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v == null || v.isNull() ? "" : v.asText();
	}

	static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
