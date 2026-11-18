package com.likelion4thon.Meetdle.planning;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.room.RoomApiTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A09 GET /api/stations — 서버가 켜질 때 공공데이터로 채운 역 카탈로그를 검색한다. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomApiTestConfig.class)
class StationSearchApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exactNameComesFirstThenPrefixMatches() throws Exception {
        search("q", "강남")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("강남"))
                .andExpect(jsonPath("$.items[0].lines", contains("2")))
                .andExpect(jsonPath("$.items[0].stationId").isString())
                .andExpect(jsonPath("$.items[0].lat").isNumber())
                .andExpect(jsonPath("$.items[*].name", hasItem("강남구청")));
    }

    @Test
    void ignoresTrailingStationSuffixAndSpaces() throws Exception {
        search("q", " 강남역 ").andExpect(jsonPath("$.items[0].name").value("강남"));
        search("q", "서울역").andExpect(jsonPath("$.items[0].name").value("서울역"));
    }

    @Test
    void findsTransferStationsWithAllLinesAndAliases() throws Exception {
        search("q", "고속터미널").andExpect(jsonPath("$.items[0].lines", contains("3", "7", "9")));
        search("q", "이수").andExpect(jsonPath("$.items[0].name").value("총신대입구(이수)"));
    }

    @Test
    void respectsLimitAndReturnsEmptyListWhenNothingMatches() throws Exception {
        search("q", "역", "limit", "3").andExpect(jsonPath("$.items.length()").value(3));
        search("q", "판교").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        search("q", "%").andExpect(jsonPath("$.items").isEmpty()); // "%"는 와일드카드가 아니라 글자로 취급
    }

    @Test
    void rejectsBadQueryOrLimit() throws Exception {
        search().andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.q").exists());
        search("q", "  ").andExpect(status().isBadRequest());
        search("q", "가".repeat(41)).andExpect(status().isBadRequest());
        search("q", "강남", "limit", "0").andExpect(status().isBadRequest());
        search("q", "강남", "limit", "21").andExpect(status().isBadRequest());
    }

    /** 예: search("q", " 강남역 ", "limit", "3") — 값은 그대로 쿼리 파라미터로 들어간다. */
    private ResultActions search(String... nameValuePairs) throws Exception {
        var request = get("/api/stations")
                .with(authentication(new TestingAuthenticationToken(BoundaryFixtures.HOST, null, "ROLE_USER")));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mockMvc.perform(request);
    }
}
