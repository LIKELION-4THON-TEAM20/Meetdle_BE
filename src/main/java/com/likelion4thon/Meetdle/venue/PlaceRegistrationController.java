package com.likelion4thon.Meetdle.venue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.error.ErrorBody;
import com.likelion4thon.Meetdle.common.logging.RequestOperationLog;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Draft;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Tag(name = "장소 등록과 투표")
@RestController
@RequestMapping("/api/rooms/{roomId}")
class PlaceRegistrationController {
    private final PlaceRegistration registration;
    private final PlaceVoting voting;

    PlaceRegistrationController(PlaceRegistration registration, PlaceVoting voting) {
        this.registration = registration;
        this.voting = voting;
    }

    record AddCandidate(int round, Category category, String pickToken) {}
    record AddedCandidate(UUID candidateId) {}

    @Operation(summary = "내 장소 후보 추가", description = "동일 카테고리 여러 장소 허용. 같은 장소를 다시 보내면 기존 후보 ID 반환")
    @PostMapping("/place-submission/me/candidates")
    AddedCandidate addCandidate(@PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestBody AddCandidate request) {
        if (request.category() == null) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        return new AddedCandidate(registration.addNomination(roomId, user.userId(), request.category(),
                new PlaceRegistration.Nomination(request.round(), request.pickToken())));
    }

