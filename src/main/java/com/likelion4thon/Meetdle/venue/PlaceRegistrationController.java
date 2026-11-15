package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/rooms/{roomId}")
class PlaceRegistrationController {
    private final PlaceRegistration registration;

    PlaceRegistrationController(PlaceRegistration registration) {
        this.registration = registration;
    }

    @PostMapping("/place-submission")
    ResponseEntity<PlaceRegistration.Opened> open(@PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PlaceRegistration.Open request) {
        return ResponseEntity.status(201).body(registration.open(roomId, user.userId(), key, request));
    }

    @GetMapping("/places/search")
    ResponseEntity<PlaceRegistration.Search> search(@PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam Category category,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String bounds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(registration.search(roomId, user.userId(), category, q, page, bounds));
    }

    @GetMapping("/place-submission/categories")
    ResponseEntity<PlaceRegistration.Availability> availability(@PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(registration.availability(roomId, user.userId()));
    }

    @PutMapping("/place-submission/me/{category}")
    ResponseEntity<Void> nominate(@PathVariable UUID roomId, @PathVariable Category category,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestBody PlaceRegistration.Nomination request) {
        registration.nominate(roomId, user.userId(), category, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/place-submission/me/completion")
    ResponseEntity<Void> complete(@PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceRegistration.Completion request) {
        registration.complete(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/place-submission/closure")
    ResponseEntity<Void> close(@PathVariable UUID roomId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PlaceRegistration.Completion request) {
        registration.closeEarly(roomId, user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/place-submission/extension")
    ResponseEntity<PlaceRegistration.Extended> extend(@PathVariable UUID roomId,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PlaceRegistration.Extension request) {
        return ResponseEntity.ok(registration.extend(roomId, user.userId(), key, request));
    }

    @ExceptionHandler(VenueException.class)
    ResponseEntity<Map<String, String>> venueError(VenueException failure) {
        return ResponseEntity.status(failure.status).body(Map.of("code", failure.getMessage(), "message",
                switch (failure.status) {
                    case 400 -> "Invalid place registration request";
                    case 403 -> "Access denied";
                    case 503 -> "Place registration service unavailable";
                    default -> "Request conflicts with the current place registration state";
                }));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalidRequest() {
        return venueError(new VenueException(400, "INVALID_REQUEST"));
    }
}
