package com.likelion4thon.Meetdle.auth;

import com.likelion4thon.Meetdle.common.logging.RequestOperationLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.HttpSessionMutexListener;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionMutexListener> sessionMutexListener() {
        return new ServletListenerRegistrationBean<>(new HttpSessionMutexListener());
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${meetdle.frontend.origin:http://localhost:5173}") String origin) {
        URI uri = URI.create(origin);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("meetdle.frontend.origin must be an HTTP(S) origin");
        }
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Location", RequestOperationLog.REQUEST_ID_HEADER));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
                                            HttpSessionCsrfTokenRepository csrf,
                                            @Value("${server.servlet.session.cookie.name:MEETDLE_SESSION}") String cookieName)
            throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .securityContext(config -> config.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/auth/kakao/start", "/api/auth/kakao/callback").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/invitations/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/me", "/api/rooms", "/api/rooms/*/state",
                                "/api/stations").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/rooms", "/api/rooms/*/join",
                                "/api/invitations/resolve", "/api/rooms/*/planning-options").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/rooms/*/inputs/me",
                                "/api/rooms/*/meeting-selection").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/rooms/*/places/search",
                                "/api/rooms/*/place-submission/categories").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/rooms/*/place-submission",
                                "/api/rooms/*/place-submission/extension", "/api/rooms/*/voting",
                                "/api/rooms/*/voting/decision", "/api/rooms/*/recovery").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/rooms/*/place-submission/me/*",
                                "/api/rooms/*/place-submission/closure", "/api/rooms/*/preferences/me",
                                "/api/rooms/*/vote-submissions/me", "/api/rooms/*/result/selections",
                                "/api/rooms/*/result/visit-order").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> unauthenticated(request, response))
                        .accessDeniedHandler((request, response, failure) -> {
                            if (isAuthenticated()) {
                                writeError(request, response, 403, "FORBIDDEN", "Access denied");
                            } else {
                                unauthenticated(request, response);
                            }
                        }))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutRequestMatcher(request -> isAuthenticated()
                                && "POST".equals(request.getMethod())
                                && (request.getContextPath() + "/api/auth/logout").equals(request.getRequestURI()))
                        .deleteCookies(cookieName)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    private static boolean isAuthenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeError(request, response, 401, "UNAUTHENTICATED", "Authentication required");
    }

    private static void writeError(HttpServletRequest request, HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        RequestOperationLog.setResultCode(request, code);
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
