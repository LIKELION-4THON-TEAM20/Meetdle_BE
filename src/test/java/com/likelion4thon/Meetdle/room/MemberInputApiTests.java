package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * A10 PUT /api/rooms/{roomId}/inputs/me 테스트.
 * base-room.sql의 방(2030-01-01 ~ 01-03, 정원 4, 방장+참가자 2명)과 stations.sql의 역 4개를 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = {"/fixtures/base-room.sql", "/fixtures/stations.sql"}, config = @SqlConfig(encoding = "UTF-8"))
class MemberInputApiTests {

    private static final String ROOM = BoundaryFixtures.ROOM_ID.toString();
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "참가자");
    private static final String GANGNAM = "31000000-0000-4000-8000-000000000001";
    private static final String HONGDAE = "31000000-0000-4000-8000-000000000002";
    private static final String PANGYO_UNSUPPORTED = "31000000-0000-4000-8000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(BoundaryFixtures.NOW);
    }

    @Test
    void savesPartiallyThenCompletes() throws Exception {
        // 1) 출발역만 + 시간 2칸 (순서 뒤죽박죽으로 보내도 정렬돼서 저장)
        save(MEMBER, """
                {"originStationId":"%s","destinationStationId":null,
                 "slots":[{"date":"2030-01-02","hour":20},{"date":"2030-01-02","hour":19}],
                 "inputRevision":0,"complete":false}
                """.formatted(GANGNAM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originStation.name").value("강남"))
                .andExpect(jsonPath("$.originStation.lines", contains("2", "신분당")))
                .andExpect(jsonPath("$.destinationStation").isEmpty())
                .andExpect(jsonPath("$.slots[0].hour").value(19))
                .andExpect(jsonPath("$.slots[1].hour").value(20))
                .andExpect(jsonPath("$.inputRevision").value(1))
                .andExpect(jsonPath("$.completedAt").isEmpty());
        assertThat(roomInputVersion()).isEqualTo(1);

        // 2) 목적지까지 넣고 완료
        save(MEMBER, """
                {"originStationId":"%s","destinationStationId":"%s",
                 "slots":[{"date":"2030-01-02","hour":19},{"date":"2030-01-02","hour":20}],
                 "inputRevision":1,"complete":true}
                """.formatted(GANGNAM, HONGDAE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationStation.name").value("홍대입구"))
                .andExpect(jsonPath("$.inputRevision").value(2))
                .andExpect(jsonPath("$.completedAt").value("2030-01-01T00:00:00Z"));
        assertThat(roomInputVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_availability WHERE participant_id = ?", Integer.class,
                BoundaryFixtures.MEMBER_PARTICIPANT.toString())).isEqualTo(2);
    }

    @Test
    void cannotChangeAfterCompleting() throws Exception {
        save(MEMBER, complete(GANGNAM, HONGDAE, 0)).andExpect(status().isOk());

        save(MEMBER, complete(HONGDAE, GANGNAM, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INPUT_LOCKED"));
    }

    @Test
    void allowsSameOriginAndDestinationForRoundTrip() throws Exception {
        save(MEMBER, complete(GANGNAM, GANGNAM, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originStation.name").value("강남"))
                .andExpect(jsonPath("$.destinationStation.name").value("강남"));
    }

    @Test
    void rejectsStaleRevision() throws Exception {
        save(MEMBER, draft(GANGNAM, 0)).andExpect(status().isOk());

        save(MEMBER, draft(HONGDAE, 0)) // 아직 0을 들고 있음
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_INPUT"))
                .andExpect(jsonPath("$.details.currentInputRevision").value(1));
    }

    @Test
    void unchangedSaveKeepsVersions() throws Exception {
        save(MEMBER, draft(GANGNAM, 0)).andExpect(status().isOk());
        save(MEMBER, draft(GANGNAM, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputRevision").value(1));
        assertThat(roomInputVersion()).isEqualTo(1);
    }

    @Test
    void completingNeedsBothStationsAndAtLeastOneSlot() throws Exception {
        save(MEMBER, """
                {"originStationId":"%s","destinationStationId":null,"slots":[],"inputRevision":0,"complete":true}
                """.formatted(GANGNAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INPUT_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing", contains("destinationStationId", "slots")));
        assertThat(roomInputVersion()).isZero();
    }

    @Test
    void rejectsUnsupportedOrUnknownStations() throws Exception {
        save(MEMBER, draft(PANGYO_UNSUPPORTED, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STATION"))
                .andExpect(jsonPath("$.details.originStationId").value(PANGYO_UNSUPPORTED));
        save(MEMBER, draft(UUID.randomUUID().toString(), 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STATION"));
    }

    @Test
    void rejectsSlotsOutsideHoursOrDatesOrDuplicated() throws Exception {
        String[] badSlots = {
                "{\"date\":\"2030-01-02\",\"hour\":9}",                                   // 10시 전
                "{\"date\":\"2030-01-02\",\"hour\":24}",                                  // 23시 넘음
                "{\"date\":\"2030-01-04\",\"hour\":19}",                                  // 방 기간(1/1~1/3) 밖
                "{\"date\":\"2030-01-02\",\"hour\":19},{\"date\":\"2030-01-02\",\"hour\":19}" // 중복
        };
        for (String slots : badSlots) {
            save(MEMBER, "{\"originStationId\":null,\"destinationStationId\":null,\"slots\":[" + slots
                    + "],\"inputRevision\":0,\"complete\":false}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SLOT"));
        }
        assertThat(roomInputVersion()).isZero();
    }

    @Test
    void roomWideLockAfterFirstConfirmation() throws Exception {
        jdbc.update("UPDATE room SET inputs_closed_at = CURRENT_TIMESTAMP(6) WHERE room_id = ?", ROOM);

        save(MEMBER, draft(GANGNAM, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INPUT_LOCKED"));
    }

    @Test
    void onlyMembersOfExistingRoomsCanSave() throws Exception {
        UUID strangerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, provider, provider_user_id, nickname) VALUES (?, 'KAKAO', ?, '남')",
                strangerId.toString(), strangerId.toString());
        AuthenticatedUser stranger = new AuthenticatedUser(strangerId, "남");

        save(stranger, draft(GANGNAM, 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(put("/api/rooms/" + UUID.randomUUID() + "/inputs/me")
                        .with(loggedIn(MEMBER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(draft(GANGNAM, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ───────────── 도우미 ─────────────

    private ResultActions save(AuthenticatedUser user, String json) throws Exception {
        return mockMvc.perform(put("/api/rooms/" + ROOM + "/inputs/me")
                .with(loggedIn(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String draft(String originStationId, long revision) {
        return """
                {"originStationId":"%s","destinationStationId":null,
                 "slots":[{"date":"2030-01-02","hour":19}],"inputRevision":%d,"complete":false}
                """.formatted(originStationId, revision);
    }

    private static String complete(String origin, String destination, long revision) {
        return """
                {"originStationId":"%s","destinationStationId":"%s",
                 "slots":[{"date":"2030-01-02","hour":19}],"inputRevision":%d,"complete":true}
                """.formatted(origin, destination, revision);
    }

    private long roomInputVersion() {
        return jdbc.queryForObject("SELECT input_version FROM room WHERE room_id = ?", Long.class, ROOM);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor loggedIn(AuthenticatedUser user) {
        return authentication(new TestingAuthenticationToken(user, null, "ROLE_USER"));
    }
}
