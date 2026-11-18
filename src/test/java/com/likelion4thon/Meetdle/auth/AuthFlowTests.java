package com.likelion4thon.Meetdle.auth;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = "meetdle.kakao.rest-api-key=test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTests {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    KakaoClient kakao;

    @Test
    void exposesHealthWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void logsInThroughInviteAndRequiresCsrfForLogout() throws Exception {
        var session = new MockHttpSession();
        String oldSessionId = session.getId();
        String invite = "A".repeat(43);
        String state = start(session, invite);
        when(kakao.profile("code")).thenReturn(new KakaoClient.Profile(UUID.randomUUID().toString(), "Meetdle"));
        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/invite/" + invite));
        assertNotEquals(oldSessionId, session.getId());

        mvc.perform(get("/api/auth/kakao/callback").session(session).param("state", state).param("code", "code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
        String body = mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.nickname").value("Meetdle"))
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

    private String start(MockHttpSession session, String invite) throws Exception {
        when(kakao.configured()).thenReturn(true);
        when(kakao.authorize(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                URI.create("https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0)));
        var request = get("/api/auth/kakao/start").session(session);
        if (invite != null) {
            request.param("inviteToken", invite);
        }
        String location = mvc.perform(request).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    }
}
