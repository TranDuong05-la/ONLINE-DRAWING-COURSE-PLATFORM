package com.artacademy.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Issues short-lived HMAC links for uploaded lesson videos and PDFs. */
@Component
public class MediaUrlSigner {
	public static final String MEDIA_PREFIX = "/media/";
	private static final Pattern PROTECTED_UPLOAD = Pattern.compile("^/uploads/([A-Za-z0-9._-]+\\.(?:mp4|pdf))$", Pattern.CASE_INSENSITIVE);
	private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:mp4|pdf)$", Pattern.CASE_INSENSITIVE);
	private final byte[] secret;
	private final long ttlSeconds;
	private final Clock clock;

	@Autowired
	public MediaUrlSigner(@Value("${app.media.signing-secret:${app.jwt.secret}}") String secret,
			@Value("${app.media.url-ttl-seconds:7200}") long ttlSeconds) {
		this(secret, ttlSeconds, Clock.systemUTC());
	}
	public MediaUrlSigner(String secret, long ttlSeconds, Clock clock) {
		if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Media signing secret is required");
		if (ttlSeconds <= 0) throw new IllegalArgumentException("Media URL TTL must be positive");
		this.secret = secret.getBytes(StandardCharsets.UTF_8); this.ttlSeconds = ttlSeconds; this.clock = clock;
	}
	public long ttlSeconds() { return ttlSeconds; }
	public boolean isProtectedUpload(String storedUrl) { return storedUrl != null && PROTECTED_UPLOAD.matcher(storedUrl.trim()).matches(); }
	public String signIfProtected(String storedUrl) {
		if (storedUrl == null) return null;
		var matcher = PROTECTED_UPLOAD.matcher(storedUrl.trim());
		return matcher.matches() ? signFile(matcher.group(1)) : storedUrl;
	}
	public String signFile(String fileName) {
		if (!isSafeFileName(fileName)) throw new IllegalArgumentException("Unsafe media file name");
		long exp = clock.instant().getEpochSecond() + ttlSeconds;
		return MEDIA_PREFIX + fileName + "?exp=" + exp + "&sig=" + signature(fileName, exp);
	}
	public boolean isSafeFileName(String fileName) { return fileName != null && !fileName.contains("..") && SAFE_FILE_NAME.matcher(fileName).matches(); }
	public boolean verify(String fileName, Long exp, String sig) {
		if (!isSafeFileName(fileName) || exp == null || sig == null || exp <= clock.instant().getEpochSecond()) return false;
		byte[] expected = signature(fileName, exp).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
	}
	public static String extensionOf(String fileName) { int dot = fileName.lastIndexOf('.'); return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT); }
	private String signature(String fileName, long exp) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((fileName + "|" + exp).getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) { throw new IllegalStateException("Could not sign media URL", e); }
	}
}
