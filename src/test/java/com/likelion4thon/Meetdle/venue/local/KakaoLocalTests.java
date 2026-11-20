package com.likelion4thon.Meetdle.venue.local;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class KakaoLocalTests {
    private final Station station = BoundaryFixtures.confirmedVenueRoom().selectedMeeting().orElseThrow().station();
    private MockRestServiceServer server;
    private KakaoLocal local;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        local = new KakaoLocal("test-key", builder.build());
    }

    @Test
    void namedSearchReturnsPlacesOutsideRadiusEvenWhenViewportIsSupplied() {
        server.expect(request -> {
            var uri = UriComponentsBuilder.fromUri(request.getURI()).build();
            assertEquals("/v2/local/search/keyword.json", uri.getPath());
            assertEquals("Cafe", uri.getQueryParams().getFirst("query"));
            assertEquals("distance", uri.getQueryParams().getFirst("sort"));
            assertEquals(String.valueOf(station.lng()), uri.getQueryParams().getFirst("x"));
            assertEquals(String.valueOf(station.lat()), uri.getQueryParams().getFirst("y"));
            assertFalse(uri.getQueryParams().containsKey("radius"));
            assertFalse(uri.getQueryParams().containsKey("rect"));
            assertEquals("KakaoAK test-key", request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess("""
                {"meta":{"total_count":1,"is_end":true},"documents":[
                  {"id":"1","place_name":"Far cafe","address_name":"Address","road_address_name":"",
                   "x":"126.9707","y":"37.7","place_url":"https://place.map.kakao.com/1","category_name":"Cafe"}]}
                """, MediaType.APPLICATION_JSON));
        var page = local.search(station, Category.CAFE, "Cafe", "126.96,37.54,126.98,37.56", 2500, 1);
        assertEquals(1, page.items().size());
        assertEquals(37.7, page.items().get(0).place().lat());
        assertEquals(1, page.totalCount());
        server.verify();
    }

    @Test
    void nearbyCategorySearchStillUsesItsConfiguredRadius() {
        server.expect(request -> {
            var uri = UriComponentsBuilder.fromUri(request.getURI()).build();
            assertEquals("/v2/local/search/category.json", uri.getPath());
            assertEquals("3000", uri.getQueryParams().getFirst("radius"));
            assertFalse(uri.getQueryParams().containsKey("query"));
        }).andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));
        assertTrue(local.search(station, Category.CAFE, null, null, 3000, 1).items().isEmpty());
        server.verify();
    }

    @Test
    void cultureIsUnavailableOnlyAfterExpandedSearchHasNoPlaces() {
        for (int radius : new int[] {2500, 3000}) {
            server.expect(request -> assertEquals(String.valueOf(radius),
                    UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("radius")))
                    .andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));
        }
        var setting = local.setting(station, Category.CULTURE);
        assertFalse(setting.available());
        assertEquals(3000, setting.radiusMeters());
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsProviderFailureWithoutSearchTextOrProviderBody(CapturedOutput output) {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("private-provider-body private-api-key"));
        assertThrows(com.likelion4thon.Meetdle.venue.VenueException.class,
                () -> local.search(station, Category.CAFE, "private-search-query", null, 2500, 1));
        server.verify();
        org.assertj.core.api.Assertions.assertThat(output.getOut())
                .contains("event=kakao_request_failed", "provider=KAKAO_LOCAL", "status=503", "category=CAFE")
                .doesNotContain("private-search-query", "private-provider-body", "private-api-key");
    }

    private static String emptyResponse() {
        return "{\"meta\":{\"total_count\":0,\"is_end\":true},\"documents\":[]}";
    }
}
