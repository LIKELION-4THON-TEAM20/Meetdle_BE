package com.likelion4thon.Meetdle.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "서버 상태")
@RestController
class HealthController {
    @Operation(summary = "서버 응답 확인",
            description = "로그인 없이 호출해 서버의 HTTP 요청 처리 여부 확인",
            responses = {
                @ApiResponse(responseCode = "200", description = "status가 UP인 JSON 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
