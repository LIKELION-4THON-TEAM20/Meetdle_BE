package com.likelion4thon.Meetdle.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(OutputCaptureExtension.class)
class KakaoClientDiagnosticsTests {
    private MockRestServiceServer server;
    private KakaoClient kakao;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        kakao = new KakaoClient("test-client-key", "test-secret", "https://example.com/callback", builder.build());
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"private-access-token\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void logsHttpPhotoConversionWithoutLoggingProfileOrCredentials(CapturedOutput output) {
        profile("""
                {"id":12345,"kakao_account":{"profile_image_needs_agreement":false,
                  "profile":{"nickname":"private-nickname","is_default_image":false,
                    "profile_image_url":"http://k.kakaocdn.net/private-photo.jpg"}}}
                """);
        assertThat(kakao.profile("private-authorization-code").profileImageUrl())
                .isEqualTo("https://k.kakaocdn.net/private-photo.jpg");
        server.verify();
        assertThat(output.getOut()).contains("event=kakao_profile_parsed", "imageNeedsAgreement=NO",
                        "defaultImage=NO", "rawImageScheme=HTTP", "parsedImagePresent=true", "outcome=AVAILABLE")
                .doesNotContain("private-photo.jpg", "private-nickname", "private-access-token",
                        "private-authorization-code", "test-secret", "12345");
    }

    @Test
    void explainsDefaultPhotoExclusion(CapturedOutput output) {
        profile("""
                {"id":12345,"kakao_account":{"profile":{"is_default_image":true,
                  "profile_image_url":"https://k.kakaocdn.net/private-default.jpg"}}}
                """);
        assertThat(kakao.profile("code").profileImageUrl()).isNull();
        server.verify();
        assertThat(output.getOut()).contains("defaultImage=YES", "rawImagePresent=true",
                "parsedImagePresent=false", "outcome=DEFAULT_IMAGE").doesNotContain("private-default.jpg");
    }

    @Test
    void distinguishesConsentRequiredFromUnknownConsent(CapturedOutput output) {
        profile("""
                {"id":12345,"kakao_account":{"profile_image_needs_agreement":true,"profile":{"nickname":"private-name"}}}
                """);
        assertThat(kakao.profile("code").profileImageUrl()).isNull();
        server.verify();
        assertThat(output.getOut()).contains("imageNeedsAgreement=YES", "defaultImage=UNKNOWN", "outcome=URL_MISSING");
    }

    @Test
    void explainsMissingAccountData(CapturedOutput output) {
        profile("{\"id\":12345}");
        assertThat(kakao.profile("code").profileImageUrl()).isNull();
        server.verify();
        assertThat(output.getOut()).contains("outcome=ACCOUNT_MISSING", "imageNeedsAgreement=UNKNOWN", "profilePresent=false");
    }

    @Test
    void recordsProviderFailureStageAndStatusWithoutProviderBody(CapturedOutput output) {
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("provider-body-secret private-access-token"));
        assertThatThrownBy(() -> kakao.profile("private-code")).isInstanceOf(RestClientResponseException.class);
        server.verify();
        assertThat(output.getOut()).contains("event=kakao_request_failed", "provider=KAKAO_PROFILE", "status=401")
                .doesNotContain("provider-body-secret", "private-access-token", "private-code");
    }

    private void profile(String body) {
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(header("Authorization", "Bearer private-access-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
