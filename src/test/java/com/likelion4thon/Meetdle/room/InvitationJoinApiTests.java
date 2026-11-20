package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * A06 링크 미리보기 → A25 코드 확인 → A07 가입 흐름 테스트.
 * 시나리오: 방장(은서 역할)이 방을 만들고, 친구가 링크/코드로 들어온다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class InvitationJoinApiTests {

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

    // ───────────── A06 링크 미리보기 ─────────────

    @Test
    void anyoneCanPreviewWithLink() throws Exception {
        CreatedRoom room = createRoom(3);

        mockMvc.perform(get("/api/invitations/" + room.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(room.roomId()))
                .andExpect(jsonPath("$.title").value("초대 테스트"))
                .andExpect(jsonPath("$.dates.from").value("2030-01-10"))
                .andExpect(jsonPath("$.dates.through").value("2030-01-12"))
                .andExpect(jsonPath("$.hostNickname").value("방장"))
                .andExpect(jsonPath("$.joinedCount").value(1))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.entryStatus").value("OPEN"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void previewShowsMemberToTheHost() throws Exception {
        CreatedRoom room = createRoom(3);

        mockMvc.perform(get("/api/invitations/" + room.token()).with(loggedIn(BoundaryFixtures.HOST)))
                .andExpect(jsonPath("$.entryStatus").value("MEMBER"));
    }

    @Test
    void previewRejectsUnknownMalformedAndExpiredLinks() throws Exception {
        CreatedRoom room = createRoom(3);

        mockMvc.perform(get("/api/invitations/" + "A".repeat(43)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(get("/api/invitations/too-short"))
                .andExpect(status().isNotFound());

        clock.advance(Duration.ofDays(7)); // 2030-01-08T00:00Z = 만료 시각 그 순간
        mockMvc.perform(get("/api/invitations/" + room.token()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    @Test
    void previewShowsFullAndClosed() throws Exception {
        CreatedRoom room = createRoom(2);
        joinWithCode(newUser("친구1"), room); // 방장 + 친구1 = 2/2

        mockMvc.perform(get("/api/invitations/" + room.token()))
                .andExpect(jsonPath("$.joinedCount").value(2))
                .andExpect(jsonPath("$.entryStatus").value("FULL"));

        jdbc.update("UPDATE room SET join_closed_at = CURRENT_TIMESTAMP(6) WHERE room_id = ?", room.roomId());
        mockMvc.perform(get("/api/invitations/" + room.token()))
                .andExpect(jsonPath("$.entryStatus").value("CLOSED"));
    }

    // ───────────── A25 코드 확인 ─────────────

    @Test
    void resolvesLinkTokenWithoutCodeAndJoinsWithTheIssuedProof() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("링크로 온 친구");
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolveToken(friend, session, room.token())
                .andExpect(jsonPath("$.preview.roomId").value(room.roomId()))
                .andExpect(jsonPath("$.preview.entryStatus").value("OPEN"))
                .andExpect(jsonPath("$.joinProof").value(matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.joinProofExpiresAt").value("2030-01-01T00:10:00Z")));
        join(friend, session, room.roomId(), proof).andExpect(status().isCreated());
        join(friend, session, room.roomId(), proof).andExpect(status().isOk());
        assertThat(memberCount(room.roomId())).isEqualTo(2);
    }

    @Test
    void linkAndCodePreviewsIncludeOnlyCurrentParticipantsDisplayProfiles() throws Exception {
        CreatedRoom room = createRoom(3);
        jdbc.update("UPDATE app_user SET nickname = ?, profile_image_url = ? WHERE user_id = ?",
                "현재 방장 이름", "https://example.com/host.jpg", BoundaryFixtures.HOST.userId().toString());
        AuthenticatedUser friend = newUser("사진 없는 친구");
        joinWithCode(friend, room);
        jdbc.update("UPDATE room_member SET joined_at = '2030-01-01 00:00:00' WHERE room_id = ? AND role = 'HOST'", room.roomId());
        jdbc.update("UPDATE room_member SET joined_at = '2030-01-01 00:00:01' WHERE room_id = ? AND user_id = ?",
                room.roomId(), friend.userId().toString());

        String preview = mockMvc.perform(get("/api/invitations/" + room.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hostNickname").value("현재 방장 이름"))
                .andExpect(jsonPath("$.joinedCount").value(2))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].nickname").value("현재 방장 이름"))
                .andExpect(jsonPath("$.members[0].profileImageUrl").value("https://example.com/host.jpg"))
                .andReturn().getResponse().getContentAsString();
        var profiles = JsonPath.<List<Map<String, Object>>>read(preview, "$.members");
        assertThat(profiles).allSatisfy(profile -> assertThat(profile.keySet())
                .isEqualTo(Set.of("participantId", "nickname", "profileImageUrl")));
        assertThat(profiles.get(1)).containsEntry("nickname", "사진 없는 친구")
                .containsEntry("profileImageUrl", null);
        String participantId = jdbc.queryForObject("SELECT participant_id FROM room_member WHERE room_id = ? AND user_id = ?",
                String.class, room.roomId(), friend.userId().toString());
        assertThat(profiles.get(1)).containsEntry("participantId", participantId);

        for (var resolved : List.of(
                resolve(BoundaryFixtures.HOST, new MockHttpSession(), room.code(), null),
                resolveToken(BoundaryFixtures.HOST, new MockHttpSession(), room.token()))) {
            String body = resolved.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.preview.members")).isEqualTo(profiles);
        }
    }

    @Test
    void tokenOnlyResolveRejectsUnknownTokensMissingCredentialsAndMissingCsrf() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("친구");
        for (String json : List.of("{}", "{\"code\":null,\"inviteToken\":null}",
                "{\"code\":\"\",\"inviteToken\":\"" + room.token() + "\"}")) {
            mockMvc.perform(post("/api/invitations/resolve").with(loggedIn(friend)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String token : List.of("", "invalid", "z".repeat(43))) {
            resolveToken(friend, new MockHttpSession(), token).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        }
        mockMvc.perform(post("/api/invitations/resolve").with(loggedIn(friend))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inviteToken\":\"" + room.token() + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invitations/resolve").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inviteToken\":\"" + room.token() + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void tokenOnlyProofIsCappedByInvitationExpiry() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("늦은 친구");
        MockHttpSession session = new MockHttpSession();
        clock.advance(Duration.ofDays(7).minusMinutes(5));
        String proof = proofFrom(resolveToken(friend, session, room.token())
                .andExpect(jsonPath("$.joinProofExpiresAt").value("2030-01-08T00:00:00Z")));
        clock.advance(Duration.ofMinutes(5));
        resolveToken(friend, session, room.token()).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        join(friend, session, room.roomId(), proof).andExpect(status().isGone());
    }

    @Test
    void tokenOnlyResolveKeepsFullClosedAndExistingMemberChecks() throws Exception {
        CreatedRoom room = createRoom(2);
        AuthenticatedUser member = newUser("먼저 온 친구");
        var memberSession = new MockHttpSession();
        join(member, memberSession, room.roomId(), proofFrom(resolveToken(member, memberSession, room.token())))
                .andExpect(status().isCreated());
        resolveToken(member, memberSession, room.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.entryStatus").value("MEMBER"));

        AuthenticatedUser late = newUser("늦은 친구");
        var lateSession = new MockHttpSession();
        String proof = proofFrom(resolveToken(late, lateSession, room.token())
                .andExpect(jsonPath("$.preview.entryStatus").value("FULL")));
        join(late, lateSession, room.roomId(), proof).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_FULL"));
        jdbc.update("UPDATE room SET join_closed_at = CURRENT_TIMESTAMP(6) WHERE room_id = ?", room.roomId());
        resolveToken(late, lateSession, room.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.entryStatus").value("CLOSED"));
        join(late, lateSession, room.roomId(), proof).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOIN_CLOSED"));
    }

    @Test
    void tokenOnlyResolveUsesTheExistingAttemptLimiter() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("친구");
        for (int attempt = 0; attempt < 5; attempt++) {
            resolveToken(friend, new MockHttpSession(), "z".repeat(43)).andExpect(status().isNotFound());
        }
        resolveToken(friend, new MockHttpSession(), room.token()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        clock.advance(Duration.ofMinutes(10));
        resolveToken(friend, new MockHttpSession(), room.token()).andExpect(status().isOk());
    }

    @Test
    void resolvesCodeIgnoringSpacesAndCase() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("친구");

        resolve(friend, new MockHttpSession(), "  " + room.code().toLowerCase() + " ", room.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.roomId").value(room.roomId()))
                .andExpect(jsonPath("$.preview.entryStatus").value("OPEN"))
                .andExpect(jsonPath("$.joinProof").value(matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.joinProofExpiresAt").value("2030-01-01T00:10:00Z")); // 지금 + 10분
    }

    @Test
    void rejectsWrongCodeOrCodeFromAnotherLink() throws Exception {
        CreatedRoom room = createRoom(3);
        CreatedRoom otherRoom = createRoom(3);
        AuthenticatedUser friend = newUser("친구");

        resolve(friend, new MockHttpSession(), wrongCode(room.code()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        // 코드는 맞지만, 링크는 다른 방 링크
        resolve(friend, new MockHttpSession(), room.code(), otherRoom.token())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        // 형식부터 틀림 (6자리 아님)
        resolve(friend, new MockHttpSession(), "ABC", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void blocksAfterFiveWrongCodesForTenMinutes() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("찍기왕");

        for (int i = 0; i < 5; i++) {
            resolve(friend, new MockHttpSession(), wrongCode(room.code()), null).andExpect(status().isNotFound());
        }
        // 6번째는 맞는 코드여도 막힌다
        resolve(friend, new MockHttpSession(), room.code(), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(600));

        clock.advance(Duration.ofMinutes(10));
        resolve(friend, new MockHttpSession(), room.code(), null).andExpect(status().isOk());
    }

    @Test
    void resolveRejectsExpiredInviteAndAnonymousCaller() throws Exception {
        CreatedRoom room = createRoom(3);

        mockMvc.perform(post("/api/invitations/resolve").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + room.code() + "\"}"))
                .andExpect(status().is4xxClientError());

        clock.advance(Duration.ofDays(8));
        resolve(newUser("늦은친구"), new MockHttpSession(), room.code(), null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    // ───────────── A07 가입 ─────────────

    @Test
    void joinsOnceThenReturnsExistingMembership() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("친구");
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolve(friend, session, room.code(), room.token()));

        join(friend, session, room.roomId(), proof)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(room.roomId()));
        assertThat(jdbc.queryForObject(
                "SELECT role FROM room_member WHERE room_id = ? AND user_id = ?",
                String.class, room.roomId(), friend.userId().toString())).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT nickname FROM room_member WHERE room_id = ? AND user_id = ?",
                String.class, room.roomId(), friend.userId().toString())).isEqualTo("친구");

        // 같은 증표로 다시 눌러도 → 200 기존 회원, 인원은 그대로 2명
        join(friend, session, room.roomId(), proof).andExpect(status().isOk());
        assertThat(memberCount(room.roomId())).isEqualTo(2);
    }

    @Test
    void proofOnlyWorksInSameSessionUserAndRoom() throws Exception {
        CreatedRoom room = createRoom(3);
        CreatedRoom otherRoom = createRoom(3);
        AuthenticatedUser friend = newUser("친구");
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolveToken(friend, session, room.token()));

        join(friend, new MockHttpSession(), room.roomId(), proof)       // 다른 브라우저
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        join(newUser("남"), session, room.roomId(), proof)               // 다른 사람
                .andExpect(status().isNotFound());
        join(friend, session, otherRoom.roomId(), proof)                // 다른 방
                .andExpect(status().isNotFound());
        assertThat(memberCount(room.roomId())).isEqualTo(1);
    }

    @Test
    void proofExpiresAfterTenMinutes() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser friend = newUser("친구");
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolve(friend, session, room.code(), null));

        clock.advance(Duration.ofMinutes(10));
        join(friend, session, room.roomId(), proof)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    @Test
    void rejectsJoinWhenRoomIsFull() throws Exception {
        CreatedRoom room = createRoom(2);
        joinWithCode(newUser("친구1"), room); // 2/2

        AuthenticatedUser late = newUser("친구2");
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolve(late, session, room.code(), null));
        join(late, session, room.roomId(), proof)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_FULL"));
        assertThat(memberCount(room.roomId())).isEqualTo(2);
    }

    @Test
    void rejectsNewMembersAfterPlanningStartedButKeepsExistingOnes() throws Exception {
        CreatedRoom room = createRoom(3);
        AuthenticatedUser member = newUser("먼저온친구");
        MockHttpSession memberSession = new MockHttpSession();
        String memberProof = proofFrom(resolve(member, memberSession, room.code(), null));
        join(member, memberSession, room.roomId(), memberProof).andExpect(status().isCreated());

        AuthenticatedUser late = newUser("늦은친구");
        MockHttpSession lateSession = new MockHttpSession();
        String lateProof = proofFrom(resolve(late, lateSession, room.code(), null));

        // 방장이 추천 계산을 시작함 (A11이 할 일을 여기서는 직접 표시)
        jdbc.update("UPDATE room SET join_closed_at = CURRENT_TIMESTAMP(6) WHERE room_id = ?", room.roomId());

        join(late, lateSession, room.roomId(), lateProof)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOIN_CLOSED"));
        // 기존 회원 확인이 먼저라서, 원래 회원은 200
        join(member, memberSession, room.roomId(), memberProof).andExpect(status().isOk());
    }

    // ───────────── 도우미 ─────────────

    private record CreatedRoom(String roomId, String code, String token) {}

    private CreatedRoom createRoom(int capacity) throws Exception {
        String body = mockMvc.perform(post("/api/rooms").with(loggedIn(BoundaryFixtures.HOST)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"초대 테스트","dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":%d}
                                """.formatted(capacity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.invite.inviteUrl");
        return new CreatedRoom(JsonPath.read(body, "$.roomId"), JsonPath.read(body, "$.invite.code"),
                url.substring(url.lastIndexOf('/') + 1));
    }

    /** 카카오 로그인 대신 app_user에 사람을 직접 넣는다. */
    private AuthenticatedUser newUser(String nickname) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, provider, provider_user_id, nickname) VALUES (?, 'KAKAO', ?, ?)",
                userId.toString(), userId.toString(), nickname);
        return new AuthenticatedUser(userId, nickname);
    }

    private ResultActions resolve(AuthenticatedUser user, MockHttpSession session, String code, String token)
            throws Exception {
        String json = token == null
                ? "{\"code\":\"" + code + "\"}"
                : "{\"code\":\"" + code + "\",\"inviteToken\":\"" + token + "\"}";
        return mockMvc.perform(post("/api/invitations/resolve").session(session)
                .with(loggedIn(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions resolveToken(AuthenticatedUser user, MockHttpSession session, String token) throws Exception {
        return mockMvc.perform(post("/api/invitations/resolve").session(session)
                .with(loggedIn(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteToken\":\"" + token + "\"}"));
    }

    private ResultActions join(AuthenticatedUser user, MockHttpSession session, String roomId, String proof)
            throws Exception {
        return mockMvc.perform(post("/api/rooms/" + roomId + "/join").session(session)
                .with(loggedIn(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"joinProof\":\"" + proof + "\"}"));
    }

    private void joinWithCode(AuthenticatedUser user, CreatedRoom room) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String proof = proofFrom(resolve(user, session, room.code(), null));
        join(user, session, room.roomId(), proof).andExpect(status().isCreated());
    }

    private static String proofFrom(ResultActions resolved) throws Exception {
        String body = resolved.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.joinProof");
    }

    /** 진짜 코드와 한 글자만 다른 코드. 코드 문자표(0·O·1·I 없음) 안에서 고른다. */
    private static String wrongCode(String code) {
        char first = code.charAt(0) == 'A' ? 'B' : 'A';
        return first + code.substring(1);
    }

    private int memberCount(String roomId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_member WHERE room_id = ?", Integer.class, roomId);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor loggedIn(AuthenticatedUser user) {
        return authentication(new TestingAuthenticationToken(user, null, "ROLE_USER"));
    }
}
