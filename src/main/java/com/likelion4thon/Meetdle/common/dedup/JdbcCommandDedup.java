package com.likelion4thon.Meetdle.common.dedup;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.CommandDedup;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 중복 요청 막이 (contract.CommandDedup 구현) 🎫
 *
 * 🧾 번호표 비유: 앱이 요청마다 번호표(Idempotency-Key)를 붙여 보낸다.
 * - 처음 보는 번호표 → 일을 하고, 결과(영수증)를 번호표와 함께 24시간 보관
 * - 같은 번호표 + 같은 내용 → 일을 또 하지 않고 보관한 영수증을 그대로 다시 줌
 *   예: 방 만들기 응답이 와이파이 끊김으로 사라져서 앱이 다시 보내도 방은 1개만 생김
 * - 같은 번호표 + 다른 내용 → 409 KEY_REUSED
 *
 * 동시에 같은 번호표가 두 번 오면: 먼저 온 쪽이 "번호표 자리"(빈 행)를 먼저 넣는다.
 * 뒤에 온 쪽은 MySQL에서 그 행이 커밋될 때까지 기다렸다가 중복으로 판정되어 영수증을 받는다.
 * 실패한 요청은 트랜잭션이 되돌려지면서 자리도 사라진다 → 성공 응답만 보관한다(명세).
 */
@Component
public class JdbcCommandDedup implements CommandDedup {

    static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcCommandDedup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Response execute(Key key, String requestHash, Supplier<Response> command) {
        byte[] hash = HexFormat.of().parseHex(requestHash);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 유효기간(24시간)이 지난 번호표는 지우고 새로 쓴다
        jdbc.sql("""
                        DELETE FROM command_dedup
                        WHERE user_id = :userId AND request_path = :path AND idempotency_key = :key
                          AND expires_at <= :now
                        """)
                .param("userId", key.userId().toString())
                .param("path", key.requestPath())
                .param("key", key.idempotencyKey().toString())
                .param("now", utc(now))
                .update();

        try {
            jdbc.sql("""
                            INSERT INTO command_dedup (user_id, request_path, idempotency_key, request_hash, expires_at)
                            VALUES (:userId, :path, :key, :hash, :expiresAt)
                            """)
                    .param("userId", key.userId().toString())
                    .param("path", key.requestPath())
                    .param("key", key.idempotencyKey().toString())
                    .param("hash", hash)
                    .param("expiresAt", utc(now.plus(RETENTION)))
                    .update();
        } catch (DuplicateKeyException alreadyUsed) {
            return replay(key, hash);
        }

        Response response = command.get();
        jdbc.sql("""
                        UPDATE command_dedup
                        SET response_status = :status, response_location = :location, response_json = :json
                        WHERE user_id = :userId AND request_path = :path AND idempotency_key = :key
                        """)
                .param("status", response.statusCode())
                .param("location", response.location().map(URI::toString).orElse(null))
                .param("json", response.jsonBody().orElse(null))
                .param("userId", key.userId().toString())
                .param("path", key.requestPath())
                .param("key", key.idempotencyKey().toString())
                .update();
        return response;
    }

    private Response replay(Key key, byte[] hash) {
        Stored stored = jdbc.sql("""
                        SELECT request_hash, response_status, response_location, response_json
                        FROM command_dedup
                        WHERE user_id = :userId AND request_path = :path AND idempotency_key = :key
                        """)
                .param("userId", key.userId().toString())
                .param("path", key.requestPath())
                .param("key", key.idempotencyKey().toString())
                .query((rs, row) -> new Stored(rs.getBytes("request_hash"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_location"), rs.getString("response_json")))
                .optional()
                .orElseThrow(() -> inProgress());
        if (!MessageDigest.isEqual(hash, stored.hash())) {
            throw ApiException.of(HttpStatus.CONFLICT, "KEY_REUSED",
                    "같은 Idempotency-Key로 다른 내용을 보냈어요. 새 키로 다시 보내 주세요.");
        }
        if (stored.status() == null) {
            throw inProgress();
        }
        return new Response(stored.status(), Optional.ofNullable(stored.location()).map(URI::create),
                Optional.ofNullable(stored.json()));
    }

    /** 같은 번호표 요청이 아직 처리 중일 때 (MySQL에선 기다렸다 판정하므로 거의 안 생김). 코드 이름은 추측. */
    private static ApiException inProgress() {
        return ApiException.of(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "같은 요청을 처리하고 있어요. 잠시 후 다시 시도해 주세요.");
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Stored(byte[] hash, Integer status, String location, String json) {}
}
