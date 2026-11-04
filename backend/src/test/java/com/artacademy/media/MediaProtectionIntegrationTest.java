package com.artacademy.media;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
		"app.payment.vnpay.enabled=false",
		"app.payment.vnpay.session-api.enabled=false",
		"app.payment.vnpay.ipn.enabled=false",
		"app.payment.vnpay.query-dr.enabled=false"
})
class MediaProtectionIntegrationTest extends IntegrationTestBase {
	@Autowired private MediaUrlSigner signer;
	@Value("${app.upload.dir}") private String uploadDir;

	@Test
	void uploadedVideoRequiresSignedLinkAndSignedLinkIsNotCacheable() throws Exception {
		Path dir = Path.of(uploadDir).toAbsolutePath().normalize();
		Files.createDirectories(dir); Files.writeString(dir.resolve("signed.mp4"), "video-bytes");
		mvc.perform(get("/uploads/signed.mp4")).andExpect(status().isUnauthorized());
		mvc.perform(get("/uploads/signed.mp4").with(admin())).andExpect(status().isNotFound());
		String url = signer.signFile("signed.mp4");
		assertTrue(Pattern.matches("/media/signed\\.mp4\\?exp=\\d+&sig=[A-Za-z0-9_-]+", url));
		mvc.perform(get(url)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}

	@Test
	void invalidSignatureAndMissingParametersAreRejected() throws Exception {
		mvc.perform(get("/media/missing.mp4")).andExpect(status().isForbidden());
		String url = signer.signFile("missing.mp4");
		mvc.perform(get(url + "x")).andExpect(status().isForbidden());
	}
}
