package com.likelion4thon.Meetdle.room.invite;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 초대 코드와 링크 토큰을 랜덤으로 뽑는다.
 * - 코드: 6자리 영문 대문자/숫자. 사람이 직접 치므로 헷갈리는 0·O·1·I는 뺐다. 예: "K7P2QX"
 * - 토큰: 32바이트 랜덤 → base64url 43자. 링크에 들어간다.
 */
@Component
public class InviteCodeGenerator {

    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String nextCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    public String nextToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
