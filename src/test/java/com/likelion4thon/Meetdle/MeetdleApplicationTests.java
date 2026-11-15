package com.likelion4thon.Meetdle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class MeetdleApplicationTests {

	private static final String ROOM_ID = "10000000-0000-0000-0000-000000000001";
	private static final String HOST_USER_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void appliesBaselineMigrationsAndLoadsSharedFixture() {
		assertEquals("WAITING_FOR_DEPARTURE", jdbc.queryForObject(
				"SELECT phase FROM room WHERE room_id = ?", String.class, ROOM_ID));
		assertEquals(4, jdbc.queryForObject(
				"SELECT capacity FROM room WHERE room_id = ?", Integer.class, ROOM_ID));
		assertEquals(3, jdbc.queryForObject(
				"SELECT COUNT(*) FROM room_member WHERE room_id = ?", Integer.class, ROOM_ID));
	}

	@Test
	void rejectsCapacityOutsideSupportedRange() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE room SET capacity = ? WHERE room_id = ?", 7, ROOM_ID));
	}

	@Test
	void rejectsDuplicateMembership() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO room_member (participant_id, room_id, user_id, nickname, role) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID().toString(), ROOM_ID, HOST_USER_ID, "duplicate", "MEMBER"));
	}

	@Test
	void rejectsMembershipWithoutAnExistingUser() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO room_member (participant_id, room_id, user_id, nickname, role) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID().toString(), ROOM_ID, UUID.randomUUID().toString(), "unknown", "MEMBER"));
	}

}
