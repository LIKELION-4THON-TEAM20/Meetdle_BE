package com.likelion4thon.Meetdle.auth.kakao;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoClient {
    private static final String AUTH = "https://kauth.kakao.com";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String key;
    private final String secret;
    private final String redirectUri;

    public KakaoClient(@Value("${meetdle.kakao.rest-api-key:}") String key,
                       @Value("${meetdle.kakao.client-secret:}") String secret,
                       @Value("${meetdle.kakao.redirect-uri:http://localhost:8080/api/auth/kakao/callback}") String redirectUri) {
        this.key = key;
        this.secret = secret;
        this.redirectUri = redirectUri;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public URI authorize(String state) {
        return UriComponentsBuilder.fromUriString(AUTH + "/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "{key}")
                .queryParam("redirect_uri", "{redirect}")
                .queryParam("state", "{state}")
                .encode().buildAndExpand(key, redirectUri, state).toUri();
    }

    public Profile profile(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", key);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (!secret.isBlank()) {
            form.add("client_secret", secret);
        }
        var token = client.post().uri(AUTH + "/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JSON);
        if (token == null || !(token.get("access_token") instanceof String accessToken) || accessToken.isBlank()) {
            throw new IllegalStateException("Kakao access token missing");
        }
        var user = client.get().uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(JSON);
        if (user == null || !(user.get("id") instanceof Number id)) {
            throw new IllegalStateException("Kakao user ID missing");
        }
        String nickname = "카카오 사용자";
        if (user.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String name && !name.isBlank()) {
            nickname = name.strip();
        }
        int length = Math.min(100, nickname.codePointCount(0, nickname.length()));
        return new Profile(id.toString(), nickname.substring(0, nickname.offsetByCodePoints(0, length)));
    }

    public record Profile(String id, String nickname) {}
}
