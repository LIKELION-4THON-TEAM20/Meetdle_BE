package com.likelion4thon.Meetdle.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

class KakaoAuthorizationTests {
    private final String callback = "https://api.meetdle.co.kr/api/auth/kakao/callback";
    private final KakaoClient kakao = new KakaoClient("test-key", "private-client-secret", callback);

    @Test
    void normalLoginKeepsItsExistingConsentFlow() {
        var query = UriComponentsBuilder.fromUri(kakao.authorize("normal-state")).build().getQueryParams();
        assertThat(query).doesNotContainKeys("scope", "prompt");
        assertThat(query.getFirst("state")).isEqualTo("normal-state");
    }

    @Test
    void onlyTheSeparatePhotoFlowRequestsPhotoConsent() {
        var uri = kakao.authorizePhotoConsent("photo-state");
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(uri.getPath()).isEqualTo("/oauth/authorize");
        assertThat(query.getFirst("scope")).isEqualTo("profile_image");
        assertThat(query.getFirst("state")).isEqualTo("photo-state");
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8)).isEqualTo(callback);
        assertThat(uri.toString()).doesNotContain("private-client-secret");
    }
}
