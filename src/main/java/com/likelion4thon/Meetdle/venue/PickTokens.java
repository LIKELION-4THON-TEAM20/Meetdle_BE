package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.VenueStateReader.Place;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class PickTokens {
    private final JsonMapper json;
    private final SecretKeySpec key;
    private final boolean configured;

    PickTokens(JsonMapper json, @Value("${meetdle.invite.hmac-key:}") String signingSecret) {
        this.json = json;
        this.configured = !signingSecret.isBlank();
        this.key = new SecretKeySpec(("meetdle-place-pick-v1:" + signingSecret)
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String issue(Pick pick) {
        if (!configured) {
            throw new VenueException(503, "LOCAL_UNAVAILABLE");
        }
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = encoder.encodeToString(json.writeValueAsBytes(pick));
        return payload + "." + encoder.encodeToString(sign(payload));
    }

    Pick verify(String token) {
        if (!configured || token.length() > 8192) {
            throw new VenueException(400, "INVALID_PICK");
        }
        try {
            var decoder = Base64.getUrlDecoder();
            String[] parts = token.split("\\.", -1);
            if (parts.length == 2 && MessageDigest.isEqual(sign(parts[0]), decoder.decode(parts[1]))) {
                return json.readValue(decoder.decode(parts[0]), Pick.class);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid Base64 has the same response as an invalid signature.
        }
        throw new VenueException(400, "INVALID_PICK");
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign place picks", failure);
        }
    }

    record Pick(UUID roomId, UUID selectionId, int round, Instant expiresAt, String providerId, Place place) {}
}
