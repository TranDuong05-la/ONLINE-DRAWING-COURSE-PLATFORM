package com.artacademy.service;

import java.util.Map;

/** Secondary Google tokeninfo transport boundary, kept injectable for deterministic tests. */
@FunctionalInterface
public interface GoogleTokenInfoClient {
    Map<String, Object> fetch(String idToken);
}