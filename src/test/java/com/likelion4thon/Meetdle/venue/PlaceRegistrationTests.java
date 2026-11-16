package com.likelion4thon.Meetdle.venue;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import com.likelion4thon.Meetdle.contract.VenueStateReader.CategorySetting;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Place;
import com.likelion4thon.Meetdle.venue.local.KakaoLocal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static com.likelion4thon.Meetdle.contract.BoundaryFixtures.*;
import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:registration;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "meetdle.invite.hmac-key=registration-test-signing-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/fixtures/base-room.sql")
class PlaceRegistrationTests {
    private static final String ROOT = "/api/rooms/" + ROOM_ID;
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OBSERVER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String OPEN = ROOT + "/place-submission";
    private static final String SEARCH = ROOT + "/places/search";
    private static final String EXTENSION = OPEN + "/extension";
    private static final String FOOD = OPEN + "/me/FOOD";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlaceRegistration registration;
    @Autowired VenueStateReader reader;
    @MockitoBean RoomVenueAccess rooms;
    @MockitoBean KakaoLocal kakao;

    private Instant now = NOW;

    @Test
    void registrationLifecycleUsesSignedPicksAndProjectsOnlyOwnNominations() throws Exception {
        var fixture = confirmedVenueRoom();
        var meeting = fixture.selectedMeeting().orElseThrow();
        jdbc.update("UPDATE room SET phase = 'REGION_CONFIRMED', capacity = 3 WHERE room_id = ?", ROOM_ID.toString());
        when(rooms.read(eq(ROOM_ID), any(UUID.class))).thenAnswer(call ->
                snapshot(member(call.getArgument(1))));
        when(rooms.lockForWrite(eq(ROOM_ID), any())).thenAnswer(call -> {
            jdbc.queryForObject("SELECT room_id FROM room WHERE room_id = ? FOR UPDATE",
                    String.class, ROOM_ID.toString());
            var actor = call.getArgument(1, RoomVenueAccess.Actor.class);
            Optional<RoomMember> member = actor instanceof RoomVenueAccess.UserActor user
                    ? Optional.of(member(user.userId())) : Optional.empty();
            var snapshot = snapshot(member);
            return (RoomVenueAccess.LockedRoomVenue) () -> snapshot;
        });
        doAnswer(call -> {
            jdbc.update("UPDATE room SET phase = ?, revision = revision + 1 WHERE room_id = ?",
                    call.getArgument(1, RoomPhase.class).name(), ROOM_ID.toString());
            return null;
        }).when(rooms).recordChange(any(), any());

        for (Category category : Category.values()) {
            when(kakao.setting(meeting.station(), category))
                    .thenReturn(new CategorySetting(category, false, category != Category.CULTURE, 2500));
        }
        when(kakao.search(eq(meeting.station()), eq(Category.FOOD), isNull(), any(), eq(2500), eq(1)))
                .thenReturn(new KakaoLocal.Page(List.of(
                        new KakaoLocal.FoundPlace("123", new Place("Nearby", "Address", 37.5547, 126.9707,
                                Category.FOOD, null)),
                        new KakaoLocal.FoundPlace("124", new Place("Far away", "Address", 37.65, 126.9707,
                                Category.FOOD, null))), true));

        when(kakao.search(eq(meeting.station()), eq(Category.CAFE), isNull(), any(), eq(2500), eq(1)))
                .thenReturn(new KakaoLocal.Page(List.of(new KakaoLocal.FoundPlace("125",
                        new Place("Cafe", "Address", 37.5547, 126.9707, Category.CAFE, null))), true));

        mvc.perform(get(SEARCH).param("category", "FOOD")).andExpect(status().isUnauthorized());
        var host = session(HOST.userId());
        var member = session(MEMBER);
        var observer = session(OBSERVER);
        mvc.perform(get(OPEN + "/categories").session(host.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories.length()").value(4));
        mvc.perform(get(OPEN + "/categories").session(member.session())).andExpect(status().isForbidden());

        String openBody = """
                {"selectionId":"%s","requiredCategories":["FOOD"]}
                """.formatted(meeting.selectionId());
        UUID openKey = UUID.randomUUID();
        postCommand(OPEN, host, openKey, openBody, 201);
        postCommand(OPEN, host, openKey, openBody, 201);
        postCommand(OPEN, host, openKey, openBody.replace("FOOD", "CAFE"), 409);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM venue_round", Integer.class));

        mvc.perform(get(SEARCH).session(host.session()).param("category", "FOOD")
                        .param("bounds", "127,38,126,37")).andExpect(status().isBadRequest());
        String response = mvc.perform(get(SEARCH).session(observer.session()).param("category", "FOOD")
                        .param("bounds", "126,37,128,38"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].registrable").value(true))
                .andExpect(jsonPath("$.items[1].registrable").value(false))
                .andExpect(jsonPath("$.items[1].pickToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.items[0].pickToken");
        String pick = """
                {"round":1,"pickToken":"%s"}
                """.formatted(token);

        putCommand(OPEN + "/me/completion", member, "{\"round\":1}", 409);
        putCommand(FOOD, host, "{\"round\":1}", 400);
        putCommand(FOOD, host, pick.replace(token, "invalid"), 400);
        putCommand(FOOD, host, pick, 204);
        assertEquals(1, projection(HOST.userId()).uniqueCandidateCount());
        assertEquals(0, projection(MEMBER).myNominations().size());
        assertNull(projection(MEMBER).categoryCounts());
        putCommand(FOOD, member, pick, 204);
        putCommand(FOOD, host, "{\"round\":1,\"pickToken\":null}", 204);
        assertEquals(1, projection(MEMBER).uniqueCandidateCount());
        putCommand(FOOD, host, pick, 204);

        putCommand(OPEN + "/me/completion", host, "{\"round\":1}", 204);
        putCommand(OPEN + "/me/completion", member, "{\"round\":1}", 204);
        putCommand(FOOD, host, "{\"round\":1,\"pickToken\":null}", 409);
        assertEquals(2, projection(HOST.userId()).completedParticipantIds().size());

        String cafe = mvc.perform(get(SEARCH).session(observer.session()).param("category", "CAFE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cafePath = OPEN + "/me/CAFE";
        putCommand(cafePath, observer, pick.replace(token, JsonPath.<String>read(cafe, "$.items[0].pickToken")), 204);
        putCommand(OPEN + "/closure", member, "{\"round\":1}", 403);

        String mini = "{\"round\":1,\"mode\":\"MINI_TIMER\"}";
        postCommand(EXTENSION, host, UUID.randomUUID(), mini, 200);
        postCommand(EXTENSION, host, UUID.randomUUID(), mini, 409);
        now = NOW.plusSeconds(7200);
        putCommand(FOOD, observer, pick, 409);
        registration.advanceDeadline(ROOM_ID);
        assertEquals(PLACE_SUBMISSION_CLOSED, phase());
        assertEquals(now, projection(HOST.userId()).closedAt());
        assertEquals(1, projection(HOST.userId()).categoryCounts().stream()
                .filter(count -> count.category() == Category.FOOD).findFirst().orElseThrow().count());

        String extend = """
                {"round":1,"mode":"EXTEND","closesAt":"2030-01-01T02:15:00Z"}
                """;
        UUID extendKey = UUID.randomUUID();
        postCommand(EXTENSION, host, extendKey, extend, 200);
        postCommand(EXTENSION, host, extendKey, extend, 200);
        postCommand(EXTENSION, host, UUID.randomUUID(), extend, 409);
        putCommand(FOOD, observer, pick, 409);
        putCommand(cafePath, observer, "{\"round\":1,\"pickToken\":null}", 204);
        String fresh = mvc.perform(get(SEARCH).session(observer.session()).param("category", "FOOD"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        putCommand(FOOD, observer, pick.replace(token, JsonPath.<String>read(fresh, "$.items[0].pickToken")), 204);
        putCommand(OPEN + "/me/completion", observer, "{\"round\":1}", 204);
        putCommand(OPEN + "/me/completion", observer, "{\"round\":1}", 204);
        assertEquals(FINAL_PLACE_CONFIRMED, phase());
        var result = reader.project(new VenueStateReader.ViewContext(ROOM_ID, member(MEMBER), phase(), fixture.capacity(),
                fixture.selectedMeeting().map(selected -> selected.meetingAt()), now)).result();
        assertEquals(0, result.submittedCount());
        assertEquals(List.of(Category.FOOD), result.selected().stream().map(place -> place.place().category()).toList());
        assertEquals(List.of(result.selected().get(0).candidateId()), result.visitOrder());
        assertNull(result.selected().get(0).aggregate());

        verifyVoting(host, member, meeting.selectionId());
    }

    private void verifyVoting(Session host, Session member, UUID selectionId) throws Exception {
        String room = ROOM_ID.toString();
        jdbc.update("UPDATE room SET phase = 'PLACE_SUBMISSION_CLOSED' WHERE room_id = ?", room);
        jdbc.update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at, collection_closed_at)
                VALUES (?, 2, ?, '2030-01-01 02:15:00', '2030-01-01 02:15:00')
                """, room, selectionId.toString());
        jdbc.update("""
                INSERT INTO round_member (room_id, round_no, participant_id)
                SELECT room_id, 2, participant_id FROM room_member WHERE room_id = ?
                """, room);
        jdbc.update("""
                INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                VALUES (?, 2, 'FOOD', TRUE, TRUE, 2500), (?, 2, 'CAFE', FALSE, TRUE, 2500)
                """, room, room);
        UUID auto = UUID.randomUUID();
        UUID competitive = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO place_candidate (room_id, round_no, candidate_id, category, provider_place_id,
                    name, address, lat, lng)
                VALUES (?, 2, ?, 'FOOD', '1', 'Food', 'Address', 37, 127),
                       (?, 2, ?, 'CAFE', '2', 'Cafe', 'Address', 37, 127)
                """, room, auto.toString(), room, competitive.toString());
        now = Instant.parse("2030-01-01T02:15:00Z");
        Instant deadline = now.plusSeconds(1800);
        String start = """
                {"round":2,"closesAt":"%s"}
                """.formatted(deadline);
        UUID key = UUID.randomUUID();
        postCommand(ROOT + "/voting", member, key, start, 403);
        postCommand(ROOT + "/voting", host, key, start, 204);
        postCommand(ROOT + "/voting", host, key, start, 204);
        assertEquals(VOTING_OPEN, phase());
        assertEquals(2, voteState(HOST.userId()).candidates().size());

        String preferences = ROOT + "/preferences/me";
        String submissions = ROOT + "/vote-submissions/me";
        String edit = """
                {"round":2,"draftRevision":0,"selections":[{"candidateId":"%s","choice":"LIKE"}]}
                """;
        putCommand(submissions, host, "{\"round\":2,\"draftRevision\":0}", 409);
        putCommand(preferences, host, edit.formatted(auto), 400);
        putCommand(preferences, host, edit.formatted(competitive), 200);
        putCommand(preferences, host, edit.formatted(competitive), 409);
        assertEquals(List.of(), voteState(MEMBER).myDraft().selections());

        String submit = "{\"round\":2,\"draftRevision\":1}";
        putCommand(submissions, host, submit, 204);
        putCommand(submissions, host, submit, 204);
        putCommand(preferences, host, edit.formatted(competitive), 409);
        assertEquals(List.of(HOST_PARTICIPANT), voteState(MEMBER).completedParticipantIds());
        now = deadline;
        putCommand(preferences, member, edit.formatted(competitive), 409);
        putCommand(submissions, member, "{\"round\":2,\"draftRevision\":0}", 409);
    }

    private VenueStateReader.Voting voteState(UUID user) {
        return reader.project(new VenueStateReader.ViewContext(ROOM_ID, member(user), phase(), confirmedVenueRoom().capacity(),
                confirmedVenueRoom().selectedMeeting().map(meeting -> meeting.meetingAt()), now)).voting();
    }

    private RoomPhase phase() {
        return RoomPhase.valueOf(jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?",
                String.class, ROOM_ID.toString()));
    }

    private VenueStateReader.Registration projection(UUID user) {
        return reader.project(new VenueStateReader.ViewContext(ROOM_ID, member(user), phase(), confirmedVenueRoom().capacity(),
                confirmedVenueRoom().selectedMeeting().map(meeting -> meeting.meetingAt()), now)).registration();
    }

    private RoomVenueAccess.RoomVenueSnapshot snapshot(RoomMember member) {
        return snapshot(Optional.of(member));
    }

    private RoomVenueAccess.RoomVenueSnapshot snapshot(Optional<RoomMember> member) {
        var fixture = confirmedVenueRoom();
        return new RoomVenueAccess.RoomVenueSnapshot(ROOM_ID, phase(), 1, 3, member,
                fixture.memberParticipantIds(), fixture.selectedMeeting(), now);
    }

    private static RoomMember member(UUID user) {
        if (user.equals(HOST.userId())) {
            return new RoomMember(HOST_PARTICIPANT, RoomMember.Role.HOST);
        }
        return new RoomMember(user.equals(MEMBER) ? MEMBER_PARTICIPANT : MEMBER_WITHOUT_PLANNING_INPUT,
                RoomMember.Role.MEMBER);
    }

    private Session session(UUID user) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user, "Test"), null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        String body = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(session, JsonPath.read(body, "$.csrfToken"));
    }

    private void postCommand(String path, Session session, UUID key, String body, int expected) throws Exception {
        mvc.perform(post(path).session(session.session()).header("X-CSRF-TOKEN", session.csrf())
                        .header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().is(expected));
    }

    private void putCommand(String path, Session session, String body, int expected) throws Exception {
        mvc.perform(put(path).session(session.session()).header("X-CSRF-TOKEN", session.csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().is(expected));
    }

    private record Session(MockHttpSession session, String csrf) {}
}
