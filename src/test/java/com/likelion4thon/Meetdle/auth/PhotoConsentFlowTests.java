package com.likelion4thon.Meetdle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = "meetdle.kakao.rest-api-key=test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoConsentFlowTests {
    private static final String PHOTO = "/api/auth/kakao/photo-consent";
    private static final String CALLBACK = "/api/auth/kakao/callback";
    private static final String OLD_PHOTO = "https://example.com/old-photo.jpg";
    private static final String NEW_PHOTO = "https://example.com/new-photo.jpg";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KakaoClient kakao;

    @BeforeEach
    void setup() {
        when(kakao.configured()).thenReturn(true);
        when(kakao.authorize(anyString())).thenAnswer(invocation ->
                URI.create("https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0)));
        when(kakao.authorizePhotoConsent(anyString())).thenAnswer(invocation ->
                URI.create("https://kauth.kakao.com/oauth/authorize?scope=profile_image&state=" + invocation.getArgument(0)));
    }

    @Test
    void requiresAnExistingLogin() throws Exception {
        mvc.perform(get(PHOTO)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("Location"));
        verify(kakao, never()).authorizePhotoConsent(anyString());
    }

    @Test
    void updatesOnlyTheSameUsersPhotoAndReturnsToTheSelectedFrontend() throws Exception {
        var session = new MockHttpSession();
        String providerId = UUID.randomUUID().toString();
        String userId = login(session, providerId, null);
        String path = "/rooms/" + UUID.randomUUID();
        String state = startPhoto(session, path);
        String oldSessionId = session.getId();
        when(kakao.profile("consent-code")).thenReturn(new KakaoClient.Profile(providerId, "changed-provider-name", NEW_PHOTO));
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173" + path + "?photoConsent=UPDATED"));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        assertUser(session, userId, NEW_PHOTO);
        assertThat(storedPhoto(userId)).isEqualTo(NEW_PHOTO);
        assertThat(jdbc.queryForObject("SELECT nickname FROM app_user WHERE user_id = ?", String.class, userId))
                .isEqualTo("original-name");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE provider_user_id = ?", Integer.class, providerId))
                .isEqualTo(1);
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void cancellationKeepsLoginAndPhotoAndRestoresTheInvitePage() throws Exception {
        var session = new MockHttpSession();
        String userId = login(session, UUID.randomUUID().toString(), OLD_PHOTO);
        String oldSessionId = session.getId();
        String path = "/invite/" + "b".repeat(43);
        String state = startPhoto(session, path);
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173" + path + "?photoConsent=CANCELLED"));
        assertThat(session.getId()).isEqualTo(oldSessionId);
        assertUser(session, userId, OLD_PHOTO);
        assertThat(storedPhoto(userId)).isEqualTo(OLD_PHOTO);
        verify(kakao, never()).profile("consent-code");
    }

    @Test
    void missingPhotoDoesNotEraseTheExistingPhotoOrChangeNickname() throws Exception {
        var session = new MockHttpSession();
        String providerId = UUID.randomUUID().toString();
        String userId = login(session, providerId, OLD_PHOTO);
        String state = startPhoto(session, null);
        when(kakao.profile("consent-code")).thenReturn(new KakaoClient.Profile(providerId, "new-name", null));
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?photoConsent=NOT_PROVIDED"));
        assertUser(session, userId, OLD_PHOTO);
        assertThat(storedPhoto(userId)).isEqualTo(OLD_PHOTO);
    }

    @Test
    void anotherKakaoAccountCannotReplaceTheLoggedInAccount() throws Exception {
        var session = new MockHttpSession();
        String userId = login(session, UUID.randomUUID().toString(), OLD_PHOTO);
        String state = startPhoto(session, null);
        String otherProviderId = UUID.randomUUID().toString();
        when(kakao.profile("consent-code")).thenReturn(new KakaoClient.Profile(otherProviderId, "other-name", NEW_PHOTO));
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?photoConsent=ACCOUNT_MISMATCH"));
        assertUser(session, userId, OLD_PHOTO);
        assertThat(storedPhoto(userId)).isEqualTo(OLD_PHOTO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE provider_user_id = ?", Integer.class, otherProviderId))
                .isZero();
    }

    @Test
    void changingTheSessionUserDuringConsentRejectsTheOldAttempt() throws Exception {
        var session = new MockHttpSession();
        login(session, UUID.randomUUID().toString(), OLD_PHOTO);
        String state = startPhoto(session, null);
        String secondUserId = login(session, UUID.randomUUID().toString(), null);
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?photoConsent=SESSION_CHANGED"));
        assertUser(session, secondUserId, null);
        verify(kakao, never()).profile("consent-code");
    }

    @Test
    void providerFailureKeepsTheExistingLoginAndPhoto() throws Exception {
        var session = new MockHttpSession();
        String userId = login(session, UUID.randomUUID().toString(), OLD_PHOTO);
        String state = startPhoto(session, null);
        when(kakao.profile("consent-code")).thenThrow(new ResourceAccessException("provider unavailable"));
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?photoConsent=PROVIDER_UNAVAILABLE"));
        assertUser(session, userId, OLD_PHOTO);
        assertThat(storedPhoto(userId)).isEqualTo(OLD_PHOTO);
    }

    @Test
    void rechecksTheSessionOwnerAfterTheProviderRequest() throws Exception {
        var session = new MockHttpSession();
        String providerId = UUID.randomUUID().toString();
        String firstUserId = login(session, providerId, OLD_PHOTO);
        String state = startPhoto(session, null);
        var secondSession = new MockHttpSession();
        String secondUserId = login(secondSession, UUID.randomUUID().toString(), null);
        String contextKey = org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
        Object secondContext = secondSession.getAttribute(contextKey);
        when(kakao.profile("consent-code")).thenAnswer(invocation -> {
            session.setAttribute(contextKey, secondContext);
            return new KakaoClient.Profile(providerId, "changed-name", NEW_PHOTO);
        });
        mvc.perform(get(CALLBACK).session(session).param("state", state).param("code", "consent-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/rooms?photoConsent=SESSION_CHANGED"));
        assertUser(session, secondUserId, null);
        assertThat(storedPhoto(firstUserId)).isEqualTo(OLD_PHOTO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "//evil.example", "/rooms?next=evil", "/rooms/../login", "/invite/short"})
    void rejectsUntrustedReturnPaths(String path) throws Exception {
        var session = new MockHttpSession();
        login(session, UUID.randomUUID().toString(), null);
        mvc.perform(get(PHOTO).session(session).param("returnTo", path))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(kakao, never()).authorizePhotoConsent(anyString());
    }

    @Test
    void rejectsUntrustedReturnOrigins() throws Exception {
        var session = new MockHttpSession();
        login(session, UUID.randomUUID().toString(), null);
        mvc.perform(get(PHOTO).session(session).param("returnOrigin", "https://evil.example"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void reportsMissingKakaoConfigurationWithoutLoggingOut() throws Exception {
        var session = new MockHttpSession();
        String userId = login(session, UUID.randomUUID().toString(), OLD_PHOTO);
        when(kakao.configured()).thenReturn(false);
        mvc.perform(get(PHOTO).session(session)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        assertUser(session, userId, OLD_PHOTO);
    }

    private String login(MockHttpSession session, String providerId, String image) throws Exception {
        String code = "login-" + providerId;
        when(kakao.profile(code)).thenReturn(new KakaoClient.Profile(providerId, "original-name", image));
        String location = mvc.perform(get("/api/auth/kakao/start").session(session)).andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(CALLBACK).session(session).param("state", stateOf(location)).param("code", code))
                .andExpect(status().isFound());
        String body = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.user.userId");
    }

    private String startPhoto(MockHttpSession session, String path) throws Exception {
        var request = get(PHOTO).session(session).param("returnOrigin", "http://localhost:5173");
        if (path != null) request.param("returnTo", path);
        String location = mvc.perform(request).andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        return stateOf(location);
    }

    private static String stateOf(String location) {
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    }

    private void assertUser(MockHttpSession session, String userId, String image) throws Exception {
        String body = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(userId))
                .andExpect(jsonPath("$.user.nickname").value("original-name"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$.user")).containsEntry("profileImageUrl", image);
    }

    private String storedPhoto(String userId) {
        return jdbc.queryForObject("SELECT profile_image_url FROM app_user WHERE user_id = ?", String.class, userId);
    }
}
