package com.likelion4thon.Meetdle.common.dedup;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.CommandDedup;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 컨트롤러가 번호표(Idempotency-Key)를 다룰 때 쓰는 도우미.
 * 1) 헤더 읽기: 없거나 UUID가 아니면 400 INVALID_REQUEST
 * 2) 본문 지문(SHA-256): 같은 본문이면 같은 지문 → "같은 내용인지" 비교용
 * 3) 응답 만들기: 처음 응답과 다시 주는 응답이 글자 하나까지 같도록 JSON 문자열로 보낸다
 */
@Component
public class Idempotency {

    public static final String HEADER = "Idempotency-Key";

    private final ObjectMapper json;

    public Idempotency(ObjectMapper json) {
        this.json = json;
    }

    public static UUID parseKey(String header) {
        if (header == null || header.isBlank()) {
            throw invalidKey("필수 값이에요");
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw invalidKey("UUID 형식이어야 해요");
        }
    }

    public String hash(Object requestBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(requestBody));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public CommandDedup.Response response(HttpStatus status, URI location, Object body) {
        return new CommandDedup.Response(status.value(), Optional.ofNullable(location),
                Optional.of(json.writeValueAsString(body)));
    }

    public static ResponseEntity<String> toEntity(CommandDedup.Response response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON);
        response.location().ifPresent(uri -> builder.header(HttpHeaders.LOCATION, uri.toString()));
        return builder.body(response.jsonBody().orElse(null));
    }

    private static ApiException invalidKey(String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Idempotency-Key 헤더를 확인해 주세요.",
                Map.of(HEADER, reason));
    }
}
