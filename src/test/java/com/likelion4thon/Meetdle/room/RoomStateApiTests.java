package com.likelion4thon.Meetdle.room;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.planning.subway.SubwayData;
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
 * A08 GET /api/rooms/{roomId}/state 테스트.
 * B(장소) 코드가 아직 이 브랜치에 없어서 장소 칸(registration 등)은 null인 상태를 확인한다.
 * B 조각을 옮겨 담는 부분은 RoomStateVenueMappingTests에서 가짜 B로 따로 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class RoomStateApiTests {

    static final String ROOM = BoundaryFixtures.ROOM_ID.toString();
    static final AuthenticatedUser HOST = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "방장");
    static final AuthenticatedUser MEMBER = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "참가자");
    static final AuthenticatedUser MEMBER2 = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000003"), "참가자2");
    static final String HOST_PID = "20000000-0000-0000-0000-000000000001";
    static final String MEMBER_PID = "20000000-0000-0000-0000-000000000002";

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
    void newRoomShowsInviteOnlyToHost() throws Exception {
        String created = mockMvc.perform(post("/api/rooms")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"금요 저녁\",\"dates\":{\"from\":\"2030-01-10\",\"through\":\"2030-01-12\"},"
                                + "\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String roomId = JsonPath.read(created, "$.roomId");
        String code = JsonPath.read(created, "$.invite.code");
        String inviteUrl = JsonPath.read(created, "$.invite.inviteUrl");

        state(HOST, roomId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.title").value("금요 저녁"))
                .andExpect(jsonPath("$.dates.from").value("2030-01-10"))
                .andExpect(jsonPath("$.phase").value("WAITING_FOR_DEPARTURE"))
                .andExpect(jsonPath("$.serverNow").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.joinedCount").value(1))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.inputProgress.completedCount").value(0))
                .andExpect(jsonPath("$.inputProgress.capacity").value(4))
                .andExpect(jsonPath("$.me.role").value("HOST"))
                .andExpect(jsonPath("$.me.canRegister").value(false))
                .andExpect(jsonPath("$.members", hasSize(1)))
                .andExpect(jsonPath("$.myInput.inputRevision").value(0))
                .andExpect(jsonPath("$.myInput.slots", empty()))
                // 방장은 만들 때 받은 초대 코드·링크를 다시 볼 수 있다
                .andExpect(jsonPath("$.invite.code").value(code))
                .andExpect(jsonPath("$.invite.inviteUrl").value(inviteUrl))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.meeting").isEmpty())
                .andExpect(jsonPath("$.registration").isEmpty())
                .andExpect(jsonPath("$.allowedActions", contains("EDIT_INPUT", "COMPLETE_INPUT", "EDIT_ROOM")));
    }

    @Test
    void membersSeeEveryoneButNotInvite() throws Exception {
        complete(HOST_PID, "강남");

        state(MEMBER, ROOM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me.participantId").value(MEMBER_PID))
                .andExpect(jsonPath("$.me.role").value("MEMBER"))
                .andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.members[0].nickname").value("방장"))
                .andExpect(jsonPath("$.members[0].inputComplete").value(true))
                .andExpect(jsonPath("$.members[1].inputComplete").value(false))
                .andExpect(jsonPath("$.inputProgress.completedCount").value(1))
                .andExpect(jsonPath("$.invite").isEmpty());

        // 방장 본인은 이미 완료 → 입력 버튼 없음, 완료자 1명이라 계산 버튼도 아직 없음
        state(HOST, ROOM)
                .andExpect(jsonPath("$.myInput.originStation.name").value("강남"))
                .andExpect(jsonPath("$.myInput.completedAt").isString())
                .andExpect(jsonPath("$.allowedActions", contains("EDIT_ROOM", "START_ROOM")));
    }

    @Test
    void hostGetsCalculateButtonWithTwoCompleted() throws Exception {
        complete(HOST_PID, "강남");
        complete(MEMBER_PID, "홍대입구");

        state(HOST, ROOM)
                .andExpect(jsonPath("$.inputProgress.completedCount").value(2))
                .andExpect(jsonPath("$.allowedActions", contains("CALCULATE_OPTIONS", "EDIT_ROOM", "START_ROOM")));
        state(MEMBER2, ROOM)
                .andExpect(jsonPath("$.allowedActions", contains("EDIT_INPUT", "COMPLETE_INPUT")));
    }

    @Test
    void afterCalculationOnlyHostSeesPersonalTravel() throws Exception {
        complete(HOST_PID, "강남");
        complete(MEMBER_PID, "홍대입구");
        calculate();

        state(HOST, ROOM)
                .andExpect(jsonPath("$.phase").value("OPTIONS_READY"))
                .andExpect(jsonPath("$.options.slots", hasSize(2)))
                .andExpect(jsonPath("$.options.slots[0].endsAt").value("2030-01-02T11:00:00Z"))
                .andExpect(jsonPath("$.options.regions[0].travel", hasSize(2)))
                // 가입 마감 뒤라 초대는 더 안 보여줌
                .andExpect(jsonPath("$.invite").isEmpty())
                .andExpect(jsonPath("$.allowedActions", contains("SELECT_MEETING", "EDIT_ROOM")));

        state(MEMBER, ROOM)
                .andExpect(jsonPath("$.options.regions[0].station.name").isString())
                .andExpect(jsonPath("$.options.regions[0].travel", empty()))
                .andExpect(jsonPath("$.allowedActions", empty()));

        // 계산 뒤에도 미완료자는 첫 확정 전까지 완료할 수 있다
        state(MEMBER2, ROOM)
                .andExpect(jsonPath("$.allowedActions", containsInAnyOrder("EDIT_INPUT", "COMPLETE_INPUT")));
    }

    @Test
    void afterConfirmationShowsMeetingAndLocksInputs() throws Exception {
        complete(HOST_PID, "강남");
        complete(MEMBER_PID, "홍대입구");
        String options = calculate();

        mockMvc.perform(put("/api/rooms/" + ROOM + "/meeting-selection")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionsId\":\"%s\",\"slotId\":\"%s\",\"candidateId\":\"%s\",\"expectedSelectionId\":null}"
                                .formatted(JsonPath.read(options, "$.optionsId"),
                                        JsonPath.read(options, "$.slots[0].slotId"),
                                        JsonPath.read(options, "$.regions[0].candidateId"))))
                .andExpect(status().isOk());

        state(MEMBER2, ROOM)
                .andExpect(jsonPath("$.phase").value("REGION_CONFIRMED"))
                .andExpect(jsonPath("$.meeting.meetingAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath("$.meeting.station.name").isString())
                .andExpect(jsonPath("$.allowedActions", not(hasItem("EDIT_INPUT"))));
        state(HOST, ROOM)
                .andExpect(jsonPath("$.allowedActions", hasItem("SELECT_MEETING")));
    }

    @Test
    void outsidersAndUnknownRooms() throws Exception {
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "남");
        state(stranger, ROOM)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        state(HOST, UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
    }

    private ResultActions state(AuthenticatedUser user, String roomId) throws Exception {
        return mockMvc.perform(get("/api/rooms/" + roomId + "/state")
                .with(authentication(new TestingAuthenticationToken(user, null, "ROLE_USER"))));
    }

    private String calculate() throws Exception {
        return mockMvc.perform(post("/api/rooms/" + ROOM + "/planning-options")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputVersion\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** 왕복 입력 완료 + 1/2 19·20시 가능 */
    private void complete(String participantId, String stationName) {
        String station = SubwayData.stationIdOf(SubwayData.key(stationName)).toString();
        jdbc.update("""
                        INSERT INTO member_input (participant_id, origin_station_id, destination_station_id,
                                                  input_revision, completed_at)
                        VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP(6))
                        """, participantId, station, station);
        for (int hour : new int[] {19, 20}) {
            jdbc.update("INSERT INTO member_availability (participant_id, available_date, hour_of_day) VALUES (?, ?, ?)",
                    participantId, java.sql.Date.valueOf("2030-01-02"), hour);
        }
    }
}
