package com.artacademy.learning.certificate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Short-lived signed tokens for certificate PDF download links.
 * Uses a key derived separately from the login-token key, so a download token can never pass as a login token.
 */
@Service
public class CertificateLinkTokens {
	private static final String PURPOSE = "cert-file";

	private final SecretKey key;

	public CertificateLinkTokens(@Value("${app.jwt.secret}") String secret) {
		try {
			byte[] derived = MessageDigest.getInstance("SHA-256")
					.digest((PURPOSE + ":" + secret).getBytes(StandardCharsets.UTF_8));
			this.key = Keys.hmacShaKeyFor(derived);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public String issueCertificateFileToken(Long certificateId, Duration ttl) {
		long now = System.currentTimeMillis();
		return Jwts.builder()
				.subject("cert:" + certificateId)
				.claim("purpose", PURPOSE)
				.issuedAt(new Date(now))
				.expiration(new Date(now + ttl.toMillis()))
				.signWith(key)
				.compact();
	}

	/** @return certificate id, or null when the token is invalid/expired. */
	public Long parseCertificateFileToken(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			if (!PURPOSE.equals(claims.get("purpose", String.class))) {
				return null;
			}
			return Long.valueOf(claims.getSubject().substring("cert:".length()));
		} catch (RuntimeException e) { // invalid, expired or malformed token
			return null;
		}
	}
}
