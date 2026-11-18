package com.likelion4thon.Meetdle.auth;

import com.likelion4thon.Meetdle.auth.kakao.KakaoClient;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.WebUtils;

@RestController
class AuthController {
    private static final String ATTEMPT = AuthController.class.getName() + ".attempt";
    private final SecureRandom random = new SecureRandom();
    private final KakaoClient kakao;
    private final JdbcTemplate jdbc;
    private final SecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrfTokens;
    private final String frontendOrigin;

    AuthController(KakaoClient kakao, JdbcTemplate jdbc, SecurityContextRepository contexts,
                   HttpSessionCsrfTokenRepository csrfTokens,
                   @Value("${meetdle.frontend.origin:http://localhost:5173}") String frontendOrigin) {
        this.kakao = kakao;
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping("/api/auth/kakao/start")
    ResponseEntity<?> start(@RequestParam(required = false) String inviteToken, HttpServletRequest request) {
        if (inviteToken != null && !inviteToken.matches("[A-Za-z0-9_-]{43}")) {
            return error(400, "INVALID_REQUEST", "Invalid invite token");
        }
        if (!kakao.configured()) {
            return error(503, "PROVIDER_UNAVAILABLE", "Kakao login is unavailable");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String path = inviteToken == null ? "/rooms" : "/invite/" + inviteToken;
        var session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            session.setAttribute(ATTEMPT, new Attempt(state, path, Instant.now().plusSeconds(300)));
        }
        return redirect(kakao.authorize(state));
    }

    @GetMapping("/api/auth/kakao/callback")
    ResponseEntity<?> callback(@RequestParam(required = false) String state,
                               @RequestParam(required = false) String code,
                               @RequestParam(required = false) String error,
                               HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null) {
            return invalidState();
        }
        Attempt attempt;
        synchronized (WebUtils.getSessionMutex(session)) {
            if (!(session.getAttribute(ATTEMPT) instanceof Attempt pending)
                    || !pending.state().equals(state) || !Instant.now().isBefore(pending.expiresAt())) {
                return invalidState();
            }
            attempt = pending;
            session.removeAttribute(ATTEMPT);
        }
        String destination = frontendOrigin + attempt.path();
        if ((code == null) == (error == null) || (code != null && code.isBlank())
                || (error != null && error.isBlank())) {
            return error(400, "INVALID_REQUEST", "Expected a code or a provider error");
        }
        if (error != null) {
            return redirect(URI.create(destination + "?loginError="
                    + ("access_denied".equals(error) ? "CANCELLED" : "PROVIDER_UNAVAILABLE")));
        }

        KakaoClient.Profile profile;
        try {
            profile = kakao.profile(code);
        } catch (RestClientException | IllegalStateException failure) {
            return redirect(URI.create(destination + "?loginError=PROVIDER_UNAVAILABLE"));
        }
        AuthenticatedUser user = account(profile);
        request.changeSessionId();
        csrfTokens.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return redirect(URI.create(destination));
    }

    private AuthenticatedUser account(KakaoClient.Profile profile) {
        String select = "SELECT user_id, nickname FROM app_user WHERE provider = 'KAKAO' AND provider_user_id = ?";
        var users = jdbc.query(select, (rs, row) -> new AuthenticatedUser(
                UUID.fromString(rs.getString("user_id")), rs.getString("nickname")), profile.id());
        if (!users.isEmpty()) {
            return users.get(0);
        }
        UUID userId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO app_user (user_id, provider, provider_user_id, nickname) VALUES (?, 'KAKAO', ?, ?)",
                    userId.toString(), profile.id(), profile.nickname());
        } catch (DuplicateKeyException conflict) {
            return jdbc.queryForObject(select, (rs, row) -> new AuthenticatedUser(
                    UUID.fromString(rs.getString("user_id")), rs.getString("nickname")), profile.id());
        }
        return new AuthenticatedUser(userId, profile.nickname());
    }

    @GetMapping("/api/me")
    ResponseEntity<Session> me(@AuthenticationPrincipal AuthenticatedUser user, CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(user, csrfToken.getToken()));
    }

    private static ResponseEntity<?> invalidState() {
        return error(400, "OAUTH_STATE_INVALID", "Invalid or expired OAuth state");
    }

    private static ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(302).location(location).cacheControl(CacheControl.noStore()).build();
    }

    private record Attempt(String state, String path, Instant expiresAt) {}
    private record Session(AuthenticatedUser user, String csrfToken) {}
}
