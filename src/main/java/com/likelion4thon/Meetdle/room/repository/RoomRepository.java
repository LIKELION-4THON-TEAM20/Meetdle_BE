package com.likelion4thon.Meetdle.room.repository;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.domain.MyRoomRow;
import com.likelion4thon.Meetdle.room.domain.NewRoom;
import com.likelion4thon.Meetdle.room.domain.NewRoomMember;
import com.likelion4thon.Meetdle.room.domain.RoomPreviewRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * room, room_member 테이블에 SQL을 직접 보내는 창고 관리자.
 * 팀 공통 설정(V001~V003)이 JDBC 방식이라 JPA 대신 JdbcClient를 쓴다.
 */
@Repository
public class RoomRepository {

    private final JdbcClient jdbc;

    public RoomRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** phase(WAITING_FOR_DEPARTURE), revision(0), input_version(0)은 DB 기본값이 채운다. */
    public void insertRoom(NewRoom room) {
        jdbc.sql("""
                        INSERT INTO room (room_id, title, starts_on, ends_on, capacity)
                        VALUES (:roomId, :title, :startsOn, :endsOn, :capacity)
                        """)
                .param("roomId", room.roomId().toString())
                .param("title", room.title())
                .param("startsOn", room.startsOn())
                .param("endsOn", room.endsOn())
                .param("capacity", room.capacity())
                .update();
    }

    /** 미리보기용 방 정보 + 방장 닉네임 + 현재 인원 수를 한 번에 읽는다. */
    public Optional<RoomPreviewRow> findPreview(UUID roomId) {
        return jdbc.sql("""
                        SELECT r.room_id, r.title, r.starts_on, r.ends_on, r.capacity, r.join_closed_at,
                               (SELECT m.nickname FROM room_member m
                                 WHERE m.room_id = r.room_id AND m.role = 'HOST') AS host_nickname,
                               (SELECT COUNT(*) FROM room_member m WHERE m.room_id = r.room_id) AS joined_count
                          FROM room r
                         WHERE r.room_id = :roomId
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> new RoomPreviewRow(
                        UUID.fromString(rs.getString("room_id")),
                        rs.getString("title"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class),
                        rs.getInt("capacity"),
                        toInstant(rs.getObject("join_closed_at", LocalDateTime.class)),
                        rs.getString("host_nickname"),
                        rs.getInt("joined_count")))
                .optional();
    }

    /**
     * 방 행을 잠근다 (SELECT ... FOR UPDATE). 반드시 @Transactional 안에서 불러야 한다.
     * 예: 정원 4명 방에 3명이 있을 때 두 사람이 동시에 가입 버튼을 눌러도,
     * 한 명이 잠금을 쥐고 4번째로 들어간 뒤에야 다른 한 명이 "꽉 찼네"를 확인한다.
     */
    public Optional<LockedRoomRow> lockRoom(UUID roomId) {
        return jdbc.sql("""
                        SELECT room_id, capacity, starts_on, ends_on, join_closed_at, inputs_closed_at,
                               phase, input_version, current_selection_id
                          FROM room WHERE room_id = :roomId FOR UPDATE
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> new LockedRoomRow(
                        UUID.fromString(rs.getString("room_id")),
                        rs.getInt("capacity"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class),
                        toInstant(rs.getObject("join_closed_at", LocalDateTime.class)),
                        toInstant(rs.getObject("inputs_closed_at", LocalDateTime.class)),
                        RoomPhase.valueOf(rs.getString("phase")),
                        rs.getLong("input_version"),
                        rs.getString("current_selection_id") == null ? null
                                : UUID.fromString(rs.getString("current_selection_id"))))
                .optional();
    }

    /**
     * 내가 회원인 방 전부 (A24). 한 사람이 속한 방은 많아야 수십 개라서 한 번에 읽고 정렬은 서비스에서 한다.
     * 확정 시각(meetingAt, endsAt)은 지금 확정된 선택(current_selection_id)의 일시 칸에서 읽는다. 확정 전이면 null.
     */
    public List<MyRoomRow> findRoomsOfUser(UUID userId) {
        return jdbc.sql("""
                        SELECT r.room_id, r.title, r.starts_on, r.ends_on, r.capacity, r.phase,
                               (SELECT COUNT(*) FROM room_member c WHERE c.room_id = r.room_id) AS joined_count,
                               so.starts_at, so.ends_at
                          FROM room r
                          JOIN room_member m ON m.room_id = r.room_id
                          LEFT JOIN meeting_selection ms
                                 ON ms.room_id = r.room_id AND ms.selection_id = r.current_selection_id
                          LEFT JOIN slot_option so
                                 ON so.options_id = ms.options_id AND so.slot_id = ms.slot_id
                         WHERE m.user_id = :userId
                        """)
                .param("userId", userId.toString())
                .query((rs, rowNum) -> new MyRoomRow(
                        UUID.fromString(rs.getString("room_id")),
                        rs.getString("title"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class),
                        rs.getInt("capacity"),
                        rs.getInt("joined_count"),
                        RoomPhase.valueOf(rs.getString("phase")),
                        toInstant(rs.getObject("starts_at", LocalDateTime.class)),
                        toInstant(rs.getObject("ends_at", LocalDateTime.class))))
                .list();
    }

