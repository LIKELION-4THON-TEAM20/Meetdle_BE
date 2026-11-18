package com.likelion4thon.Meetdle.planning.station;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "지하철역")
@RestController
public class StationController {

    private final StationSearchService stationSearchService;

    public StationController(StationSearchService stationSearchService) {
        this.stationSearchService = stationSearchService;
    }

    /**
     * A09 GET /api/stations?q=강남&limit=10 → 200 {"items":[{"stationId":"...","name":"강남","lines":["2"],"lat":37.49,"lng":127.02}]}
     * 결과가 없으면 빈 배열.
     */
    @Operation(summary = "지하철역 검색",
            description = "출발역과 목적지역을 입력할 때 역 이름으로 검색. 결과의 stationId를 내 입력 저장에 사용",
            responses = {
                @ApiResponse(responseCode = "200", description = "일치하는 역 목록 반환. 결과가 없으면 빈 배열", useReturnTypeSchema = true)
            })
    @GetMapping("/api/stations")
    public StationsResponse search(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "검색할 역 이름 1~40자. 공백 제거 후 빈 문자열은 허용하지 않음", example = "강남", required = true)
            @RequestParam(required = false) String q,
            @Parameter(description = "최대 결과 수. 1~20 지정, 기본값 10", example = "10")
            @RequestParam(required = false) Integer limit
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return new StationsResponse(stationSearchService.search(q, limit));
    }

    public record StationsResponse(List<Station> items) {}
}
