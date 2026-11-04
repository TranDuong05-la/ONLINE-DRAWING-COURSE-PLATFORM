package com.artacademy.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Đọc phản hồi API hoàn tiền VNPay: chỉ tin phản hồi có chữ ký đúng và khớp yêu cầu. */
class VnPayRefundClientTest {
	private static final String SECRET = "unit-test-secret";

	private final VnPayRefundClient client = new VnPayRefundClient(props(), "https://unused.example/api");
	private final VnPayRefundClient.RefundCommand command = new VnPayRefundClient.RefundCommand("R1T1", "dca-abc",
			"123", 150_000, Instant.parse("2026-11-04T04:40:00Z"), true, "admin-1", "Hoan tien");
	private final Map<String, String> sent = Map.of("vnp_TxnRef", "dca-abc", "vnp_Amount", "15000000");

	private static VnPayProperties props() {
		VnPayProperties p = new VnPayProperties();
		p.setEnabled(true);
		p.setTmnCode("ABCD1234");
		p.setSecretKey(SECRET);
		p.setReturnUrl("https://a.example/return");
		p.setIpnUrl("https://a.example/ipn");
		return p;
	}

	private static String response(String code, String amount, boolean signed) {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("vnp_ResponseId", "resp-1");
		r.put("vnp_Command", "refund");
		r.put("vnp_ResponseCode", code);
		r.put("vnp_Message", code.equals("00") ? "Refund success" : "Refund failed");
		r.put("vnp_TmnCode", "ABCD1234");
		r.put("vnp_TxnRef", "dca-abc");
		r.put("vnp_Amount", amount);
		r.put("vnp_BankCode", "NCB");
		r.put("vnp_PayDate", "20261104114500");
		r.put("vnp_TransactionNo", "98765");
		r.put("vnp_TransactionType", "02");
		r.put("vnp_TransactionStatus", "05");
		r.put("vnp_OrderInfo", "Hoan tien");
		String hash = VnPaySignature.hmacSha512Hex(SECRET, String.join("|", r.values()));
		StringBuilder json = new StringBuilder("{");
		r.forEach((k, v) -> json.append('"').append(k).append("\":\"").append(v).append("\","));
		json.append("\"vnp_SecureHash\":\"").append(signed ? hash : "").append("\"}");
		return json.toString();
	}

	@Test
	void signedSuccessMatchingRequestIsSuccess() {
		var r = client.interpret(command, sent, response("00", "15000000", true));
		assertThat(r.outcome()).isEqualTo(VnPayRefundClient.Outcome.SUCCESS);
		assertThat(r.providerReference()).isEqualTo("vnpay-refund:98765");
	}

	@Test
	void signedErrorCodeIsDefinitiveFailureButCode99IsUnknown() {
		assertThat(client.interpret(command, sent, response("91", "15000000", true)).outcome())
				.isEqualTo(VnPayRefundClient.Outcome.DEFINITIVE_FAILURE);
		assertThat(client.interpret(command, sent, response("99", "15000000", true)).outcome())
				.isEqualTo(VnPayRefundClient.Outcome.UNKNOWN);
	}

	@Test
	void unsignedOrMismatchedResponsesAreNeverTreatedAsSuccess() {
		assertThat(client.interpret(command, sent, response("00", "15000000", false)).outcome())
				.isEqualTo(VnPayRefundClient.Outcome.UNKNOWN);
		assertThat(client.interpret(command, sent, response("00", "999", true)).outcome())
				.isEqualTo(VnPayRefundClient.Outcome.UNKNOWN);
		assertThat(client.interpret(command, sent, "not json").outcome()).isEqualTo(VnPayRefundClient.Outcome.UNKNOWN);
	}
}
