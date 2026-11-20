package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Action;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import com.likelion4thon.Meetdle.contract.VenueStateReader.CategorySetting;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Registration;
import com.likelion4thon.Meetdle.contract.VenueStateReader.VenueState;
import com.likelion4thon.Meetdle.contract.VenueStateReader.ViewContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * A08이 B(장소) 조각을 명세 모양으로 옮겨 담는지 확인한다.
 * 창환님 VenueStateReader 대신 가짜(Mockito)를 끼워서 "등록 진행 중" 상태를 흉내 낸다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class RoomStateVenueMappingTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private VenueStateReader venueStateReader;

    @Test
    void mapsRegistrationIntoProgressAndNamedCompletion() throws Exception {
        jdbc.update("UPDATE room SET phase = 'PLACE_SUBMISSION_OPEN' WHERE room_id = ?", RoomStateApiTests.ROOM);
        jdbc.update("UPDATE app_user SET profile_image_url = 'https://example.com/host.jpg' WHERE user_id = ?",
                BoundaryFixtures.HOST.userId().toString());
        UUID hostPid = UUID.fromString(RoomStateApiTests.HOST_PID);
        when(venueStateReader.project(any())).thenReturn(new VenueState(
                new Registration(1, Instant.parse("2030-01-01T05:00:00Z"), null,
                        List.of(new CategorySetting(Category.FOOD, true, true, 2500)),
                        3, List.of(hostPid), List.of(), null),
                null, null, null, null, null,
                List.of(Action.SEARCH_PLACES, Action.EDIT_NOMINATION)));

        mockMvc.perform(get("/api/rooms/" + RoomStateApiTests.ROOM + "/state")
                        .with(authentication(new TestingAuthenticationToken(
                                RoomStateApiTests.MEMBER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration.round").value(1))
                .andExpect(jsonPath("$.registration.progress.completedCount").value(1))
                .andExpect(jsonPath("$.registration.progress.capacity").value(4))
                .andExpect(jsonPath("$.registration.completion[0].nickname").value("방장"))
                .andExpect(jsonPath("$.registration.completion[0].completed").value(true))
                .andExpect(jsonPath("$.registration.completion[0].participantId").value(RoomStateApiTests.HOST_PID))
                .andExpect(jsonPath("$.registration.completion[0].profileImageUrl").value("https://example.com/host.jpg"))
                .andExpect(jsonPath("$.registration.completion[1].completed").value(false))
                .andExpect(jsonPath("$.registration.myCompleted").value(false))
                .andExpect(jsonPath("$.registration.categories[0].category").value("FOOD"))
                .andExpect(jsonPath("$.registration.uniqueCandidateCount").value(3))
                .andExpect(jsonPath("$.voting").isEmpty())
                .andExpect(jsonPath("$.me.canRegister").value(true))
                .andExpect(jsonPath("$.allowedActions", contains("SEARCH_PLACES", "EDIT_NOMINATION")));

        ArgumentCaptor<ViewContext> context = ArgumentCaptor.forClass(ViewContext.class);
        verify(venueStateReader).project(context.capture());
        assertThat(context.getValue().roomId()).isEqualTo(BoundaryFixtures.ROOM_ID);
        assertThat(context.getValue().phase()).isEqualTo(RoomPhase.PLACE_SUBMISSION_OPEN);
        assertThat(context.getValue().capacity()).isEqualTo(4);
        assertThat(context.getValue().viewer().participantId().toString())
                .isEqualTo(RoomStateApiTests.MEMBER_PID);
        assertThat(context.getValue().meetingAt()).isEmpty();
    }
}
