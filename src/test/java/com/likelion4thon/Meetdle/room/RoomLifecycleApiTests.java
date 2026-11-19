package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoomApiTestConfig.class)
@Transactional
@Sql("/fixtures/base-room.sql")
class RoomLifecycleApiTests {
    private static final String ROOM = BoundaryFixtures.ROOM_ID.toString();
    private static final String ROOT = "/api/rooms/" + ROOM;
    private static final AuthenticatedUser HOST = RoomStateApiTests.HOST;
    private static final AuthenticatedUser MEMBER = RoomStateApiTests.MEMBER;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void setClock() {
        clock.set(BoundaryFixtures.NOW);
    }

    @Test
    void startFreezesActualMembershipAndIsSafeToRetry() throws Exception {
        mvc.perform(as(post(ROOT + "/start"), MEMBER).content("{\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(ROOT + "/start"), HOST).content("{\"expectedRevision\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ROOM"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(as(post(ROOT + "/start"), HOST).content("{\"expectedRevision\":0}"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(as(get(ROOT + "/state"), HOST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.startedAt").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.revision").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE join_closed_at IS NOT NULL", Integer.class));
    }

    @Test
    void updateResetsPlanningAndInputsButKeepsMembersAndRejectsStaleWrites() throws Exception {
        var hostPid = RoomStateApiTests.HOST_PID;
        jdbc.update("INSERT INTO member_input (participant_id, input_revision, completed_at) VALUES (?, 4, CURRENT_TIMESTAMP)", hostPid);
        jdbc.update("INSERT INTO member_availability (participant_id, available_date, hour_of_day) VALUES (?, '2030-01-02', 12)", hostPid);
        String optionsId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO planning_options (options_id, room_id, input_version, calculation_started_at,
                    calculation_participants_json) VALUES (?, ?, 0, CURRENT_TIMESTAMP, '[]')
                """, optionsId, ROOM);
        jdbc.update("UPDATE room SET phase = 'OPTIONS_READY', join_closed_at = CURRENT_TIMESTAMP WHERE room_id = ?", ROOM);
        String request = """
                {"title":"새 약속","dates":{"from":"2030-01-03","through":"2030-01-05"},
                 "capacity":4,"expectedRevision":0}
                """;
        String key = UUID.randomUUID().toString();
        mvc.perform(as(put(ROOT + "/settings"), MEMBER).header("Idempotency-Key", key).content(request))
                .andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(as(put(ROOT + "/settings"), HOST).header("Idempotency-Key", key).content(request))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        mvc.perform(as(get(ROOT + "/state"), HOST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("WAITING_FOR_DEPARTURE"))
                .andExpect(jsonPath("$.title").value("새 약속"))
                .andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.myInput.inputRevision").value(5))
                .andExpect(jsonPath("$.myInput.slots", hasSize(0)))
                .andExpect(jsonPath("$.members[0].inputStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.inputVersion").value(1));
        mvc.perform(as(put(ROOT + "/settings"), HOST).header("Idempotency-Key", UUID.randomUUID()).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ROOM"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_availability", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM member_input WHERE input_revision > 0", Integer.class));
    }

    @Test
    void deleteHidesOnlyCallersPastRoomAndUpcomingDeletionIsRejected() throws Exception {
        mvc.perform(as(delete(ROOT), MEMBER)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPCOMING_ROOM"));
        clock.set(Instant.parse("2030-01-05T00:00:00Z"));
        mvc.perform(as(delete(ROOT), MEMBER)).andExpect(status().isNoContent());
        mvc.perform(as(delete(ROOT), MEMBER)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/rooms"), MEMBER)).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(as(get("/api/rooms"), HOST)).andExpect(jsonPath("$.items", hasSize(1)));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM room_member", Integer.class));
    }

    @Test
    void sharesUsePersistentMemberLinksAndOnlyExposeCompletionMetadata() throws Exception {
        jdbc.update("UPDATE app_user SET profile_image_url = 'https://example.com/avatar.jpg' WHERE user_id = ?", MEMBER.userId().toString());
        mvc.perform(as(get(ROOT + "/share").param("purpose", "INPUT"), HOST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipients", hasSize(2)))
                .andExpect(jsonPath("$.shareUrl").value("http://localhost:5173/rooms/" + ROOM))
                .andExpect(jsonPath("$.recipients[0].profileImageUrl").value("https://example.com/avatar.jpg"))
                .andExpect(jsonPath("$.messages", hasSize(2)));
        mvc.perform(as(get(ROOT + "/share").param("purpose", "HOST"), MEMBER))
                .andExpect(jsonPath("$.recipients[0].participantId").value(RoomStateApiTests.HOST_PID));
        mvc.perform(as(get(ROOT + "/share").param("purpose", "RESULT"), HOST))
                .andExpect(status().isConflict());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AuthenticatedUser user) {
        return request.with(authentication(new TestingAuthenticationToken(user, null, "ROLE_USER")))
                .with(csrf()).contentType("application/json");
    }
}
