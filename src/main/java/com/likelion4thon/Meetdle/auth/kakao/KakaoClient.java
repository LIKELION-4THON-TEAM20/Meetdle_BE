package com.likelion4thon.Meetdle.auth.kakao;

import com.likelion4thon.Meetdle.common.logging.FeatureLog;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.Event;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.Flag;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.Provider;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoClient {
    private static final String AUTH = "https://kauth.kakao.com";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String key;
    private final String secret;
    private final String redirectUri;

    @Autowired
    public KakaoClient(@Value("${meetdle.kakao.rest-api-key:}") String key,
                       @Value("${meetdle.kakao.client-secret:}") String secret,
                       @Value("${meetdle.kakao.redirect-uri:http://localhost:8080/api/auth/kakao/callback}") String redirectUri) {
        this(key, secret, redirectUri, createClient());
    }

    KakaoClient(String key, String secret, String redirectUri, RestClient client) {
        this.key = key;
        this.secret = secret;
        this.redirectUri = redirectUri;
        this.client = client;
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
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
        var token = call(Provider.KAKAO_TOKEN, () -> client.post().uri(AUTH + "/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JSON));
        if (token == null || !(token.get("access_token") instanceof String accessToken) || accessToken.isBlank()) {
            FeatureLog.warn(Event.KAKAO_REQUEST_FAILED, null,
                    Map.of("provider", Provider.KAKAO_TOKEN, "validBody", false));
            throw new IllegalStateException("Kakao access token missing");
        }
        var user = call(Provider.KAKAO_PROFILE, () -> client.get().uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(JSON));
        if (user == null || !(user.get("id") instanceof Number id)) {
            FeatureLog.warn(Event.KAKAO_REQUEST_FAILED, null,
                    Map.of("provider", Provider.KAKAO_PROFILE, "validBody", false));
            throw new IllegalStateException("Kakao user ID missing");
        }
        String nickname = "카카오 사용자";
        if (user.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String name && !name.isBlank()) {
            nickname = name.strip();
        }
        int length = Math.min(100, nickname.codePointCount(0, nickname.length()));
        String imageUrl = null;
        if (user.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && !Boolean.TRUE.equals(profile.get("is_default_image"))
                && profile.get("profile_image_url") instanceof String image) {
            imageUrl = secureImageUrl(image);
        }
        Map<?, ?> account = user.get("kakao_account") instanceof Map<?, ?> value ? value : Map.of();
        Map<?, ?> profile = account.get("profile") instanceof Map<?, ?> value ? value : Map.of();
        Object rawImage = profile.get("profile_image_url");
        PhotoOutcome outcome = imageUrl != null ? PhotoOutcome.AVAILABLE
                : !(user.get("kakao_account") instanceof Map<?, ?>) ? PhotoOutcome.ACCOUNT_MISSING
                : !(account.get("profile") instanceof Map<?, ?>) ? PhotoOutcome.PROFILE_MISSING
                : Boolean.TRUE.equals(profile.get("is_default_image")) ? PhotoOutcome.DEFAULT_IMAGE
                : !(rawImage instanceof String image) || image.isBlank() ? PhotoOutcome.URL_MISSING
                : PhotoOutcome.URL_REJECTED;
        FeatureLog.info(Event.KAKAO_PROFILE_PARSED, null, Map.of(
                "profilePresent", account.get("profile") instanceof Map<?, ?>,
                "imageNeedsAgreement", Flag.of(account.get("profile_image_needs_agreement")),
                "legacyProfileNeedsAgreement", Flag.of(account.get("profile_needs_agreement")),
                "defaultImage", Flag.of(profile.get("is_default_image")),
                "rawImagePresent", rawImage instanceof String image && !image.isBlank(),
                "rawImageScheme", imageScheme(rawImage), "parsedImagePresent", imageUrl != null,
                "outcome", outcome));
        return new Profile(id.toString(), nickname.substring(0, nickname.offsetByCodePoints(0, length)), imageUrl);
    }

    private <T> T call(Provider provider, Supplier<T> request) {
        long started = System.nanoTime();
        try {
            T result = request.get();
            FeatureLog.debug(Event.KAKAO_REQUEST_COMPLETED, null, Map.of("provider", provider,
                    "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "bodyPresent", result != null));
            return result;
        } catch (RestClientException failure) {
            FeatureLog.warn(Event.KAKAO_REQUEST_FAILED, null, Map.of("provider", provider,
                    "status", failure instanceof RestClientResponseException response ? response.getStatusCode().value() : 0,
                    "failureType", failure.getClass(),
                    "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            throw failure;
        }
    }

    private static ImageScheme imageScheme(Object raw) {
        if (!(raw instanceof String image) || image.isBlank()) return ImageScheme.NONE;
        String trimmed = image.strip();
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)) return ImageScheme.HTTPS;
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)) return ImageScheme.HTTP;
        return ImageScheme.OTHER;
    }

    private enum ImageScheme { NONE, HTTP, HTTPS, OTHER }
    private enum PhotoOutcome { AVAILABLE, ACCOUNT_MISSING, PROFILE_MISSING, DEFAULT_IMAGE, URL_MISSING, URL_REJECTED }

    /**
     * 카카오는 사진 주소를 http://로 줄 때가 있어서, https 페이지에서 쓸 수 있게 https://로 바꾼다.
     * 예: http://k.kakaocdn.net/a.jpg → https://k.kakaocdn.net/a.jpg, 그 밖의 형식은 null
     */
    static String secureImageUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.strip();
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return "https://" + trimmed.substring(8);
        }
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + trimmed.substring(7);
        }
        return null;
    }

    public record Profile(String id, String nickname, String profileImageUrl) {
        public Profile(String id, String nickname) {
            this(id, nickname, null);
        }
    }
}
