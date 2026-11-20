package com.likelion4thon.Meetdle.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KakaoClientImageUrlTests {

    @Test
    void httpImageUrlIsUpgradedToHttps() {
        assertThat(KakaoClient.secureImageUrl("http://k.kakaocdn.net/dn/a/img.jpg"))
                .isEqualTo("https://k.kakaocdn.net/dn/a/img.jpg");
    }

    @Test
    void httpsImageUrlIsKept() {
        assertThat(KakaoClient.secureImageUrl("https://k.kakaocdn.net/dn/a/img.jpg"))
                .isEqualTo("https://k.kakaocdn.net/dn/a/img.jpg");
    }

    @Test
    void otherValuesBecomeNull() {
        assertThat(KakaoClient.secureImageUrl(null)).isNull();
        assertThat(KakaoClient.secureImageUrl("")).isNull();
        assertThat(KakaoClient.secureImageUrl("ftp://k.kakaocdn.net/a.jpg")).isNull();
        assertThat(KakaoClient.secureImageUrl("javascript:alert(1)")).isNull();
    }
}
