package com.artacademy.utility;

import java.security.SecureRandom;

/**
 * Utility class for generating secure random codes and tokens.
 * Can be used across modules for generating verification codes,
 * order codes, or other random identifiers.
 */
public final class CodeGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String NUMERIC = "0123456789";

    private CodeGenerator() {
        // Utility class, prevent instantiation
    }

    /**
     * Generate a secure random alphanumeric string of the given length.
     *
     * @param length the desired length
     * @return random alphanumeric string
     */
    public static String generateAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Generate a secure random numeric string of the given length.
     *
     * @param length the desired length
     * @return random numeric string
     */
    public static String generateNumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NUMERIC.charAt(SECURE_RANDOM.nextInt(NUMERIC.length())));
        }
        return sb.toString();
    }
}