    @Operation(summary = "내 장소 후보 한 곳 삭제")
    @DeleteMapping("/place-submission/me/candidates/{candidateId}")
    ResponseEntity<Void> removeCandidate(@PathVariable UUID roomId, @PathVariable UUID candidateId,
            @RequestParam int round, @AuthenticationPrincipal AuthenticatedUser user) {
        registration.removeNomination(roomId, user.userId(), round, candidateId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "일반 결과 최종 확정", description = "방장이 RESULT_READY에서 최신 결과 버전을 확인하고 확정")
    @PutMapping("/result/confirmation")
    ResponseEntity<Void> confirmResult(@PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
                                        @RequestBody PlaceVoting.ConfirmResult request) {
        voting.confirmResult(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "동점 장소 확정",
            description = "방장이 최신 tieDecision을 조회한 뒤 동점 카테고리마다 최종 방문 장소 한 곳 선택",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "동점 장소 확정 완료", content = @Content)
            })
    @PutMapping("/result/selections")
    ResponseEntity<Void> resolveTies(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceVoting.ResolveTies request) {
        voting.resolveTies(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "최종 방문 순서 변경",
            description = "방장이 결과에 확정된 장소 ID 전체를 원하는 방문 순서대로 전송",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "방문 순서 변경 완료", content = @Content)
            })
    @PutMapping("/result/visit-order")
    ResponseEntity<Void> reorderVisit(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceVoting.ReorderVisit request) {
        voting.reorderVisit(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "장소 투표 시작",
            description = "방장이 장소 등록 종료 후 현재 등록 라운드와 투표 마감 시각을 보내 투표 시작",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "투표 시작 완료", content = @Content)
            })
    @PostMapping("/voting")
    ResponseEntity<Void> startVoting(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "요청 중복 처리를 막는 UUID. 새 요청마다 발급하고 같은 요청을 재시도할 때 재사용")
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody PlaceVoting.Start request) {
        voting.start(roomId, user.userId(), key, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "투표 조기 마감 또는 미제출 투표 처리 결정",
            description = "방장이 PROCEED로 마감 전후 제출표를 집계하거나, 마감 후 EXTEND로 시간을 연장. 집계는 제출자 1명 이상 필요. 마감까지 기다리기는 API 호출 없이 모달만 닫음",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "연장 또는 집계 처리 완료", content = @Content)
            })
    @PostMapping("/voting/decision")
    ResponseEntity<Void> decide(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "EXTEND 요청에는 필수인 중복 처리 방지 UUID. 같은 요청 재시도 시 재사용")
            @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestBody PlaceVoting.Decision request) {
        voting.decide(roomId, user.userId(), key, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "장소 후보 재등록 시작",
            description = "복구 단계에서 방장이 현재 라운드와 새 등록 마감 시각을 보내 다음 등록 라운드 생성",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "201", description = "새 등록 라운드 번호와 마감 시각 반환", useReturnTypeSchema = true)
            })
    @PostMapping("/recovery")
    ResponseEntity<PlaceRegistration.Extended> recover(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "요청 중복 처리를 막는 UUID. 새 요청마다 발급하고 같은 요청을 재시도할 때 재사용")
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PlaceVoting.Recollect request) {
        return ResponseEntity.status(201).body(voting.recollect(roomId, user.userId(), key, request));
    }

    @Operation(summary = "내 장소 선호도 임시 저장",
            description = "투표 대상 후보별 선호도 목록 전체 교체. 이후 제출 요청에 저장 응답의 draftRevision 사용",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "200", description = "새 초안 버전과 저장된 선호도 반환", useReturnTypeSchema = true)
            })
    @PutMapping("/preferences/me")
    ResponseEntity<Draft> savePreferences(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceVoting.Edit request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(voting.save(roomId, user.userId(), request));
    }

    @Operation(summary = "내 장소 투표 최종 제출",
            description = "모든 투표 대상 후보의 선호도를 임시 저장한 뒤 같은 라운드와 저장된 초안 버전으로 제출. 제출 후 선호도 수정 불가",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "투표 제출 완료. 응답 본문 없음", content = @Content),
                @ApiResponse(responseCode = "409", description = "초안 버전 불일치, 미평가 후보 존재, 투표 마감 또는 단계 불일치",
                        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorBody.class)))
            })
    @PutMapping("/vote-submissions/me")
    ResponseEntity<Void> submitVote(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceVoting.Submit request) {
        voting.submit(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "장소 후보 등록 시작",
            description = "방장이 확정된 일시·지역의 selectionId와 필수 방문 카테고리를 보내 장소 등록 시작",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "201", description = "등록 라운드와 카테고리·마감 시각 반환", useReturnTypeSchema = true)
            })
    @PostMapping("/place-submission")
    ResponseEntity<PlaceRegistration.Opened> open(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "요청 중복 처리를 막는 UUID. 새 요청마다 발급하고 같은 요청을 재시도할 때 재사용")
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PlaceRegistration.Open request) {
        return ResponseEntity.status(201).body(registration.open(roomId, user.userId(), key, request));
    }

    @Operation(summary = "확정 지역 주변 장소 검색",
            description = "확정 역 기준으로 검색. 장소명 q 검색은 반경 밖도 distanceMeters와 registrable=false로 반환하며 pickToken은 없음. 반경과 확장 여부는 radiusMeters와 expanded 사용",
            responses = {
                @ApiResponse(responseCode = "200", description = "검색 결과 및 다음 페이지 번호 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/places/search")
    ResponseEntity<PlaceRegistration.Search> search(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "검색할 카테고리. FOOD, CAFE, CULTURE, BAR 중 하나")
            @RequestParam Category category,
            @Parameter(description = "장소명 또는 검색어. 생략 시 카테고리로 검색, 입력 시 1~60자", example = "파스타")
            @RequestParam(required = false) String q,
            @Parameter(description = "검색 페이지 번호. 1~3 지정, 기본값 1. 다음 호출에 nextPage 사용", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "주변 검색의 지도 범위. 서쪽 경도,남쪽 위도,동쪽 경도,북쪽 위도 순. q가 있으면 범위 제한 없이 장소명을 검색", example = "126.97,37.53,127.02,37.58")
            @RequestParam(required = false) String bounds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(registration.search(roomId, user.userId(), category, q, page, bounds));
    }

    @Operation(summary = "장소 등록 가능 카테고리 조회",
            description = "방장이 장소 등록을 열기 전에 확정 역 주변의 카테고리별 등록 가능 여부와 검색 반경 확인",
            responses = {
                @ApiResponse(responseCode = "200", description = "확정 선택 ID와 카테고리 설정 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/place-submission/categories")
    ResponseEntity<PlaceRegistration.Availability> availability(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(registration.availability(roomId, user.userId()));
    }

    @Operation(summary = "내 장소 후보 저장 또는 삭제",
            description = "장소 검색에서 받은 pickToken으로 카테고리 후보를 저장하거나 기존 후보 교체. 삭제 시 pickToken에 null 전달",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "후보 저장 또는 삭제 완료", content = @Content)
            })
    @PutMapping("/place-submission/me/{category}")
    ResponseEntity<Void> nominate(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @Parameter(description = "후보를 저장하거나 지울 카테고리. FOOD, CAFE, CULTURE, BAR 중 하나")
            @PathVariable Category category,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestBody PlaceRegistration.Nomination request) {
        registration.nominate(roomId, user.userId(), category, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 장소 후보 등록 완료",
            description = "참가자가 현재 등록 라운드의 후보 입력 완료 처리",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "내 등록 완료 처리 성공", content = @Content)
            })
    @PutMapping("/place-submission/me/completion")
    ResponseEntity<Void> complete(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceRegistration.Completion request) {
        registration.complete(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "장소 후보 등록 조기 종료",
            description = "방장이 현재 등록 라운드를 마감 시각 전에 종료",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "204", description = "장소 등록 종료 완료", content = @Content)
            })
    @PutMapping("/place-submission/closure")
    ResponseEntity<Void> close(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceRegistration.Completion request) {
        registration.closeEarly(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "장소 후보 등록 마감 연장",
            description = "방장이 현재 라운드에 추가 타이머 적용 또는 종료된 등록 재개",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "200", description = "등록 라운드 번호와 변경된 마감 시각 반환", useReturnTypeSchema = true)
            })
    @PostMapping("/place-submission/extension")
    ResponseEntity<PlaceRegistration.Extended> extend(
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "요청 중복 처리를 막는 UUID. 새 요청마다 발급하고 같은 요청을 재시도할 때 재사용")
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PlaceRegistration.Extension request) {
        return ResponseEntity.ok(registration.extend(roomId, user.userId(), key, request));
    }

    @ExceptionHandler(VenueException.class)
    ResponseEntity<Map<String, String>> venueError(VenueException failure, HttpServletRequest request) {
        RequestOperationLog.setFailureType(request, failure);
        RequestOperationLog.setResultCode(request, failure.getMessage());
        return ResponseEntity.status(failure.status).body(Map.of("code", failure.getMessage(), "message",
                switch (failure.status) {
                    case 400 -> "Invalid venue request";
                    case 403 -> "Access denied";
                    case 503 -> "Venue service unavailable";
                    default -> "Request conflicts with the current venue state";
                }));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalidRequest(HttpServletRequest request) {
        return venueError(new VenueException(400, "INVALID_REQUEST"), request);
    }
}
