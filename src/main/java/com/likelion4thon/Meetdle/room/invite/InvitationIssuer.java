package com.likelion4thon.Meetdle.room.invite;

import com.likelion4thon.Meetdle.room.domain.NewInvitation;
import com.likelion4thon.Meetdle.room.repository.RoomInvitationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 방 하나에 초대(6자리 코드 + 43자 링크 토큰)를 만들어 저장한다.
 * 호출한 쪽(RoomService)의 트랜잭션 안에서 실행되므로, 방 저장이 실패하면 초대도 같이 취소된다.
 */
@Component
public class InvitationIssuer {

    /** 명세 A05·D08: 링크·코드 유효기간 7일. */
    static final Duration VALIDITY = Duration.ofDays(7);
    /** 코드가 이미 쓰인 경우 다시 뽑는 최대 횟수. 32^6 ≈ 10억 가지라서 실제로 2번 넘게 겹칠 일은 거의 없다. */
    static final int MAX_CODE_ATTEMPTS = 5;

    private final InviteCodeGenerator generator;
    private final InviteCrypto crypto;
    private final RoomInvitationRepository invitationRepository;
    private final String frontOrigin;

    public InvitationIssuer(
            InviteCodeGenerator generator,
            InviteCrypto crypto,
            RoomInvitationRepository invitationRepository,
            @Value("${meetdle.frontend.origin:http://localhost:5173}") String frontOrigin
    ) {
        this.generator = generator;
        this.crypto = crypto;
        this.invitationRepository = invitationRepository;
        this.frontOrigin = frontOrigin.endsWith("/") ? frontOrigin.substring(0, frontOrigin.length() - 1) : frontOrigin;
    }

    public IssuedInvite issue(UUID roomId, Instant now) {
        String code = uniqueCode();
        String token = generator.nextToken();
        Instant expiresAt = now.plus(VALIDITY);

        invitationRepository.insert(new NewInvitation(
                UUID.randomUUID(), roomId,
                crypto.lookupHash(InviteCrypto.Kind.TOKEN, token), crypto.encrypt(token),
                crypto.lookupHash(InviteCrypto.Kind.CODE, code), crypto.encrypt(code),
                expiresAt));
        return new IssuedInvite(code, inviteUrl(token), expiresAt);
    }

    /**
     * 프론트의 초대 화면 주소. 예: http://localhost:5173/invite/Ab3...(43자)
     * TODO(프론트 확인): 경로 "/invite/{token}"은 추측이다. 프론트 라우트가 정해지면 맞춘다.
     */
    public URI inviteUrl(String token) {
        return URI.create(frontOrigin + "/invite/" + token);
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = generator.nextCode();
            if (!invitationRepository.existsByCodeLookupHash(crypto.lookupHash(InviteCrypto.Kind.CODE, code))) {
                return code;
            }
        }
        throw new IllegalStateException("초대 코드를 " + MAX_CODE_ATTEMPTS + "번 연속으로 만들지 못했어요");
    }

    /** 방장에게 돌려줄 초대 원문. DB에는 암호화된 값만 있다. */
    public record IssuedInvite(String code, URI inviteUrl, Instant expiresAt) {}
}
