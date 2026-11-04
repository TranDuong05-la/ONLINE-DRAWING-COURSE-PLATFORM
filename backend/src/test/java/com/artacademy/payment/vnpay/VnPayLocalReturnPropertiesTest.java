package com.artacademy.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** http return/IPN URL chỉ được nhận khi bật chế độ chạy local; mặc định vẫn bắt buộc HTTPS. */
class VnPayLocalReturnPropertiesTest {

	private static VnPayProperties props(String returnUrl, String ipnUrl, boolean allowLocal) {
		VnPayProperties p = new VnPayProperties();
		p.setEnabled(true);
		p.setTmnCode("ABCD1234");
		p.setSecretKey("local-test-secret");
		p.setReturnUrl(returnUrl);
		p.setIpnUrl(ipnUrl);
		p.setAllowLocalHttpReturn(allowLocal);
		return p;
	}

	@Test
	void httpUrlsRejectedByDefault() {
		assertThatThrownBy(() -> props("http://10.0.2.2:8080/api/payments/vnpay/return",
				"https://example.com/api/payments/vnpay/ipn", false).validateReady())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
	}

	@Test
	void httpUrlsAcceptedOnlyInLocalMode() {
		assertThatCode(() -> props("http://10.0.2.2:8080/api/payments/vnpay/return",
				"http://10.0.2.2:8080/api/payments/vnpay/ipn", true).validateReady()).doesNotThrowAnyException();
	}

	@Test
	void localModeStillRejectsRelativeOrOtherSchemes() {
		assertThatThrownBy(() -> props("/api/payments/vnpay/return", "http://x/ipn", true).validateReady())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> props("ftp://x/return", "http://x/ipn", true).validateReady())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nineCharacterTmnCodeIsRejected() {
		VnPayProperties p = props("https://a.example/return", "https://a.example/ipn", false);
		p.setTmnCode("ABCDE1234");
		assertThatThrownBy(p::validateReady).hasMessageContaining("TMN");
	}
}