    /** 이 방에서 이 사용자의 참가자 ID. 회원이 아니면 비어 있다. */
    public Optional<UUID> findParticipantId(UUID roomId, UUID userId) {
        return jdbc.sql("SELECT participant_id FROM room_member WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId.toString())
                .param("userId", userId.toString())
                .query(String.class)
                .optional()
                .map(UUID::fromString);
    }

    public boolean exists(UUID roomId) {
        return jdbc.sql("SELECT COUNT(*) FROM room WHERE room_id = :roomId")
                .param("roomId", roomId.toString())
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * 누군가의 입력이 실제로 바뀌었을 때 방의 입력 버전과 revision을 1씩 올린다.
     * 예: input_version 7 → 8. 방장이 A11 계산할 때 "내가 본 버전이 최신인지" 확인하는 데 쓴다.
     */
    public void bumpInputVersion(UUID roomId) {
        jdbc.sql("UPDATE room SET input_version = input_version + 1, revision = revision + 1 WHERE room_id = :roomId")
                .param("roomId", roomId.toString())
                .update();
    }

    /** 이 방에서 이 사용자의 역할(HOST/MEMBER)과 참가자 ID. 회원이 아니면 비어 있다. */
    public Optional<RoomMember> findMember(UUID roomId, UUID userId) {
        return jdbc.sql("SELECT participant_id, role FROM room_member WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId.toString())
                .param("userId", userId.toString())
                .query((rs, rowNum) -> new RoomMember(
                        UUID.fromString(rs.getString("participant_id")),
                        RoomMember.Role.valueOf(rs.getString("role"))))
                .optional();
    }

    /**
     * A11 첫 계산 시작: 가입을 마감하고(join_closed_at) 단계를 OPTIONS_READY로 넘긴다. revision도 1 올린다.
     * 예: 3명 방에서 방장이 계산을 누르면 그 뒤로는 초대 링크로 새로 들어올 수 없다.
     */
    public void startPlanning(UUID roomId, Instant now) {
        jdbc.sql("""
                        UPDATE room SET join_closed_at = :now, phase = :phase, revision = revision + 1
                         WHERE room_id = :roomId
                        """)
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("phase", RoomPhase.OPTIONS_READY.name())
                .param("roomId", roomId.toString())
                .update();
    }

    /**
     * A12 확정·재선택: 포인터를 새 선택으로 바꾸고, 단계를 REGION_CONFIRMED로, revision +1.
     * 첫 확정이면 방 전체 입력도 잠근다 (inputs_closed_at은 처음 한 번만 채워짐).
     */
    public void confirmSelection(UUID roomId, UUID selectionId, Instant now) {
        jdbc.sql("""
                        UPDATE room
                           SET current_selection_id = :selectionId, phase = :phase, revision = revision + 1,
                               inputs_closed_at = COALESCE(inputs_closed_at, :now)
                         WHERE room_id = :roomId
                        """)
                .param("selectionId", selectionId.toString())
                .param("phase", RoomPhase.REGION_CONFIRMED.name())
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("roomId", roomId.toString())
                .update();
    }

    /** A08 방 상태 조회용 방 한 줄 (잠그지 않음). */
    public Optional<RoomStateRow> findStateRow(UUID roomId) {
        return jdbc.sql("""
                        SELECT room_id, title, starts_on, ends_on, capacity, phase, revision, input_version,
                               join_closed_at, inputs_closed_at
                          FROM room WHERE room_id = :roomId
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> new RoomStateRow(
                        UUID.fromString(rs.getString("room_id")),
                        rs.getString("title"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class),
                        rs.getInt("capacity"),
                        RoomPhase.valueOf(rs.getString("phase")),
                        rs.getLong("revision"),
                        rs.getLong("input_version"),
                        toInstant(rs.getObject("join_closed_at", LocalDateTime.class)),
                        toInstant(rs.getObject("inputs_closed_at", LocalDateTime.class))))
                .optional();
    }

    /** A08 회원 목록 + 각자 입력 완료 여부. 들어온 순서대로. 예: [방장(완료), 참가자(입력 중)] */
    public List<MemberStatusRow> findMemberStatuses(UUID roomId) {
        return jdbc.sql("""
                        SELECT m.participant_id, m.user_id, m.nickname, m.role,
                               CASE WHEN i.completed_at IS NULL THEN FALSE ELSE TRUE END AS input_complete
                          FROM room_member m
                          LEFT JOIN member_input i ON i.participant_id = m.participant_id
                         WHERE m.room_id = :roomId
                         ORDER BY m.joined_at, m.participant_id
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> new MemberStatusRow(
                        UUID.fromString(rs.getString("participant_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("nickname"),
                        RoomMember.Role.valueOf(rs.getString("role")),
                        rs.getBoolean("input_complete")))
                .list();
    }

    public record RoomStateRow(UUID roomId, String title, LocalDate startsOn, LocalDate endsOn, int capacity,
                               RoomPhase phase, long revision, long inputVersion,
                               Instant joinClosedAt, Instant inputsClosedAt) {}

    public record MemberStatusRow(UUID participantId, UUID userId, String nickname, RoomMember.Role role,
                                  boolean inputComplete) {}

    public int countMembers(UUID roomId) {
        return jdbc.sql("SELECT COUNT(*) FROM room_member WHERE room_id = :roomId")
                .param("roomId", roomId.toString())
                .query(Integer.class)
                .single();
    }

    public boolean isMember(UUID roomId, UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM room_member WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId.toString())
                .param("userId", userId.toString())
                .query(Integer.class)
                .single() > 0;
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    /** decision_status(PENDING)는 DB 기본값이 채운다. */
    public void insertMember(NewRoomMember member) {
        jdbc.sql("""
                        INSERT INTO room_member (participant_id, room_id, user_id, nickname, role)
                        VALUES (:participantId, :roomId, :userId, :nickname, :role)
                        """)
                .param("participantId", member.participantId().toString())
                .param("roomId", member.roomId().toString())
                .param("userId", member.userId().toString())
                .param("nickname", member.nickname())
                .param("role", member.role().name())
                .update();
    }
}
