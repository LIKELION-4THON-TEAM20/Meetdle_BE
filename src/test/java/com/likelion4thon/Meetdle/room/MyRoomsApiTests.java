package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * A24 GET /api/rooms 테스트.
 * 지금 = 2030-01-01T00:00Z (서울 2030-01-01 09:00). 방장(00..01)이 속한 방:
 *   다가오는 방: FIXTURE(1/1~1/3), UP_A(1/10~1/12), UP_B(1/10~1/11)  → 시작일 빠른 순, 같으면 UUID 순
 *   지난 방:     ENDED(1/5~1/6, 종료됨), PAST_2(12/25~12/26), PAST_1(12/20~12/22) → 끝난 날 최근 순
 *   남의 방:     OTHER (방장이 회원 아님 → 안 보여야 함)
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class MyRoomsApiTests {

    private static final String FIXTURE = BoundaryFixtures.ROOM_ID.toString();
    private static final String UP_A = "a0000000-0000-4000-8000-000000000001";
    private static final String UP_B = "b0000000-0000-4000-8000-000000000001";
    private static final String ENDED = "e0000000-0000-4000-8000-000000000001";
    private static final String PAST_1 = "c0000000-0000-4000-8000-000000000001";
    private static final String PAST_2 = "d0000000-0000-4000-8000-000000000001";
    private static final String OTHER = "f0000000-0000-4000-8000-000000000001";
    private static final AuthenticatedUser PARTICIPANT = new AuthenticatedUser(
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"), "참가자");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.set(BoundaryFixtures.NOW);
        room(UP_A, "2030-01-10", "2030-01-12", "WAITING_FOR_DEPARTURE", true);
        room(UP_B, "2030-01-10", "2030-01-11", "WAITING_FOR_DEPARTURE", true);
        room(ENDED, "2030-01-05", "2030-01-06", "ENDED", true);
        room(PAST_1, "2029-12-20", "2029-12-22", "WAITING_FOR_DEPARTURE", true);
        room(PAST_2, "2029-12-25", "2029-12-26", "OPTIONS_READY", true);
        room(OTHER, "2030-01-10", "2030-01-12", "WAITING_FOR_DEPARTURE", false);
    }

    @Test
    void summariesContainOnlyTheirRoomsCurrentParticipantProfiles() throws Exception {
        jdbc.update("UPDATE app_user SET nickname = ?, profile_image_url = ? WHERE user_id = ?",
                "현재 방장 이름", "https://example.com/host.jpg", BoundaryFixtures.HOST.userId().toString());
        jdbc.update("UPDATE app_user SET profile_image_url = ? WHERE user_id = ?",
                "https://example.com/member.jpg", PARTICIPANT.userId().toString());
        UUID extraParticipant = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO room_member (participant_id, room_id, user_id, nickname, role, joined_at)
                VALUES (?, ?, ?, '참가자', 'MEMBER', '2030-01-01 00:00:01')
                """, extraParticipant.toString(), UP_A, PARTICIPANT.userId().toString());
        String body = list("limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].members.length()").value(3))
                .andExpect(jsonPath("$.items[0].members[0].participantId").value(RoomStateApiTests.HOST_PID))
                .andExpect(jsonPath("$.items[0].members[0].nickname").value("현재 방장 이름"))
                .andExpect(jsonPath("$.items[0].members[0].profileImageUrl").value("https://example.com/host.jpg"))
                .andExpect(jsonPath("$.items[0].members[1].profileImageUrl").value("https://example.com/member.jpg"))
                .andExpect(jsonPath("$.items[1].members.length()").value(2))
                .andExpect(jsonPath("$.items[2].members.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        var members = JsonPath.<List<Map<String, Object>>>read(body, "$.items[0].members");
        assertThat(members).allSatisfy(member -> assertThat(member.keySet())
                .isEqualTo(Set.of("participantId", "nickname", "profileImageUrl")));
        assertThat(members.get(2)).containsEntry("profileImageUrl", null);
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.items[1].members"))
                .anySatisfy(member -> assertThat(member).containsEntry("participantId", extraParticipant.toString()));
    }

    @Test
    void userWithoutRoomsGetsAnEmptyList() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, provider, provider_user_id, nickname) VALUES (?, 'KAKAO', ?, '새 사용자')",
                userId.toString(), userId.toString());
        list("", new AuthenticatedUser(userId, "새 사용자"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void upcomingRoomsComeSoonestFirst() throws Exception {
        list("scope=UPCOMING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].roomId", contains(FIXTURE, UP_A, UP_B)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void pastRoomsComeMostRecentFirst() throws Exception {
        list("scope=PAST")
                .andExpect(jsonPath("$.items[*].roomId", contains(ENDED, PAST_2, PAST_1)));
    }

    @Test
    void allShowsUpcomingThenPastAndSummaryFields() throws Exception {
        list("")
                .andExpect(jsonPath("$.items[*].roomId", contains(FIXTURE, UP_A, UP_B, ENDED, PAST_2, PAST_1)))
                .andExpect(jsonPath("$.items[0].title").value("테스트 약속"))
                .andExpect(jsonPath("$.items[0].dates.from").value("2030-01-01"))
                .andExpect(jsonPath("$.items[0].dates.through").value("2030-01-03"))
                .andExpect(jsonPath("$.items[0].capacity").value(4))
                .andExpect(jsonPath("$.items[0].joinedCount").value(3))
                .andExpect(jsonPath("$.items[0].phase").value("WAITING_FOR_DEPARTURE"))
                .andExpect(jsonPath("$.items[0].meetingAt").isEmpty());
    }

    @Test
    void pagesThroughWithCursorWithoutGapsOrRepeats() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String body = list("limit=4" + (cursor == null ? "" : "&cursor=" + cursor))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].roomId"));
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(2); // 4개 + 2개
        assertThat(seen).containsExactly(FIXTURE, UP_A, UP_B, ENDED, PAST_2, PAST_1);
    }

    @Test
    void onlyShowsRoomsIAmIn() throws Exception {
        list("", PARTICIPANT)
                .andExpect(jsonPath("$.items[*].roomId", contains(FIXTURE)));
    }

    @Test
    void rejectsCursorFromAnotherScopeOrUserOrGarbage() throws Exception {
        String body = list("scope=ALL&limit=1").andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");

        list("scope=PAST&cursor=" + cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        list("scope=ALL&cursor=" + cursor, PARTICIPANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        list("cursor=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void rejectsBadScopeAndLimit() throws Exception {
        list("scope=TOMORROW").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.scope").exists());
        list("limit=0").andExpect(status().isBadRequest());
        list("limit=51").andExpect(status().isBadRequest());
        list("limit=abc").andExpect(status().isBadRequest());
        list("limit=50").andExpect(status().isOk());
    }

    @Test
    void roomMovesToPastAfterItsCandidatePeriod() throws Exception {
        clock.set(java.time.Instant.parse("2030-01-03T15:00:00Z")); // 서울 1/4 00:00 → FIXTURE(~1/3) 기간 끝
        list("scope=PAST")
                .andExpect(jsonPath("$.items[*].roomId", contains(ENDED, FIXTURE, PAST_2, PAST_1)));
    }

    // ───────────── 도우미 ─────────────

    private void room(String roomId, String from, String through, String phase, boolean hostIsMember) {
        jdbc.update("INSERT INTO room (room_id, title, starts_on, ends_on, capacity, phase) VALUES (?, ?, ?, ?, 4, ?)",
                roomId, "방 " + roomId.charAt(0), from, through, phase);
        String userId = hostIsMember ? BoundaryFixtures.HOST.userId().toString()
                : "00000000-0000-0000-0000-000000000003";
        jdbc.update("INSERT INTO room_member (participant_id, room_id, user_id, nickname, role) VALUES (?, ?, ?, '방장', 'HOST')",
                java.util.UUID.randomUUID().toString(), roomId, userId);
    }

    private ResultActions list(String query) throws Exception {
        return list(query, BoundaryFixtures.HOST);
    }

    private ResultActions list(String query, AuthenticatedUser user) throws Exception {
        return mockMvc.perform(get("/api/rooms" + (query.isEmpty() ? "" : "?" + query))
                .with(authentication(new TestingAuthenticationToken(user, null, "ROLE_USER"))));
    }
}
