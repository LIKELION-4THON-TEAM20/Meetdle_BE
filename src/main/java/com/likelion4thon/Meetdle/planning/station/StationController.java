package com.likelion4thon.Meetdle.planning.station;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    @GetMapping("/api/stations")
    public StationsResponse search(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return new StationsResponse(stationSearchService.search(q, limit));
    }

    public record StationsResponse(List<Station> items) {}
}
