package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.common.time.ServiceZone;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.planning.MeetingQuery;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.dto.UpdateRoomRequest;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RoomLifecycleService {
    private final RoomRepository rooms;
    private final RoomService roomService;
    private final MeetingQuery meetings;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RoomLifecycleService(RoomRepository rooms, RoomService roomService, MeetingQuery meetings,
                                JdbcTemplate jdbc, Clock clock) {
        this.rooms = rooms;
        this.roomService = roomService;
        this.meetings = meetings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void update(AuthenticatedUser user, UUID roomId, UpdateRoomRequest request) {
        var room = lockMember(user, roomId, true);
        if (room.revision() != request.expectedRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ROOM", "방 상태를 다시 확인해주세요.",
                    Map.of("currentRevision", room.revision()));
        }
        String title = roomService.normalizeTitle(request.title());
        roomService.validateDates(request.dates().from(), request.dates().through());
        if (request.capacity() < rooms.countMembers(roomId)) {
            throw ApiException.invalidRequest("capacity", "현재 참여 인원보다 작을 수 없어요");
        }
        String id = roomId.toString();
        jdbc.update("UPDATE room SET current_selection_id = NULL WHERE room_id = ?", id);
        jdbc.update("DELETE FROM meeting_selection WHERE room_id = ?", id);
        jdbc.update("DELETE FROM region_candidate WHERE options_id IN (SELECT options_id FROM planning_options WHERE room_id = ?)", id);
        jdbc.update("DELETE FROM slot_option WHERE options_id IN (SELECT options_id FROM planning_options WHERE room_id = ?)", id);
        jdbc.update("DELETE FROM planning_options WHERE room_id = ?", id);
        jdbc.update("DELETE FROM member_availability WHERE participant_id IN (SELECT participant_id FROM room_member WHERE room_id = ?)", id);
        jdbc.update("""
                INSERT INTO member_input (participant_id)
                SELECT m.participant_id FROM room_member m WHERE m.room_id = ?
                    AND NOT EXISTS (SELECT 1 FROM member_input i WHERE i.participant_id = m.participant_id)
                """, id);
        jdbc.update("""
                UPDATE member_input SET origin_station_id = NULL, destination_station_id = NULL,
                    completed_at = NULL, input_revision = input_revision + 1
                WHERE participant_id IN (SELECT participant_id FROM room_member WHERE room_id = ?)
                """, id);
        jdbc.update("""
                UPDATE room SET title = ?, starts_on = ?, ends_on = ?, capacity = ?,
                    phase = 'WAITING_FOR_DEPARTURE', started_at = NULL, inputs_closed_at = NULL,
                    input_version = input_version + 1, revision = revision + 1
                WHERE room_id = ?
                """, title, request.dates().from(), request.dates().through(), request.capacity(), id);
        jdbc.update("UPDATE room_member SET hidden_at = NULL WHERE room_id = ?", id);
        // Previous planning responses must never restore a discarded selection on a retry.
        jdbc.update("""
                DELETE FROM command_dedup WHERE request_path LIKE ?
                    AND request_path <> ? AND request_path <> ?
                """, "/api/rooms/" + id + "/%", "/api/rooms/" + id + "/settings", "/api/rooms/" + id + "/start");
    }

    public void start(AuthenticatedUser user, UUID roomId, long expectedRevision) {
        var room = lockMember(user, roomId, true);
        if (room.phase() != RoomPhase.WAITING_FOR_DEPARTURE) {
            throw ApiException.of(HttpStatus.CONFLICT, "WRONG_PHASE", "조건 입력 단계에서 시작할 수 있어요.");
        }
        boolean started = jdbc.queryForObject("SELECT started_at IS NOT NULL FROM room WHERE room_id = ?",
                Boolean.class, roomId.toString());
        if (started) {
            return;
        }
        if (room.revision() != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ROOM", "방 상태를 다시 확인해주세요.",
                    Map.of("currentRevision", room.revision()));
        }
        int count = rooms.countMembers(roomId);
        if (count < 2) {
            throw ApiException.of(HttpStatus.CONFLICT, "NOT_ENOUGH_MEMBERS", "2명 이상 참여해야 시작할 수 있어요.");
        }
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("""
                UPDATE room SET capacity = ?, started_at = ?, join_closed_at = COALESCE(join_closed_at, ?),
                    revision = revision + 1 WHERE room_id = ?
                """, count, now, now, roomId.toString());
    }

    public void hide(AuthenticatedUser user, UUID roomId) {
        var room = lockMember(user, roomId, false);
        var meeting = meetings.find(roomId);
        boolean past = room.phase() == RoomPhase.ENDED
                || meeting.map(value -> !value.endsAt().isAfter(clock.instant())).orElseGet(() ->
                    room.endsOn().isBefore(LocalDate.now(clock.withZone(ServiceZone.SEOUL))));
        if (!past) {
            throw ApiException.of(HttpStatus.CONFLICT, "UPCOMING_ROOM", "지난 약속만 삭제할 수 있어요.");
        }
        jdbc.update("UPDATE room_member SET hidden_at = COALESCE(hidden_at, ?) WHERE room_id = ? AND user_id = ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), roomId.toString(), user.userId().toString());
    }

    private LockedRoomRow lockMember(AuthenticatedUser user, UUID roomId, boolean hostOnly) {
        var room = rooms.lockRoom(roomId).orElseThrow(() ->
                ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        var member = rooms.findMember(roomId, user.userId()).orElseThrow(() ->
                ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 회원만 사용할 수 있어요."));
        if (hostOnly && member.role() != RoomMember.Role.HOST) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 사용할 수 있어요.");
        }
        return room;
    }
}
