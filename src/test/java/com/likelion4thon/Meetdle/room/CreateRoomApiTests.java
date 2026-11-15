package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.matchesPattern;

import com.jayway.jsonpath.JsonPath;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.room.invite.InviteCrypto;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * A05 POST /api/rooms 테스트.
 * 진짜 카카오 로그인은 아직 없으므로(BE B 담당), 테스트에서만 "로그인한 방장"을 흉내 낸다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class CreateRoomApiTests {

    /** base-room.sql에 이미 방 1개가 들어 있다. */
    private static final int FIXTURE_ROOM_COUNT = 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InviteCrypto inviteCrypto;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(BoundaryFixtures.NOW);
    }

    @Test
    void createsRoomAndHostInOneRequest() throws Exception {
        String response = mockMvc.perform(createRoom("""
                        {"title":"  금요 저녁  ","dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":4}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/rooms/[0-9a-f-]{36}/state")))
                .andExpect(jsonPath("$.roomId").isString())
                .andReturn().getResponse().getContentAsString();
        String roomId = JsonPath.read(response, "$.roomId");

        Map<String, Object> room = jdbc.queryForMap("SELECT * FROM room WHERE room_id = ?", roomId);
        assertThat(room.get("TITLE")).isEqualTo("금요 저녁");
        assertThat(room.get("CAPACITY")).isEqualTo(4);
        assertThat(room.get("PHASE")).isEqualTo("WAITING_FOR_DEPARTURE");
        assertThat(room.get("STARTS_ON").toString()).isEqualTo("2030-01-10");
        assertThat(room.get("ENDS_ON").toString()).isEqualTo("2030-01-12");

        Map<String, Object> host = jdbc.queryForMap("SELECT * FROM room_member WHERE room_id = ?", roomId);
        assertThat(host.get("USER_ID")).isEqualTo(BoundaryFixtures.HOST.userId().toString());
        assertThat(host.get("ROLE")).isEqualTo("HOST");
        assertThat(host.get("NICKNAME")).isEqualTo("방장");
        assertThat(host.get("DECISION_STATUS")).isEqualTo("PENDING");
    }

    @Test
    void issuesSixCharacterCodeAndLinkValidForSevenDays() throws Exception {
        String response = mockMvc.perform(createRoom("""
                        {"dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":4}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invite.code").value(matchesPattern("[A-HJ-NP-Z2-9]{6}")))
                .andExpect(jsonPath("$.invite.inviteUrl").value(
                        matchesPattern("http://localhost:5173/invite/[A-Za-z0-9_-]{43}")))
                // 고정 시계 2030-01-01T00:00Z + 7일
                .andExpect(jsonPath("$.invite.expiresAt").value("2030-01-08T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        String roomId = JsonPath.read(response, "$.roomId");
        String code = JsonPath.read(response, "$.invite.code");
        String inviteUrl = JsonPath.read(response, "$.invite.inviteUrl");
        String token = inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1);

        Map<String, Object> invitation = jdbc.queryForMap("SELECT * FROM room_invitation WHERE room_id = ?", roomId);
        assertThat(invitation.get("EXPIRES_AT").toString()).startsWith("2030-01-08 00:00:00");

        // 원문은 DB에 그대로 없고, 조회값으로 찾을 수 있으며, 복호화하면 원문이 나온다.
        byte[] encryptedCode = (byte[]) invitation.get("ENCRYPTED_CODE");
        byte[] encryptedToken = (byte[]) invitation.get("ENCRYPTED_TOKEN");
        assertThat(new String(encryptedCode, StandardCharsets.ISO_8859_1)).doesNotContain(code);
        assertThat(inviteCrypto.decrypt(encryptedCode)).isEqualTo(code);
        assertThat(inviteCrypto.decrypt(encryptedToken)).isEqualTo(token);
        assertThat((byte[]) invitation.get("CODE_LOOKUP_HASH"))
                .isEqualTo(inviteCrypto.lookupHash(InviteCrypto.Kind.CODE, code));
        assertThat((byte[]) invitation.get("TOKEN_LOOKUP_HASH"))
                .isEqualTo(inviteCrypto.lookupHash(InviteCrypto.Kind.TOKEN, token));
    }

    @Test
    void titleIsOptional() throws Exception {
        String response = mockMvc.perform(createRoom("""
                        {"dates":{"from":"2030-01-10","through":"2030-01-10"},"capacity":2}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String roomId = JsonPath.read(response, "$.roomId");

        assertThat(jdbc.queryForObject("SELECT title FROM room WHERE room_id = ?", String.class, roomId)).isNull();
    }

    @Test
    void allowsSevenDaysButRejectsEight() throws Exception {
        mockMvc.perform(createRoom("""
                        {"dates":{"from":"2030-01-10","through":"2030-01-16"},"capacity":6}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(createRoom("""
                        {"dates":{"from":"2030-01-10","through":"2030-01-17"},"capacity":6}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.dates").exists());
    }

    @Test
    void rejectsCapacityOutsideTwoToSix() throws Exception {
        for (int capacity : new int[] {1, 7}) {
            mockMvc.perform(createRoom("""
                            {"dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":%d}
                            """.formatted(capacity)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.details.capacity").exists());
        }
        assertRoomCountUnchanged();
    }

    @Test
    void rejectsReversedDates() throws Exception {
        mockMvc.perform(createRoom("""
                        {"dates":{"from":"2030-01-12","through":"2030-01-10"},"capacity":4}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.dates").exists());
        assertRoomCountUnchanged();
    }

    @Test
    void rejectsPastDates() throws Exception {
        mockMvc.perform(createRoom("""
                        {"dates":{"from":"2020-01-10","through":"2020-01-12"},"capacity":4}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.dates").exists());
        assertRoomCountUnchanged();
    }

    @Test
    void rejectsBlankOrTooLongTitle() throws Exception {
        for (String title : new String[] {"   ", "가".repeat(51)}) {
            mockMvc.perform(createRoom("""
                            {"title":"%s","dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":4}
                            """.formatted(title)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.title").exists());
        }
        assertRoomCountUnchanged();
    }

    @Test
    void rejectsMissingFieldsAndBrokenJson() throws Exception {
        mockMvc.perform(createRoom("""
                        {"title":"날짜 없음","capacity":4}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.dates").exists());

        mockMvc.perform(createRoom("""
                        {"dates":{"from":"11월 10일","through":"2030-01-12"},"capacity":4}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertRoomCountUnchanged();
    }

    @Test
    void rejectsAnonymousCaller() throws Exception {
        mockMvc.perform(post("/api/rooms").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":{"from":"2030-01-10","through":"2030-01-12"},"capacity":4}
                                """))
                .andExpect(status().is4xxClientError());
        assertRoomCountUnchanged();
    }

    private org.springframework.test.web.servlet.RequestBuilder createRoom(String json) {
        return post("/api/rooms")
                .with(loggedIn(BoundaryFixtures.HOST))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /** 세션 로그인 대신, Security Context에 AuthenticatedUser를 직접 넣는다. */
    private static RequestPostProcessor loggedIn(AuthenticatedUser user) {
        return authentication(new TestingAuthenticationToken(user, null, "ROLE_USER"));
    }

    private void assertRoomCountUnchanged() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room", Integer.class)).isEqualTo(FIXTURE_ROOM_COUNT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_invitation", Integer.class)).isZero();
    }
}
