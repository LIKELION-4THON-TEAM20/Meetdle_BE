package com.likelion4thon.Meetdle.planning.options;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.dedup.Idempotency;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.planning.options.dto.PlanningOptionsRequest;
import com.likelion4thon.Meetdle.planning.options.dto.PlanningOptionsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SC-05/06 방장이 "추천 받기"를 누르면 부르는 API. */
@Tag(name = "약속 추천")
@RestController
public class PlanningOptionsController {

    private final PlanningOptionsService planningOptionsService;

    public PlanningOptionsController(PlanningOptionsService planningOptionsService) {
        this.planningOptionsService = planningOptionsService;
    }

    /**
     * A11 POST /api/rooms/{roomId}/planning-options → 201 새로 계산 / 200 같은 버전 재사용
     * Idempotency-Key 헤더 필수. 같은 키+다른 본문 → 409 KEY_REUSED
     */
    @Operation(summary = "일시·지역 추천 계산",
            description = "방장이 방 상태의 inputVersion을 보내 참가자 입력을 기준으로 일시·지역 후보 생성. "
                    + "같은 inputVersion으로 이미 생성된 경우 저장된 후보 조회, 다른 inputVersion이면 409 STALE_INPUT. "
                    + "같은 Idempotency-Key로 다시 보내면 처음 응답을 그대로 반환하고, 같은 키로 다른 본문을 보내면 409 KEY_REUSED",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "201", description = "새로 생성한 추천 후보 반환",
                        content = @Content(mediaType = "application/json",
                                schema = @Schema(implementation = PlanningOptionsResponse.class))),
                @ApiResponse(responseCode = "200", description = "이미 저장된 추천 후보 반환",
                        content = @Content(mediaType = "application/json",
                                schema = @Schema(implementation = PlanningOptionsResponse.class)))
            })
    @PostMapping("/api/rooms/{roomId}/planning-options")
    public ResponseEntity<String> calculate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @Parameter(description = "요청 중복 처리를 막는 UUID. 새 요청마다 발급하고 같은 요청을 재시도할 때 재사용",
                    required = true, schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(value = Idempotency.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PlanningOptionsRequest request
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        UUID key = Idempotency.parseKey(idempotencyKey);
        return Idempotency.toEntity(planningOptionsService.calculate(user, roomId, request, key));
    }
}
