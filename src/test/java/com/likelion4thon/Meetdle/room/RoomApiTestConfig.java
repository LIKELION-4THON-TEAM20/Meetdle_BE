package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * room API 테스트 공통 설정.
 * 1) 시계: 2030-01-01T00:00Z에서 시작하는 MutableClock (각 테스트 시작 때 다시 맞춘다)
 * 2) 보안: A06은 누구나, 나머지 /api/** 는 로그인하면 통과하는 단순한 테스트용 보안 설정을 앞에 끼운다.
 *    (로그인 안 한 요청이 401로 오는지 같은 것을 일정하게 확인하려고)
 *    운영 보안 설정이 A 경로를 실제로 열어 두었는지는 auth/ARouteSecurityTests가 따로 확인한다.
 */
@TestConfiguration
public class RoomApiTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(BoundaryFixtures.NOW);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain roomApiTestChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/invitations/*").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
