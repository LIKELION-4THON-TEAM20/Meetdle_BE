package com.likelion4thon.Meetdle.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // A (room·planning): 명세상 공개 경로는 A06 링크 미리보기뿐이다.
                        .requestMatchers(HttpMethod.GET, "/api/invitations/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/*/state", "/api/stations")
                                .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/rooms", "/api/rooms/*/join",
                                "/api/invitations/resolve", "/api/rooms/*/planning-options").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/rooms/*/inputs/me",
                                "/api/rooms/*/meeting-selection").authenticated()
                        .anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }
}
