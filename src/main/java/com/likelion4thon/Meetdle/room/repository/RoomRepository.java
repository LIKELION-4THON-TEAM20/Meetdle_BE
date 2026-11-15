package com.likelion4thon.Meetdle.room.repository;

import com.likelion4thon.Meetdle.room.domain.NewRoom;
import com.likelion4thon.Meetdle.room.domain.NewRoomMember;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
