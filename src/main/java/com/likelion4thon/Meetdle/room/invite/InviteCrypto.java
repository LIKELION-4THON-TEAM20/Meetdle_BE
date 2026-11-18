package com.likelion4thon.Meetdle.room.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 초대 코드·토큰을 DB에 안전하게 저장하기 위한 도구.
 *
 * - lookupHash: 서버 비밀키로 만든 HMAC 값. 사용자가 코드를 입력하면 같은 방식으로 계산해 DB에서 찾는다.
 *   6자리 코드는 경우의 수가 적어서 그냥 SHA-256만 쓰면 전부 계산해 볼 수 있다. 그래서 비밀키를 섞는다.
 * - encrypt/decrypt: 방장에게 코드를 다시 보여줘야 하므로 원문은 AES-GCM으로 암호화해 저장한다.
 *
 * 비밀키는 코드에 쓰지 않고 환경변수(MEETDLE_INVITE_HMAC_KEY, MEETDLE_INVITE_ENCRYPTION_KEY)로 받는다.
 */
@Component
public class InviteCrypto {

    private static final int AES_KEY_BYTES = 32;
    private static final int MIN_HMAC_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();

    public InviteCrypto(
            @Value("${meetdle.invite.hmac-key}") String hmacKeyBase64,
            @Value("${meetdle.invite.encryption-key}") String encryptionKeyBase64
    ) {
        byte[] hmac = Base64.getDecoder().decode(hmacKeyBase64);
        byte[] aes = Base64.getDecoder().decode(encryptionKeyBase64);
        if (hmac.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException("meetdle.invite.hmac-key는 base64로 32바이트 이상이어야 해요");
        }
        if (aes.length != AES_KEY_BYTES) {
            throw new IllegalStateException("meetdle.invite.encryption-key는 base64로 정확히 32바이트여야 해요");
        }
        this.hmacKey = new SecretKeySpec(hmac, "HmacSHA256");
        this.aesKey = new SecretKeySpec(aes, "AES");
    }

    /** kind를 앞에 붙여서, 같은 글자라도 코드와 토큰의 조회값이 섞이지 않게 한다. */
    public byte[] lookupHash(Kind kind, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update((kind.name() + ":").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("초대 조회값을 만들 수 없어요", e);
        }
    }

    /** 결과 = IV(12바이트) + 암호문·인증태그. 같은 글자를 두 번 암호화해도 결과가 매번 다르다. */
    public byte[] encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("초대 값을 암호화할 수 없어요", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            byte[] iv = Arrays.copyOfRange(stored, 0, IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(stored, IV_BYTES, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("초대 값을 복호화할 수 없어요", e);
        }
    }

    public enum Kind {
        TOKEN, CODE
    }
}
