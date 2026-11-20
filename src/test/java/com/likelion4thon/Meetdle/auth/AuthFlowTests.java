package com.likelion4thon.Meetdle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.auth.kakao.KakaoClient;
import java.net.URI;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = "meetdle.kakao.rest-api-key=test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KakaoClient kakao;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void updatesStoredPhotoOnLoginAndReturnsNullWhenKakaoHasNoPhoto(CapturedOutput output) throws Exception {
        var session = new MockHttpSession();
        String providerId = UUID.randomUUID().toString();
        for (String image : new String[] {"https://example.com/first.jpg", "https://example.com/updated.jpg", null}) {
            String state = start(session, null);
            when(kakao.profile("photo-code")).thenReturn(new KakaoClient.Profile(providerId, "사진 사용자", image));
            mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "photo-code"))
                    .andExpect(status().isFound());
            String body = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Map<String, Object>>read(body, "$.user"))
                    .containsEntry("nickname", "사진 사용자").containsEntry("profileImageUrl", image);
            assertThat(jdbc.queryForObject("SELECT profile_image_url FROM app_user WHERE provider = 'KAKAO' AND provider_user_id = ?",
                    String.class, providerId)).isEqualTo(image);
        }
        assertThat(output.getOut()).contains("event=auth_account_saved", "action=CREATED", "action=UPDATED",
                        "writtenRows=1", "event=auth_session_created", "event=auth_session_read", "photoPresent=true", "photoPresent=false")
                .doesNotContain("first.jpg", "updated.jpg", "사진 사용자", "photo-code", providerId);
    }

    @Test
    void keepsBothLoginAttemptsAndTheirReturnPathsInTheSameSession() throws Exception {
        var session = new MockHttpSession();
        String invite = "b".repeat(43);
        String first = start(session, invite);
        String second = start(session, null);
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", first).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/invite/" + invite + "?loginError=CANCELLED"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", second).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?loginError=CANCELLED"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", first).param("error", "access_denied"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void keepsOtherPendingAttemptsWhenSuccessfulLoginRotatesSessionId() throws Exception {
        var session = new MockHttpSession();
        String oldId = session.getId();
        String first = start(session, null);
        String second = start(session, null);
        when(kakao.profile("rotation-code")).thenReturn(new KakaoClient.Profile(UUID.randomUUID().toString(), "사용자"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", first).param("code", "rotation-code"))
                .andExpect(status().isFound());
        assertNotEquals(oldId, session.getId());
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", second).param("error", "access_denied"))
                .andExpect(status().isFound());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reportsStateFailureReasonsWithoutLoggingCredentials(CapturedOutput output) throws Exception {
        var session = new MockHttpSession(null, "secret-session-cookie-value");
        String state = start(session, null, "Mozilla/5.0 iPhone KAKAOTALK/10.8.2 (INAPP)");
        String wrongState = "secret-wrong-state";
        String code = "secret-authorization-code";
        mvc.perform(get("/api/auth/kakao/callback").param("state", state).param("code", code))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", wrongState).param("code", code))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("error", "access_denied"))
                .andExpect(status().isFound());
        assertThat(output.getOut()).contains("outcome=NO_SESSION", "outcome=STATE_NOT_FOUND", "outcome=ACCEPTED")
                .doesNotContain(state, wrongState, code, session.getId());
        var reference = java.util.regex.Pattern.compile("oauth_state_started.*stateRef=([0-9a-f]{32}) kakaoInApp=true")
                .matcher(output.getOut());
        assertThat(reference.find()).isTrue();
        assertThat(output.getOut()).contains("stateRef=" + reference.group(1) + " kakaoInApp=false outcome=NO_SESSION");
    }

    @Test
    void exposesHealthWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void correlatesRejectedRequestsWithoutLoggingInvitationTokens(CapturedOutput output) throws Exception {
        String token = "z".repeat(43);
        String suppliedRequestId = "untrusted-request-id";
        String requestId = mvc.perform(get("/api/invitations/{token}", token)
                        .header("X-Request-Id", suppliedRequestId).param("q", "sensitive-query"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(output.getOut())
                .contains("api_request requestId=" + requestId + " method=GET route=/api/invitations/{token}"
                        + " roomId=- status=404 code=INVITATION_NOT_FOUND")
                .doesNotContain(token, suppliedRequestId, "sensitive-query");
    }

    @Test
    void logsInThroughInviteAndRequiresCsrfForLogout() throws Exception {
        var session = new MockHttpSession();
        String oldSessionId = session.getId();
        String invite = "A".repeat(43);
        String state = start(session, invite);
        when(kakao.profile("code")).thenReturn(new KakaoClient.Profile(UUID.randomUUID().toString(), "Meetdle",
                "https://example.com/avatar.jpg"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/invite/" + invite));
        assertNotEquals(oldSessionId, session.getId());

        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
        String body = mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.nickname").value("Meetdle"))
                .andExpect(jsonPath("$.user.profileImageUrl").value("https://example.com/avatar.jpg"))
                .andReturn().getResponse().getContentAsString();
        String csrf = JsonPath.read(body, "$.csrfToken");
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("Set-Cookie"));
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void preservesReturnPathOnCancelAndProviderFailure() throws Exception {
        var session = new MockHttpSession();
        String state = start(session, null);
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", "wrong").param("code", "code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?loginError=CANCELLED"));

        String nextState = start(session, null);
        when(kakao.profile("code")).thenThrow(new ResourceAccessException("provider unavailable"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", nextState).param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?loginError=PROVIDER_UNAVAILABLE"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void memberShareRestoresRoomAfterLoginAndRejectsExternalReturnPaths() throws Exception {
        var session = new MockHttpSession();
        start(session, null);
        String path = "/rooms/" + UUID.randomUUID();
        String location = mvc.perform(get("/api/auth/kakao/start").session(session).param("returnTo", path))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        when(kakao.profile("room-code")).thenReturn(new KakaoClient.Profile(UUID.randomUUID().toString(), "Member"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "room-code"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "http://localhost:5173" + path));
        mvc.perform(get("/api/auth/kakao/start").param("returnTo", "https://example.com"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/kakao/start").param("returnTo", path).param("inviteToken", "a".repeat(43)))
                .andExpect(status().isBadRequest());
    }

    private String start(MockHttpSession session, String invite) throws Exception {
        return start(session, invite, "test-browser");
    }

    private String start(MockHttpSession session, String invite, String userAgent) throws Exception {
        when(kakao.configured()).thenReturn(true);
        when(kakao.authorize(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                URI.create("https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0)));
        var request = get("/api/auth/kakao/start").session(session).header("User-Agent", userAgent);
        if (invite != null) {
            request.param("inviteToken", invite);
        }
        String location = mvc.perform(request).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    }
}
