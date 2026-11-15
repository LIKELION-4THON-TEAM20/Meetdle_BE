package com.likelion4thon.Meetdle.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 모든 에러 응답의 공통 모양. 예: {"code":"INVALID_REQUEST","message":"...","details":{"capacity":"..."}}
 * details가 없으면 JSON에서 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, Map<String, Object> details) {

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, null);
    }
}
