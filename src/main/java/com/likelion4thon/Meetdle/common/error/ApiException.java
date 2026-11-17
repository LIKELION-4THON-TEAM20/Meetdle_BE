package com.likelion4thon.Meetdle.common.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 서비스에서 "이 요청은 이런 이유로 안 돼"를 알릴 때 던지는 예외.
 * GlobalExceptionHandler가 받아서 ErrorBody JSON으로 바꿔 응답한다.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? null : Map.copyOf(details);
    }

    /** details 없이 상태·코드·메시지만. 예: of(NOT_FOUND, "INVITATION_NOT_FOUND", "초대를 찾을 수 없어요.") */
    public static ApiException of(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, null);
    }

    /** 400 INVALID_REQUEST. field에는 문제가 된 입력 칸 이름을 넣는다. */
    public static ApiException invalidRequest(String field, String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않아요.",
                Map.of(field, reason));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
