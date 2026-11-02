package com.artacademy.service;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public final class GoogleTokenInfoHttpClient implements GoogleTokenInfoClient {
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetch(String idToken) {
        RestTemplate restTemplate = new RestTemplate();
        String tokenInfoUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken.trim();
        return restTemplate.getForObject(tokenInfoUrl, Map.class);
    }
}