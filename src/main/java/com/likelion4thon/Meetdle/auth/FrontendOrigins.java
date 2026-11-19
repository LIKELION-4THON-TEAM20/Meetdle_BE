package com.likelion4thon.Meetdle.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class FrontendOrigins {
    private final String defaultOrigin;
    private final List<String> allowedOrigins;

    FrontendOrigins(
            @Value("${meetdle.frontend.origin:http://localhost:5173}") String defaultOrigin,
            @Value("${meetdle.frontend.additional-origins:}") List<String> additionalOrigins) {
        this.defaultOrigin = defaultOrigin;
        var origins = new ArrayList<String>();
        origins.add(defaultOrigin);
        origins.addAll(additionalOrigins);
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Frontend origins must be HTTP(S) origins without a path, query or fragment");
            }
        }
        this.allowedOrigins = List.copyOf(origins);
    }

    String defaultOrigin() {
        return defaultOrigin;
    }

    List<String> allowedOrigins() {
        return allowedOrigins;
    }
}
