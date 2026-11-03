package com.artacademy.service;

import com.artacademy.enums.TokenType;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import com.artacademy.security.AuthenticatedUserPrincipal;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.jwt.access-expiration-ms}") long accessExpirationMs,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
            if (keyBytes.length < 32) {
                keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /**
     * Generate an access token for the given user.
     */
    public String generateAccessToken(UserDetails userDetails) {
        return buildToken(userDetails, accessExpirationMs, TokenType.ACCESS);
    }

    /**
     * Generate a stateless JWT refresh token for the given user.
     */
    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(userDetails, refreshExpirationMs, TokenType.REFRESH);
    }

    /**
     * Validate a stateless refresh token.
     */
    public boolean isRefreshTokenValid(String token) {
        try {
            final String tokenType = extractTokenType(token);
            return !isTokenExpired(token) && TokenType.REFRESH.name().equals(tokenType);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid refresh token: {}", e.getMessage());
            return false;
        }
    }

    /** Validates a refresh token against the current persisted user token generation. */
    public boolean isRefreshTokenValid(String token, int currentTokenVersion) {
        try {
            return isRefreshTokenValid(token)
                    && extractTokenVersion(token) == currentTokenVersion;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid refresh token version");
            return false;
        }
    }

    /**
     * Generate a cryptographically random refresh token string (fallback).
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Get the access token expiration in milliseconds.
     */
    public long getAccessExpirationMs() {
        return accessExpirationMs;
    }

    /**
     * Get the refresh token expiration in milliseconds.
     */
    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }

    /**
     * Extract the subject (email) from the token.
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract the token type from the token claims.
     */
    public String extractTokenType(String token) {
        return extractClaim(token, claims -> claims.get("type", String.class));
    }

    /** Legacy tokens pre-dating V14 are version 1; subsequent account changes increment the DB value. */
    public int extractTokenVersion(String token) {
        return extractClaim(token, claims -> {
            Number version = claims.get("token_version", Number.class);
            if (version == null) return 1;
            long parsed = version.longValue();
            if (parsed < 1 || parsed > Integer.MAX_VALUE || version.doubleValue() != parsed) {
                throw new IllegalArgumentException("Invalid token version claim");
            }
            return (int) parsed;
        });
    }

    /**
     * Validate the token against the user details.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            final String tokenType = extractTokenType(token);
            int currentTokenVersion = userDetails instanceof AuthenticatedUserPrincipal principal
                    ? principal.getTokenVersion() : 1;
            return username.equals(userDetails.getUsername())
                    && !isTokenExpired(token)
                    && TokenType.ACCESS.name().equals(tokenType)
                    && extractTokenVersion(token) == currentTokenVersion;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Hash a refresh token using SHA-256 for secure storage.
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String buildToken(UserDetails userDetails, long expirationMs, TokenType tokenType) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        int tokenVersion = userDetails instanceof AuthenticatedUserPrincipal principal
                ? principal.getTokenVersion() : 1;
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claims(Map.of("type", tokenType.name(), "token_version", tokenVersion))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }
}
