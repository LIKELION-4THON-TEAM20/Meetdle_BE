package com.likelion4thon.Meetdle.room.repository;

import com.likelion4thon.Meetdle.room.domain.InvitationRow;
import com.likelion4thon.Meetdle.room.domain.NewInvitation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** room_invitation 테이블 담당. */
@Repository
public class RoomInvitationRepository {

    private final JdbcClient jdbc;

    public RoomInvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewInvitation invitation) {
        jdbc.sql("""
                        INSERT INTO room_invitation (invitation_id, room_id, token_lookup_hash, encrypted_token,
                                                     code_lookup_hash, encrypted_code, expires_at)
                        VALUES (:invitationId, :roomId, :tokenLookupHash, :encryptedToken,
                                :codeLookupHash, :encryptedCode, :expiresAt)
                        """)
                .param("invitationId", invitation.invitationId().toString())
                .param("roomId", invitation.roomId().toString())
                .param("tokenLookupHash", invitation.tokenLookupHash())
                .param("encryptedToken", invitation.encryptedToken())
                .param("codeLookupHash", invitation.codeLookupHash())
                .param("encryptedCode", invitation.encryptedCode())
                // DATETIME은 시간대 정보가 없어서, 팀 규칙대로 UTC 기준 시각으로 바꿔 저장한다.
                .param("expiresAt", LocalDateTime.ofInstant(invitation.expiresAt(), ZoneOffset.UTC))
                .update();
    }

    /** 링크 토큰의 조회값으로 초대를 찾는다 (A06). */
    public Optional<InvitationRow> findByTokenLookupHash(byte[] tokenLookupHash) {
        return jdbc.sql("SELECT invitation_id, room_id, expires_at FROM room_invitation WHERE token_lookup_hash = :hash")
                .param("hash", tokenLookupHash)
                .query(RoomInvitationRepository::mapRow)
                .optional();
    }

    /** 6자리 코드의 조회값으로 초대를 찾는다 (A25). */
    public Optional<InvitationRow> findByCodeLookupHash(byte[] codeLookupHash) {
        return jdbc.sql("SELECT invitation_id, room_id, expires_at FROM room_invitation WHERE code_lookup_hash = :hash")
                .param("hash", codeLookupHash)
                .query(RoomInvitationRepository::mapRow)
                .optional();
    }

    private static InvitationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InvitationRow(
                UUID.fromString(rs.getString("invitation_id")),
                UUID.fromString(rs.getString("room_id")),
                rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /** 만료된 코드도 포함해서 이미 쓰인 코드인지 확인한다 (ERD: 만료 코드도 유일성 유지). */
    public boolean existsByCodeLookupHash(byte[] codeLookupHash) {
        return jdbc.sql("SELECT COUNT(*) FROM room_invitation WHERE code_lookup_hash = :hash")
                .param("hash", codeLookupHash)
                .query(Integer.class)
                .single() > 0;
    }
}
