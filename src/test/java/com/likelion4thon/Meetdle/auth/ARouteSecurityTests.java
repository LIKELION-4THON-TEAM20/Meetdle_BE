package com.likelion4thon.Meetdle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진짜 보안 설정(SecurityConfiguration)으로 A 경로가 열려 있는지 확인한다.
 * 다른 A 테스트는 RoomApiTestConfig의 테스트용 보안 설정을 쓰기 때문에, 운영 설정이 막고 있어도 몰랐다.
 * 여기서는 테스트용 설정을 끼우지 않는다.
 *
 * 확인 방법: 보안에 막히면 401/403이 나온다. 그 밖의 응답(200, 404, 409 등)이면 컨트롤러까지 들어간 것.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class ARouteSecurityTests {

    private static final String ROOM = BoundaryFixtures.ROOM_ID.toString();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void linkPreviewIsPublic() throws Exception {
        mockMvc.perform(get("/api/invitations/" + "A".repeat(43)))
                .andExpect(reachedController())
                .andExpect(status().isNotFound());
    }

    @Test
    void loggedInMemberReachesEveryARoute() throws Exception {
        mockMvc.perform(asHost(get("/api/stations").param("q", "강남"))).andExpect(status().isOk());
        mockMvc.perform(asHost(get("/api/rooms"))).andExpect(status().isOk());
        mockMvc.perform(asHost(get("/api/rooms/" + ROOM + "/state"))).andExpect(status().isOk());

        mockMvc.perform(asHost(post("/api/rooms"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("""
                                {"dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":4}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(asHost(post("/api/rooms/" + ROOM + "/join")).content("{\"joinProof\":\"nope\"}"))
                .andExpect(reachedController());
        mockMvc.perform(asHost(post("/api/invitations/resolve")).content("{\"code\":\"ZZZZZZ\"}"))
                .andExpect(reachedController());
        mockMvc.perform(asHost(put("/api/rooms/" + ROOM + "/inputs/me")).content("""
                        {"originStationId":null,"destinationStationId":null,"slots":[],
                         "inputRevision":0,"complete":false}
                        """))
                .andExpect(reachedController());
        mockMvc.perform(asHost(post("/api/rooms/" + ROOM + "/planning-options"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"inputVersion\":0}"))
                .andExpect(reachedController());
        mockMvc.perform(asHost(put("/api/rooms/" + ROOM + "/meeting-selection")).content("""
                        {"optionsId":"%s","slotId":"%s","candidateId":"%s"}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(reachedController());
    }

    @Test
    void unknownRoutesStayBlocked() throws Exception {
        mockMvc.perform(asHost(get("/api/not-a-route"))).andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder asHost(MockHttpServletRequestBuilder request) {
        return request.with(loggedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    private static RequestPostProcessor loggedIn() {
        return authentication(new TestingAuthenticationToken(BoundaryFixtures.HOST, null, "ROLE_USER"));
    }

    private static ResultMatcher reachedController() {
        return result -> assertThat(result.getResponse().getStatus())
                .as("보안 설정에 막히면 401/403")
                .isNotIn(401, 403);
    }
}
