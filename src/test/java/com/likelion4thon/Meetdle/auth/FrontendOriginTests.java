package com.likelion4thon.Meetdle.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.auth.kakao.KakaoClient;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = {
        "meetdle.frontend.origin=https://meetdle.co.kr",
        "meetdle.frontend.additional-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrontendOriginTests {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    KakaoClient kakao;

    @BeforeEach
    void configureKakao() {
        when(kakao.configured()).thenReturn(true);
        when(kakao.authorize(anyString())).thenAnswer(invocation ->
                URI.create("https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://meetdle.co.kr", "http://localhost:5173"})
    void allowsCredentialedCorsFromBothFrontends(String origin) throws Exception {
        mvc.perform(options("/api/rooms")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN,Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void rejectsCorsFromOtherFrontends() throws Exception {
        mvc.perform(options("/api/rooms")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://meetdle.co.kr", "http://localhost:5173"})
    void returnsToChosenFrontendAfterLogin(String origin) throws Exception {
        var session = new MockHttpSession();
        String state = start(session, get("/api/auth/kakao/start").param("returnOrigin", origin));
        when(kakao.profile("code")).thenReturn(new KakaoClient.Profile(UUID.randomUUID().toString(), "Member"));

        mvc.perform(get("/api/auth/kakao/callback").session(session)
                        .param("state", state).param("code", "code")
                        .param("returnOrigin", "https://untrusted.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", origin + "/rooms"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void usesDomainWhenReturnOriginIsOmitted() throws Exception {
        var session = new MockHttpSession();
        String state = start(session, get("/api/auth/kakao/start"));
        mvc.perform(get("/api/auth/kakao/callback").session(session)
                        .param("state", state).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://meetdle.co.kr/rooms?loginError=CANCELLED"));
    }

    @Test
    void keepsLocalInviteOnCancellation() throws Exception {
        var session = new MockHttpSession();
        String invite = "a".repeat(43);
        String state = start(session, get("/api/auth/kakao/start")
                .param("returnOrigin", "http://localhost:5173").param("inviteToken", invite));
        mvc.perform(get("/api/auth/kakao/callback").session(session)
                        .param("state", state).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/invite/" + invite + "?loginError=CANCELLED"));
    }

    @Test
    void keepsLocalRoomOnProviderFailure() throws Exception {
        var session = new MockHttpSession();
        String path = "/rooms/" + UUID.randomUUID();
        String state = start(session, get("/api/auth/kakao/start")
                .param("returnOrigin", "http://localhost:5173").param("returnTo", path));
        when(kakao.profile("code")).thenThrow(new ResourceAccessException("provider unavailable"));
        mvc.perform(get("/api/auth/kakao/callback").session(session)
                        .param("state", state).param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173" + path + "?loginError=PROVIDER_UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://untrusted.example",
            "//untrusted.example",
            "http://localhost:5173/untrusted",
            "https://meetdle.co.kr@untrusted.example",
            ""
    })
    void rejectsUntrustedReturnOrigins(String origin) throws Exception {
        mvc.perform(get("/api/auth/kakao/start").param("returnOrigin", origin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String start(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        String location = mvc.perform(request.session(session)).andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    }
}
