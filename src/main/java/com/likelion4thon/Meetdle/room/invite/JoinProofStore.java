package com.likelion4thon.Meetdle.room.invite;

import com.likelion4thon.Meetdle.room.domain.InvitationRow;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 가입 증표(joinProof) 보관함. ERD: "가입 증표는 서버 세션에 묶는다".
 *
 * 🎟️ 놀이공원 손목밴드 비유: A25에서 코드를 맞히면 손목밴드(증표)를 채워주고,
 * A07 가입 입구에서 "이 손목밴드, 이 브라우저(세션)에서 받은 거 맞아? 이 방 거 맞아? 10분 안 지났어?"를 확인한다.
 * 증표는 세션에만 저장되므로 다른 브라우저로 복사해 가도 쓸 수 없다.
 */
@Component
public class JoinProofStore {

    static final String SESSION_KEY = "meetdle.room.joinProofs";
    /** 명세 A25: 증표는 10분 또는 초대 만료 중 이른 시점까지 유효. */
    static final Duration VALIDITY = Duration.ofMinutes(10);

    private final InviteCodeGenerator generator;

    public JoinProofStore(InviteCodeGenerator generator) {
        this.generator = generator;
    }

    public IssuedProof issue(HttpSession session, UUID userId, InvitationRow invitation, Instant now) {
        Instant tenMinutesLater = now.plus(VALIDITY);
        Instant expiresAt = tenMinutesLater.isBefore(invitation.expiresAt()) ? tenMinutesLater : invitation.expiresAt();
        String proof = generator.nextToken();

        Map<String, JoinGrant> grants = grants(session);
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt())); // 지난 증표 정리
        grants.put(proof, new JoinGrant(userId, invitation.roomId(), invitation.invitationId(), expiresAt));
        session.setAttribute(SESSION_KEY, grants); // 세션 저장소가 바뀐 걸 알도록 다시 넣는다
        return new IssuedProof(proof, expiresAt);
    }

    /** 세션이 없거나 이 세션에서 발급한 증표가 아니면 비어 있다. */
    public Optional<JoinGrant> find(HttpSession session, String proof) {
        if (session == null || proof == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(grants(session).get(proof));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JoinGrant> grants(HttpSession session) {
        Object stored = session.getAttribute(SESSION_KEY);
        return stored instanceof Map<?, ?> map ? (Map<String, JoinGrant>) map : new HashMap<>();
    }

    public record IssuedProof(String proof, Instant expiresAt) {}

    /** 세션에 저장되는 증표 내용. 세션 저장소가 직렬화할 수 있도록 Serializable. */
    public record JoinGrant(UUID userId, UUID roomId, UUID invitationId, Instant expiresAt) implements Serializable {

        public boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
