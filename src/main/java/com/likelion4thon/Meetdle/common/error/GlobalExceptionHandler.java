package com.likelion4thon.Meetdle.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 컨트롤러에서 터진 예외를 ErrorBody 모양의 JSON 응답으로 바꿔준다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 서비스가 직접 던진 업무 규칙 위반. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorBody(e.code(), e.getMessage(), e.details()));
    }

    /** @Valid 검사 실패. 예: capacity가 7이면 {"capacity": "2 이상 6 이하여야 합니다"} */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(new ErrorBody("INVALID_REQUEST", "요청 값이 올바르지 않아요.", details));
    }

    /** JSON 문법이 깨졌거나 날짜 형식이 틀린 경우. 예: "from": "11월 20일" */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of("INVALID_REQUEST", "요청 본문을 읽을 수 없어요. JSON 형식을 확인해 주세요."));
    }
}
