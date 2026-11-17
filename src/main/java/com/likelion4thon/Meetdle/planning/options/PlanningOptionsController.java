package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.common.dedup.Idempotency;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.planning.options.dto.PlanningOptionsRequest;
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
    @PostMapping("/api/rooms/{roomId}/planning-options")
    public ResponseEntity<String> calculate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID roomId,
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
