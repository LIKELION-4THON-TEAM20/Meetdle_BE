package com.likelion4thon.Meetdle.venue.local;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import com.likelion4thon.Meetdle.contract.VenueStateReader.CategorySetting;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Place;
import com.likelion4thon.Meetdle.venue.VenueException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KakaoLocal {
    private final RestClient client;
    private final String key;

    public KakaoLocal(@Value("${meetdle.kakao.rest-api-key:}") String key) {
        this.key = key;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl("https://dapi.kakao.com")
                .requestFactory(factory).build();
    }

    public CategorySetting setting(Station station, Category category) {
        int threshold = switch (category) {
            case FOOD -> 20;
            case CAFE -> 10;
            case CULTURE -> 5;
            case BAR -> 10;
        };
        boolean required = category == Category.FOOD || category == Category.CAFE;
        int radius = 2500;
        boolean available = true;
        if (countUpTo(station, category, radius, threshold) < threshold) {
            radius = 3000;
            if (category == Category.CULTURE) {
                available = fetch(station, category, null, null, radius, 1).meta().total_count() > 0;
            }
        }
        return new CategorySetting(category, required, available, radius);
    }

    public Page search(Station station, Category category, String query, String bounds, int radius, int page) {
        try {
            var result = fetch(station, category, query, bounds, radius, page);
            var places = result.documents().stream()
                    .filter(document -> matches(category, document))
                    .map(document -> {
                        String address = document.road_address_name();
                        if (address == null || address.isBlank()) {
                            address = document.address_name();
                        }
                        URI detail = document.place_url() == null || document.place_url().isBlank()
                                ? null : URI.create(document.place_url());
                        return new FoundPlace(document.id(), new Place(document.place_name(), address,
                                Double.parseDouble(document.y()), Double.parseDouble(document.x()), category, detail));
                    }).toList();
            return new Page(places, result.meta().is_end());
        } catch (IllegalArgumentException failure) {
            throw new VenueException(503, "LOCAL_UNAVAILABLE");
        }
    }

    private int countUpTo(Station station, Category category, int radius, int threshold) {
        var ids = new java.util.HashSet<String>();
        for (int page = 1; page <= 3; page++) {
            var response = fetch(station, category, null, null, radius, page);
            response.documents().stream().filter(document -> matches(category, document))
                    .map(Document::id).forEach(ids::add);
            if (ids.size() >= threshold || response.meta().is_end()) {
                break;
            }
        }
        return ids.size();
    }

    private static boolean matches(Category category, Document document) {
        boolean bar = isBar(document.category_name());
        return (category != Category.BAR || bar) && (category != Category.FOOD || !bar);
    }

    private SearchResponse fetch(Station station, Category category, String query, String bounds, int radius, int page) {
        if (key.isBlank()) {
            throw new VenueException(503, "LOCAL_UNAVAILABLE");
        }
        String keyword = category == Category.BAR ? (query == null ? "주점" : query) : query;
        String group = switch (category) {
            case FOOD -> "FD6";
            case CAFE -> "CE7";
            case CULTURE -> "CT1";
            case BAR -> null;
        };
        try {
            var result = client.get().uri(builder -> {
                builder.path(keyword == null ? "/v2/local/search/category.json" : "/v2/local/search/keyword.json")
                        .queryParam("page", page).queryParam("size", 15);
                if (keyword != null) {
                    builder.queryParam("query", "{query}");
                }
                if (group != null) {
                    builder.queryParam("category_group_code", group);
                }
                if (bounds == null) {
                    builder.queryParam("x", station.lng()).queryParam("y", station.lat())
                            .queryParam("radius", radius).queryParam("sort", "distance");
                } else {
                    builder.queryParam("rect", bounds);
                }
                return keyword == null ? builder.build() : builder.build(keyword);
            }).header("Authorization", "KakaoAK " + key).retrieve().body(SearchResponse.class);
            if (result == null || result.meta() == null || result.documents() == null) {
                throw new VenueException(503, "LOCAL_UNAVAILABLE");
            }
            return result;
        } catch (RestClientException failure) {
            throw new VenueException(503, "LOCAL_UNAVAILABLE");
        }
    }

    private static boolean isBar(String description) {
        String category = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return List.of("술집", "주점", "호프", "이자카야", "와인바", "칵테일", "바(bar)")
                .stream().anyMatch(category::contains);
    }

    public record FoundPlace(String id, Place place) {}
    public record Page(List<FoundPlace> items, boolean end) {}
    private record SearchResponse(Meta meta, List<Document> documents) {}
    private record Meta(int total_count, boolean is_end) {}
    private record Document(String id, String place_name, String address_name, String road_address_name,
                            String x, String y, String place_url, String category_name) {}
}
