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
 * 2) 보안: 창환님 SecurityConfiguration은 아직 모든 요청을 막는다(denyAll).
 *    테스트에서만 A06은 누구나, 나머지 /api/** 는 로그인하면 통과하도록 앞에 끼운다. 운영 코드는 건드리지 않는다.
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
