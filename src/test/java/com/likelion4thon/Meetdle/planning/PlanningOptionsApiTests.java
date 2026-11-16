package com.likelion4thon.Meetdle.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.planning.subway.SubwayData;
import com.likelion4thon.Meetdle.room.MutableClock;
import com.likelion4thon.Meetdle.room.RoomApiTestConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
 * A11 POST /api/rooms/{roomId}/planning-options 테스트.
 * base-room.sql의 방(2030-01-01 ~ 01-03, 방장+참가자 2명)을 쓰고,
 * 역은 서버 시작 때 공공데이터로 채워진 실제 역(강남, 홍대입구 등)을 쓴다.
 * 시계는 2030-01-01T00:00Z = 서울 1월 1일 오전 9시.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class PlanningOptionsApiTests {

    private static final String ROOM = BoundaryFixtures.ROOM_ID.toString();
    private static final AuthenticatedUser HOST = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "방장");
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "참가자");
    private static final String HOST_PID = "20000000-0000-0000-0000-000000000001";
    private static final String MEMBER_PID = "20000000-0000-0000-0000-000000000002";
    private static final String MEMBER2_PID = "20000000-0000-0000-0000-000000000003";

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
    void calculatesSlotsAndRegionsThenClosesJoining() throws Exception {
        // 방장: 강남 출발·귀가, 1/1 10시 + 1/2 19·20시 / 참가자: 홍대입구 출발·귀가, 1/2 19·20·21시
        complete(HOST_PID, "강남", "강남", "2030-01-01", 10, "2030-01-02", 19, "2030-01-02", 20);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19, "2030-01-02", 20, "2030-01-02", 21);

        calculate(HOST, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inputVersion").value(0))
                .andExpect(jsonPath("$.matrixVersion").value("subway-public-2026-11-16"))
                // 둘 다 되는 19시·20시만 (혼자만 되는 칸은 빠짐). 서울 19시 = UTC 10시
                .andExpect(jsonPath("$.slots", hasSize(2)))
                .andExpect(jsonPath("$.slots[0].meetingAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath("$.slots[0].endsAt").value("2030-01-02T11:00:00Z"))
                .andExpect(jsonPath("$.slots[0].availableCount").value(2))
                .andExpect(jsonPath("$.slots[0].unavailableParticipantIds", hasSize(0)))
                .andExpect(jsonPath("$.slots[1].meetingAt").value("2030-01-02T11:00:00Z"))
                .andExpect(jsonPath("$.regions", hasSize(lessThanOrEqualTo(2))))
                .andExpect(jsonPath("$.regions[0].reasons", hasItem("BALANCED")))
                .andExpect(jsonPath("$.regions[0].station.name").isString())
                .andExpect(jsonPath("$.regions[0].travel", hasSize(2)))
                .andExpect(jsonPath("$.regions[0].score.timeSpreadMinutes").isNumber())
                .andExpect(jsonPath("$.regions[0].score.meanTotalTransfers").isNumber());

        assertThat(jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?", String.class, ROOM))
                .isEqualTo("OPTIONS_READY");
        assertThat(jdbc.queryForObject("SELECT join_closed_at IS NOT NULL FROM room WHERE room_id = ?",
                Boolean.class, ROOM)).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision FROM room WHERE room_id = ?", Long.class, ROOM))
                .isEqualTo(1L);
    }

    @Test
    void retryReturnsSavedOptionsWith200() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19);

        String first = calculate(HOST, 0).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String optionsId = JsonPath.read(first, "$.optionsId");

        calculate(HOST, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionsId").value(optionsId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planning_options", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyCompletedMembersAreFrozen() throws Exception {
        // 참가자2는 입력 중(완료 안 함) → 계산에서 빠지고, 안 되는 사람 목록에도 안 나온다
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19);
        jdbc.update("INSERT INTO member_input (participant_id, origin_station_id, input_revision) VALUES (?, ?, 1)",
                MEMBER2_PID, station("잠실"));

        String body = calculate(HOST, 0).andExpect(status().isCreated())
                .andExpect(jsonPath("$.slots[0].availableCount").value(2))
                .andExpect(jsonPath("$.regions[0].travel", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String optionsId = JsonPath.read(body, "$.optionsId");
        assertThat(jdbc.queryForObject(
                "SELECT calculation_participants_json FROM planning_options WHERE options_id = ?",
                String.class, optionsId)).contains(HOST_PID, MEMBER_PID).doesNotContain(MEMBER2_PID);
    }

    @Test
    void slotsSortAllAvailableFirstThenCountThenTime() throws Exception {
        // 3명: 1/2 18시는 2명, 19시는 3명, 20시는 3명, 21시는 2명 → [19, 20, 18]
        complete(HOST_PID, "강남", "강남", "2030-01-02", 18, "2030-01-02", 19, "2030-01-02", 20,
                "2030-01-02", 21);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 18, "2030-01-02", 19, "2030-01-02", 20);
        complete(MEMBER2_PID, "잠실", "잠실", "2030-01-02", 19, "2030-01-02", 20, "2030-01-02", 21);

        calculate(HOST, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slots[*].meetingAt", contains(
                        "2030-01-02T10:00:00Z", "2030-01-02T11:00:00Z", "2030-01-02T09:00:00Z")))
                .andExpect(jsonPath("$.slots[2].availableCount").value(2))
                .andExpect(jsonPath("$.slots[2].unavailableParticipantIds", contains(MEMBER2_PID)))
                .andExpect(jsonPath("$.regions[0].travel", hasSize(3)));
    }

    @Test
    void pastSlotsAreSkipped() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19, "2030-01-02", 20);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19, "2030-01-02", 20);
        clock.set(Instant.parse("2030-01-02T10:30:00Z")); // 서울 1/2 19:30 → 19시 칸은 이미 시작됨

        calculate(HOST, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slots", hasSize(1)))
                .andExpect(jsonPath("$.slots[0].meetingAt").value("2030-01-02T11:00:00Z"));
    }

    @Test
    void memberCannotCalculate() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19);

        calculate(MEMBER, 0)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsStaleInputVersion() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 19);
        jdbc.update("UPDATE room SET input_version = 3 WHERE room_id = ?", ROOM);

        calculate(HOST, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_INPUT"))
                .andExpect(jsonPath("$.details.currentInputVersion").value(3));
    }

    @Test
    void needsTwoCompletedInputs() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);

        calculate(HOST, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_INPUTS"))
                .andExpect(jsonPath("$.details.completedCount").value(1));
    }

    @Test
    void noSharedHourIsNotFeasibleAndChangesNothing() throws Exception {
        complete(HOST_PID, "강남", "강남", "2030-01-02", 19);
        complete(MEMBER_PID, "홍대입구", "홍대입구", "2030-01-02", 20);

        calculate(HOST, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_FEASIBLE_OPTION"))
                .andExpect(jsonPath("$.details.reason").value("NO_SLOT"));
        assertThat(jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?", String.class, ROOM))
                .isEqualTo("WAITING_FOR_DEPARTURE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planning_options", Integer.class)).isZero();
    }

    @Test
    void unknownRoomIs404AndBadBodyIs400() throws Exception {
        mockMvc.perform(post("/api/rooms/" + UUID.randomUUID() + "/planning-options")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputVersion\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rooms/" + ROOM + "/planning-options")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActions calculate(AuthenticatedUser user, long inputVersion) throws Exception {
        return mockMvc.perform(post("/api/rooms/" + ROOM + "/planning-options")
                .with(authentication(new TestingAuthenticationToken(user, null, "ROLE_USER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputVersion\":" + inputVersion + "}"));
    }

    /** 입력 완료 상태를 DB에 바로 넣는다. slots = 날짜, 시, 날짜, 시, ... */
    private void complete(String participantId, String origin, String destination, Object... slots) {
        jdbc.update("""
                        INSERT INTO member_input (participant_id, origin_station_id, destination_station_id,
                                                  input_revision, completed_at)
                        VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP(6))
                        """,
                participantId, station(origin), station(destination));
        for (int i = 0; i < slots.length; i += 2) {
            jdbc.update("INSERT INTO member_availability (participant_id, available_date, hour_of_day) VALUES (?, ?, ?)",
                    participantId, java.sql.Date.valueOf((String) slots[i]), slots[i + 1]);
        }
    }

    private static String station(String name) {
        return SubwayData.stationIdOf(SubwayData.key(name)).toString();
    }
}
