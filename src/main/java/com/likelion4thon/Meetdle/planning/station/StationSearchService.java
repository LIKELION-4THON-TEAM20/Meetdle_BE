package com.likelion4thon.Meetdle.planning.station;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** A09 지원역 검색. SC-04에서 출발역·목적지를 고를 때 같은 검색을 쓴다. */
@Service
public class StationSearchService {

    static final int MAX_QUERY_LENGTH = 40;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;

    private final StationRepository stationRepository;

    public StationSearchService(StationRepository stationRepository) {
        this.stationRepository = stationRepository;
    }

    /**
     * 예: q=" 강남역 " → 공백 지우고 끝의 "역"도 떼서 "강남"으로 찾는다 → [강남, 강남구청]
     * q가 비었거나 40자 넘음, limit이 1~20 밖 → 400 INVALID_REQUEST
     */
    @Transactional(readOnly = true)
    public List<Station> search(String q, Integer limitParam) {
        String query = q == null ? "" : q.strip();
        int length = query.codePointCount(0, query.length());
        if (length < 1 || length > MAX_QUERY_LENGTH) {
            throw ApiException.invalidRequest("q", "검색어는 1~" + MAX_QUERY_LENGTH + "자여야 해요");
        }
        int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalidRequest("limit", "1~" + MAX_LIMIT + " 사이여야 해요");
        }
        String typed = query;
        if (query.endsWith("역") && query.length() > 2) {
            query = query.substring(0, query.length() - 1);
        }
        // 예: "서울역" → "서울"로 찾되, 이름이 정확히 "서울역"인 역을 "서울대입구"보다 먼저
        return stationRepository.search(query, typed, limit);
    }
}
