package com.likelion4thon.Meetdle.room.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.MyInput;
import com.likelion4thon.Meetdle.room.dto.SaveMyInputRequest;
import com.likelion4thon.Meetdle.room.service.MemberInputService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SC-04 시간·출발역·목적지 입력 화면이 부르는 API. */
@Tag(name = "참가자 입력")
@RestController
public class MemberInputController {

    private final MemberInputService memberInputService;

    public MemberInputController(MemberInputService memberInputService) {
        this.memberInputService = memberInputService;
    }

    /** A10 PUT /api/rooms/{roomId}/inputs/me → 200 MyInput */
    @Operation(summary = "내 가능 시간과 이동 정보 저장",
            description = "참가자가 출발역·목적지역과 가능 시간 목록 전체를 교체. 최초 저장은 inputRevision에 0 사용",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "200", description = "저장된 입력과 새 입력 버전 반환", useReturnTypeSchema = true)
            })
    @PutMapping("/api/rooms/{roomId}/inputs/me")
    public MyInput saveMyInput(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @Valid @RequestBody SaveMyInputRequest request
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return memberInputService.saveMyInput(user, roomId, request);
    }
}
