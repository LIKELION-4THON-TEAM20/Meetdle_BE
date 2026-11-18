package com.likelion4thon.Meetdle.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.auth.kakao.KakaoClient;
import com.likelion4thon.Meetdle.common.logging.RequestOperationLog;
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

@Tag(name = "인증")
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

    @Operation(summary = "카카오 로그인 시작",
            description = "브라우저에서 호출해 카카오 로그인 화면으로 이동. 초대 링크에서 진입한 경우 inviteToken 전달",
            responses = {
                @ApiResponse(responseCode = "302", description = "카카오 인증 화면으로 이동", content = @Content)
            })
    @GetMapping("/api/auth/kakao/start")
    ResponseEntity<?> start(
            @Parameter(description = "로그인 후 초대 화면으로 돌아갈 때 사용할 URL 경로의 초대 토큰")
            @RequestParam(required = false) String inviteToken, HttpServletRequest request) {
        if (inviteToken != null && !inviteToken.matches("[A-Za-z0-9_-]{43}")) {
            return error(request, 400, "INVALID_REQUEST", "Invalid invite token");
        }
        if (!kakao.configured()) {
            return error(request, 503, "PROVIDER_UNAVAILABLE", "Kakao login is unavailable");
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

    @Operation(summary = "카카오 로그인 콜백",
            description = "카카오가 전달한 state와 code로 로그인 세션 생성. 성공 또는 취소 결과에 따라 프런트 화면으로 이동",
            responses = {
                @ApiResponse(responseCode = "302", description = "프런트 화면으로 이동", content = @Content)
            })
    @GetMapping("/api/auth/kakao/callback")
    ResponseEntity<?> callback(
            @Parameter(description = "카카오 로그인 요청에서 발급한 세션 확인용 일회성 값")
            @RequestParam(required = false) String state,
                               @Parameter(description = "카카오 로그인 성공 시 전달되는 일회성 인가 코드")
            @RequestParam(required = false) String code,
                               @Parameter(description = "카카오 로그인 취소 또는 실패 시 전달되는 오류 코드", example = "access_denied")
            @RequestParam(required = false) String error,
                               HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null) {
            return invalidState(request);
        }
        Attempt attempt;
        synchronized (WebUtils.getSessionMutex(session)) {
            if (!(session.getAttribute(ATTEMPT) instanceof Attempt pending)
                    || !pending.state().equals(state) || !Instant.now().isBefore(pending.expiresAt())) {
                return invalidState(request);
            }
            attempt = pending;
            session.removeAttribute(ATTEMPT);
        }
        String destination = frontendOrigin + attempt.path();
        if ((code == null) == (error == null) || (code != null && code.isBlank())
                || (error != null && error.isBlank())) {
            return error(request, 400, "INVALID_REQUEST", "Expected a code or a provider error");
        }
        if (error != null) {
            String result = "access_denied".equals(error) ? "CANCELLED" : "PROVIDER_UNAVAILABLE";
            RequestOperationLog.setResultCode(request, result);
            return redirect(URI.create(destination + "?loginError=" + result));
        }

        KakaoClient.Profile profile;
        try {
            profile = kakao.profile(code);
        } catch (RestClientException | IllegalStateException failure) {
            RequestOperationLog.setResultCode(request, "PROVIDER_UNAVAILABLE");
            return redirect(URI.create(destination + "?loginError=PROVIDER_UNAVAILABLE"));
        }
        AuthenticatedUser user = account(profile);
        request.changeSessionId();
        csrfTokens.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        RequestOperationLog.setResultCode(request, "AUTHENTICATED");
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

    @Operation(summary = "내 로그인 세션 조회",
            description = "로그인한 사용자 정보와 쓰기 요청의 X-CSRF-TOKEN 헤더에 사용할 csrfToken 조회",
            responses = {
                @ApiResponse(responseCode = "200", description = "로그인 사용자 정보 및 CSRF 토큰 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/api/me")
    ResponseEntity<Session> me(@AuthenticationPrincipal AuthenticatedUser user,
                               @Parameter(hidden = true) CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(user, csrfToken.getToken()));
    }

    private static ResponseEntity<?> invalidState(HttpServletRequest request) {
        return error(request, 400, "OAUTH_STATE_INVALID", "Invalid or expired OAuth state");
    }

    private static ResponseEntity<?> error(HttpServletRequest request, int status, String code, String message) {
        RequestOperationLog.setResultCode(request, code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(302).location(location).cacheControl(CacheControl.noStore()).build();
    }

    private record Attempt(String state, String path, Instant expiresAt) {}
    private record Session(
            @Schema(description = "현재 세션으로 로그인한 사용자 정보") AuthenticatedUser user,
            @Schema(description = "같은 세션의 쓰기 요청에서 X-CSRF-TOKEN 헤더로 보낼 값") String csrfToken
    ) {}
}
