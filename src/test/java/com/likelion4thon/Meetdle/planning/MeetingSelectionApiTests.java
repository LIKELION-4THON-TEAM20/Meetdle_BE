package com.likelion4thon.Meetdle.planning;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.planning.subway.SubwayData;
import com.likelion4thon.Meetdle.room.MutableClock;
import com.likelion4thon.Meetdle.room.RoomApiTestConfig;
import java.time.Instant;
import java.util.Optional;
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
 * A12 PUT /api/rooms/{roomId}/meeting-selection 테스트.
 * 매 테스트마다 방장(강남)·참가자(홍대입구)가 1/2 19·20시로 입력 완료 → A11 계산까지 해 둔 상태에서 시작한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class MeetingSelectionApiTests {

    private static final String ROOM = BoundaryFixtures.ROOM_ID.toString();
    private static final AuthenticatedUser HOST = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "방장");
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "참가자");
    private static final AuthenticatedUser MEMBER2 = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000003"), "참가자2");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeetingQuery meetingQuery;

    private String optionsId;
    private String slot19;
    private String slot20;
    private String region1;
    private String region2;
    private String region1Name;

    @BeforeEach
    void calculateOptions() throws Exception {
        clock.set(BoundaryFixtures.NOW);
        complete("20000000-0000-0000-0000-000000000001", "강남");
        complete("20000000-0000-0000-0000-000000000002", "홍대입구");
        String body = mockMvc.perform(post("/api/rooms/" + ROOM + "/planning-options")
                        .with(authentication(new TestingAuthenticationToken(HOST, null, "ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputVersion\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        optionsId = JsonPath.read(body, "$.optionsId");
        slot19 = JsonPath.read(body, "$.slots[0].slotId");
        slot20 = JsonPath.read(body, "$.slots[1].slotId");
        region1 = JsonPath.read(body, "$.regions[0].candidateId");
        region2 = JsonPath.read(body, "$.regions[1].candidateId");
        region1Name = JsonPath.read(body, "$.regions[0].station.name");
    }

    @Test
    void firstConfirmationLocksInputsAndFillsMyRooms() throws Exception {
        String body = select(HOST, optionsId, slot19, region1, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.station.name").value(region1Name))
                .andExpect(jsonPath("$.meetingAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2030-01-02T11:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        String selectionId = JsonPath.read(body, "$.selectionId");

        assertThat(jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?", String.class, ROOM))
                .isEqualTo("REGION_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT current_selection_id FROM room WHERE room_id = ?", String.class, ROOM))
                .isEqualTo(selectionId);
        assertThat(jdbc.queryForObject("SELECT inputs_closed_at IS NOT NULL FROM room WHERE room_id = ?",
                Boolean.class, ROOM)).isTrue();

        // 계산에서 빠졌던 참가자2도 이제는 입력을 못 바꾼다 (방 전체 잠금)
        mockMvc.perform(put("/api/rooms/" + ROOM + "/inputs/me")
                        .with(authentication(new TestingAuthenticationToken(MEMBER2, null, "ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originStationId\":null,\"destinationStationId\":null,\"slots\":[],"
                                + "\"inputRevision\":0,\"complete\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INPUT_LOCKED"));

        // A24 내 약속 목록에 확정 시각이 나온다
        mockMvc.perform(get("/api/rooms")
                        .with(authentication(new TestingAuthenticationToken(MEMBER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].meetingAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].endsAt").value("2030-01-02T11:00:00Z"));

        // 장소(B) 쪽이 쓰는 MeetingQuery도 같은 확정을 읽는다
        Optional<SelectedMeeting> meeting = meetingQuery.find(BoundaryFixtures.ROOM_ID);
        assertThat(meeting).isPresent();
        assertThat(meeting.get().selectionId().toString()).isEqualTo(selectionId);
        assertThat(meeting.get().station().name()).isEqualTo(region1Name);
    }

    @Test
    void reselectNeedsCurrentSelectionId() throws Exception {
        String first = JsonPath.read(select(HOST, optionsId, slot19, region1, null)
                .andReturn().getResponse().getContentAsString(), "$.selectionId");

        // 기대값 null로 다시 보내면: 이미 확정이 있으니 409
        select(HOST, optionsId, slot20, region2, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SELECTION"))
                .andExpect(jsonPath("$.details.currentSelectionId").value(first));

        // 지금 선택 ID를 같이 내면 재선택 성공 → 새 선택 행, 포인터 이동
        String second = JsonPath.read(select(HOST, optionsId, slot20, region2, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingAt").value("2030-01-02T11:00:00Z"))
                .andReturn().getResponse().getContentAsString(), "$.selectionId");
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT current_selection_id FROM room WHERE room_id = ?", String.class, ROOM))
                .isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting_selection", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revision FROM room WHERE room_id = ?", Long.class, ROOM))
                .isEqualTo(3L); // 계산 1 + 확정 1 + 재선택 1
    }

    @Test
    void sameChoiceAgainChangesNothing() throws Exception {
        String first = JsonPath.read(select(HOST, optionsId, slot19, region1, null)
                .andReturn().getResponse().getContentAsString(), "$.selectionId");

        select(HOST, optionsId, slot19, region1, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionId").value(first));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting_selection", Integer.class)).isEqualTo(1);
    }

    @Test
    void choiceMustComeFromThisRoomsOptions() throws Exception {
        String random = UUID.randomUUID().toString();
        select(HOST, random, slot19, region1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_OPTION"))
                .andExpect(jsonPath("$.details.optionsId").exists());
        select(HOST, optionsId, random, region1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.slotId").exists());
        select(HOST, optionsId, slot19, random, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.candidateId").exists());
    }

    @Test
    void passedSlotCannotBeChosen() throws Exception {
        clock.set(Instant.parse("2030-01-02T10:10:00Z")); // 서울 19:10
        select(HOST, optionsId, slot19, region1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_OPTION"))
                .andExpect(jsonPath("$.details.slotId").value("PASSED"));
    }

    @Test
    void onlyHostAndOnlyBeforePlaceSubmission() throws Exception {
        select(MEMBER, optionsId, slot19, region1, null)
                .andExpect(status().isForbidden());

        String first = JsonPath.read(select(HOST, optionsId, slot19, region1, null)
                .andReturn().getResponse().getContentAsString(), "$.selectionId");
        jdbc.update("UPDATE room SET phase = 'PLACE_SUBMISSION_OPEN' WHERE room_id = ?", ROOM);
        select(HOST, optionsId, slot20, region2, first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_PHASE"));
    }

    @Test
    void needsOptionsFirst() throws Exception {
        jdbc.update("DELETE FROM region_candidate");
        jdbc.update("DELETE FROM slot_option");
        jdbc.update("DELETE FROM planning_options");
        jdbc.update("UPDATE room SET phase = 'WAITING_FOR_DEPARTURE' WHERE room_id = ?", ROOM);

        select(HOST, optionsId, slot19, region1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_PHASE"));
    }

    private ResultActions select(AuthenticatedUser user, String options, String slot, String candidate,
                                 String expected) throws Exception {
        String expectedJson = expected == null ? "null" : "\"" + expected + "\"";
        return mockMvc.perform(put("/api/rooms/" + ROOM + "/meeting-selection")
                .with(authentication(new TestingAuthenticationToken(user, null, "ROLE_USER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"optionsId":"%s","slotId":"%s","candidateId":"%s","expectedSelectionId":%s}
                        """.formatted(options, slot, candidate, expectedJson)));
    }

    /** 왕복(출발=목적지) 입력 완료 + 1/2 19·20시 가능 */
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
