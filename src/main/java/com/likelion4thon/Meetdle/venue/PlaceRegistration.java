package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomMember.Role;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.RoomVenueSnapshot;
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.contract.VenueStateReader.*;
import com.likelion4thon.Meetdle.venue.RegistrationStore.Round;
import com.likelion4thon.Meetdle.venue.local.KakaoLocal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static com.likelion4thon.Meetdle.venue.RegistrationStore.utc;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class PlaceRegistration {
    private final ObjectProvider<RoomVenueAccess> rooms;
    private final RegistrationStore store;
    private final KakaoLocal kakao;
    private final PickTokens tokens;
    private final JsonMapper json;
    private final TransactionTemplate write;

    PlaceRegistration(ObjectProvider<RoomVenueAccess> rooms, RegistrationStore store, KakaoLocal kakao,
                      PickTokens tokens, JsonMapper json, PlatformTransactionManager transactions) {
        this.rooms = rooms;
        this.store = store;
        this.kakao = kakao;
        this.tokens = tokens;
        this.json = json;
        this.write = new TransactionTemplate(transactions);
        this.write.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    Opened open(UUID room, UUID user, UUID key, Open request) {
        if (request.selectionId() == null) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        var required = request.requiredCategories();
        if (required == null || required.isEmpty() || required.stream().anyMatch(c -> c == null || c == Category.CULTURE)
                || new HashSet<>(required).size() != required.size()) {
            throw new VenueException(400, "INVALID_REQUIRED_CATEGORIES");
        }

        RoomVenueAccess access = access();
        var snapshot = access.read(room, user);
        host(snapshot);
        var command = commandKey(room, user, key, "/place-submission", request);
        var prior = saved(command, snapshot.now());
        if (prior.isPresent()) {
            return json.readValue(prior.get(), Opened.class);
        }
        // Hold no write lock while calling Kakao. Recheck the state and deadline after acquiring it.
        var meeting = checkOpen(snapshot, request);
        openDeadline(snapshot, meeting, request);
        var settings = settings(meeting, required);

        return write.execute(transaction -> {
            var lock = access.lockForWrite(room, new RoomVenueAccess.UserActor(user));
            var locked = lock.snapshot();
            host(locked);
            return deduplicate(locked, command, 201, Opened.class, () -> {
                var selected = checkOpen(locked, request);
                Instant deadline = openDeadline(locked, selected, request);
                return createRound(access, lock, selected, deadline, settings);
            });
        });
    }

    Opened createRound(RoomVenueAccess access, RoomVenueAccess.LockedRoomVenue lock,
                               SelectedMeeting meeting, Instant deadline, List<CategorySetting> settings) {
        UUID room = lock.snapshot().roomId();
        String id = room.toString();
        int number = store.latest(room).map(round -> round.number() + 1).orElse(1);
        store.jdbc().update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at) VALUES (?, ?, ?, ?)
                """, id, number, meeting.selectionId().toString(), utc(deadline));
        for (CategorySetting setting : settings) {
            store.jdbc().update("""
                    INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, number, setting.category().name(), setting.required(), setting.available(),
                    setting.radiusMeters());
        }
        var members = lock.snapshot().memberParticipantIds().stream()
                .map(participantId -> new Object[] {id, number, participantId.toString()})
                .toList();
        store.jdbc().batchUpdate("""
                INSERT INTO round_member (room_id, round_no, participant_id) VALUES (?, ?, ?)
                """, members);
        access.recordChange(lock, PLACE_SUBMISSION_OPEN);
        return new Opened(number, settings, deadline);
    }

    private static SelectedMeeting checkOpen(RoomVenueSnapshot snapshot, Open request) {
        if (snapshot.phase() != REGION_CONFIRMED) {
            throw conflict("WRONG_PHASE");
        }
        var meeting = meeting(snapshot);
        if (!meeting.selectionId().equals(request.selectionId())) {
            throw conflict("STALE_SELECTION");
        }
        return meeting;
    }

    private static Instant openDeadline(RoomVenueSnapshot snapshot, SelectedMeeting meeting, Open request) {
        return registrationDeadline(snapshot, meeting, request.closesAt(), false);
    }

    static Instant registrationDeadline(RoomVenueSnapshot snapshot, SelectedMeeting meeting,
                                        Instant requested, boolean required) {
        var now = snapshot.now();
        var minimum = now.plus(MeetingWindow.calculate(meeting.meetingAt(), now).registrationMinimum());
        var deadline = !required && requested == null ? minimum : requested;
        if (minimum.isAfter(cutoff(meeting.meetingAt(), now))) {
            throw conflict("INSUFFICIENT_TIME");
        }
        if (deadline == null || deadline.isBefore(minimum) || deadline.isAfter(cutoff(meeting.meetingAt(), now))) {
            throw new VenueException(400, "INVALID_DEADLINE");
        }
        return deadline;
    }

    @Transactional(readOnly = true)
    Availability availability(UUID room, UUID user) {
        var snapshot = access().read(room, user);
        host(snapshot);
        if (snapshot.phase() != REGION_CONFIRMED) {
            throw conflict("WRONG_PHASE");
        }
        var meeting = meeting(snapshot);
        return new Availability(meeting.selectionId(), settings(meeting, List.of(Category.FOOD, Category.CAFE)));
    }

    private List<CategorySetting> settings(SelectedMeeting meeting, List<Category> required) {
        return Arrays.stream(Category.values()).map(category -> {
            var setting = kakao.setting(meeting.station(), category);
            return new CategorySetting(category, required.contains(category), setting.available(), setting.radiusMeters());
        }).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Search search(UUID room, UUID user, Category category, String query, int page, String bounds) {
        if (page < 1 || page > 3 || (query != null && (query.strip().isEmpty()
                || query.strip().codePointCount(0, query.strip().length()) > 60))) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        validateBounds(bounds);
        var snapshot = access().read(room, user);
        var meeting = meeting(snapshot);
        CategorySetting setting;
        int number;
        if (snapshot.phase() == REGION_CONFIRMED) {
            host(snapshot);
            setting = kakao.setting(meeting.station(), category);
            number = 0;
        } else if (snapshot.phase() == PLACE_SUBMISSION_OPEN) {
            Round round = latest(room);
            requireOpen(snapshot, round);
            participant(snapshot, round);
            setting = store.categories(round).stream().filter(item -> item.category() == category)
                    .findFirst().orElseThrow(() -> conflict("CATEGORY_DISABLED"));
            number = round.number();
        } else {
            throw conflict("WRONG_PHASE");
        }

        int radius = setting.radiusMeters();
        if (!setting.available()) {
            return new Search(List.of(), null, radius, radius > 2500, false);
        }
        var results = kakao.search(meeting.station(), category, query == null ? null : query.strip(), bounds, radius, page);
        var items = results.items().stream().map(found -> {
            double meters = distance(meeting.station(), found.place());
            boolean registrable = number > 0 && meters <= radius;
            String token = registrable ? tokens.issue(new PickTokens.Pick(room, meeting.selectionId(), number,
                    snapshot.now().plus(Duration.ofMinutes(15)), found.id(), found.place())) : null;
            return new SearchItem(found.place(), (int) Math.round(meters), token, registrable);
        }).toList();
        return new Search(items, page < 3 && !results.end() ? page + 1 : null, radius, radius > 2500, true);
    }

    void nominate(UUID room, UUID user, Category category, Nomination request) {
        RoomVenueAccess access = access();
        var lock = access.lockForWrite(room, new RoomVenueAccess.UserActor(user));
        var snapshot = lock.snapshot();
        Round round = current(snapshot, request.round());
        requireOpen(snapshot, round);
        UUID participant = participant(snapshot, round);
        var setting = category(round, category);
        if (store.completed(round).contains(participant)) {
            throw conflict("ALREADY_COMPLETED");
        }
        PickTokens.Pick pick = null;
        if (request.pickToken() != null) {
            pick = tokens.verify(request.pickToken());
            var meeting = meeting(snapshot);
            if (!pick.roomId().equals(room) || !pick.selectionId().equals(meeting.selectionId())) {
                throw new VenueException(400, "INVALID_PICK");
            }
            if (pick.round() != round.number()) {
                throw conflict("STALE_ROUND");
            }
            if (!snapshot.now().isBefore(pick.expiresAt())) {
                throw conflict("PICK_EXPIRED");
            }
            if (pick.place().category() != category) {
                throw new VenueException(400, "CATEGORY_MISMATCH");
            }
            if (distance(meeting.station(), pick.place()) > setting.radiusMeters()) {
                throw new VenueException(400, "OUT_OF_RADIUS");
            }
        }
        if (store.nominate(round, participant, category, pick, snapshot.now())) {
            access.recordChange(lock, PLACE_SUBMISSION_OPEN);
        }
    }

    void complete(UUID room, UUID user, Completion request) {
        RoomVenueAccess access = access();
        var lock = access.lockForWrite(room, new RoomVenueAccess.UserActor(user));
        var snapshot = lock.snapshot();
        Round round = current(snapshot, request.round());
        UUID participant = participant(snapshot, round);
        var completed = store.completed(round);
        if ((snapshot.phase() == PLACE_SUBMISSION_OPEN || snapshot.phase() == PLACE_SUBMISSION_CLOSED
                || snapshot.phase() == FINAL_PLACE_CONFIRMED)
                && completed.contains(participant)) {
            return;
        }
        requireOpen(snapshot, round);
        if (!store.hasRequiredNominations(round, store.nominations(round, participant))) {
            throw conflict("REQUIRED_NOMINATIONS_MISSING");
        }
        store.jdbc().update("""
                UPDATE round_member SET registration_completed_at = ?
                WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, utc(snapshot.now()), round.room(), round.number(), participant.toString());
        if (completed.size() + 1 == store.eligibleCount(round)) {
            close(access, lock, round, snapshot.now(), "ALL_COMPLETED");
        } else {
            access.recordChange(lock, PLACE_SUBMISSION_OPEN);
        }
    }

    void closeEarly(UUID room, UUID user, Completion request) {
        RoomVenueAccess access = access();
        var lock = access.lockForWrite(room, new RoomVenueAccess.UserActor(user));
        var snapshot = lock.snapshot();
        host(snapshot);
        Round round = current(snapshot, request.round());
        if (snapshot.phase() != PLACE_SUBMISSION_OPEN || !snapshot.now().isBefore(round.closesAt())) {
            throw conflict("WRONG_PHASE");
        }
        if (!canCloseEarly(snapshot.capacity(), store.completed(round).size())) {
            throw conflict("NOT_ENOUGH_COMPLETIONS");
        }
        if (!store.hasRequiredCandidates(round, store.counts(round))) {
            throw conflict("REQUIRED_CATEGORY_EMPTY");
        }
        close(access, lock, round, snapshot.now(), "HOST_CLOSED");
    }

    Extended extend(UUID room, UUID user, UUID key, Extension request) {
        RoomVenueAccess access = access();
        var lock = access.lockForWrite(room, new RoomVenueAccess.UserActor(user));
        var snapshot = lock.snapshot();
        host(snapshot);
        if (request.mode() == null) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        var command = commandKey(room, user, key, "/place-submission/extension", request);
        return deduplicate(snapshot, command, 200, Extended.class, () -> {
            if (snapshot.phase() != PLACE_SUBMISSION_OPEN && snapshot.phase() != PLACE_SUBMISSION_CLOSED) {
                throw conflict("WRONG_PHASE");
            }
            Round round = current(snapshot, request.round());
            Instant now = snapshot.now();
            boolean mini = request.mode() == ExtensionMode.MINI_TIMER;
            boolean phaseMatches = mini
                    ? snapshot.phase() == PLACE_SUBMISSION_OPEN && now.isBefore(round.closesAt())
                    : snapshot.phase() == PLACE_SUBMISSION_CLOSED;
            if (mini ? round.miniTimerUsed() : round.extended()) {
                throw conflict("EXTENSION_EXHAUSTED");
            }
            int completed = store.completed(round).size();
            if (!phaseMatches || completed >= store.eligibleCount(round)
                    || (mini && !canStartEarly(snapshot.capacity(), completed))) {
                throw conflict("NOT_ALLOWED");
            }
            Instant cutoff = cutoff(meeting(snapshot).meetingAt(), now);
            Instant base = mini ? round.closesAt() : now;
            if (!cutoff.isAfter(base)) {
                throw conflict("INSUFFICIENT_TIME");
            }
            Instant deadline = request.closesAt();
            if (mini && deadline == null) {
                deadline = base.plus(Duration.ofHours(1));
                if (deadline.isAfter(cutoff)) {
                    deadline = cutoff;
                }
            }
            if (deadline == null || !deadline.isAfter(base) || deadline.isAfter(cutoff)) {
                throw new VenueException(400, "INVALID_DEADLINE");
            }
            store.jdbc().update("""
                    UPDATE venue_round SET collection_closes_at = ?, collection_closed_at = NULL,
                        collection_close_reason = NULL,
                        collection_extended_at = COALESCE(?, collection_extended_at),
                        mini_timer_used_at = COALESCE(?, mini_timer_used_at)
                    WHERE room_id = ? AND round_no = ?
                    """, utc(deadline), mini ? null : utc(now), mini ? utc(now) : null, round.room(), round.number());
            access.recordChange(lock, PLACE_SUBMISSION_OPEN);
            return new Extended(round.number(), deadline);
        });
    }

    void advanceDeadline(UUID room) {
        RoomVenueAccess access = access();
        var lock = access.lockForWrite(room, RoomVenueAccess.SystemActor.DEADLINE_SCHEDULER);
        var snapshot = lock.snapshot();
        if (snapshot.phase() != PLACE_SUBMISSION_OPEN && snapshot.phase() != PLACE_SUBMISSION_CLOSED) {
            return;
        }
        Round round = latest(room);
        Instant now = snapshot.now();
        if (snapshot.phase() == PLACE_SUBMISSION_OPEN && !now.isBefore(round.closesAt())) {
            close(access, lock, round, round.closesAt(), "DEADLINE");
        } else if (snapshot.phase() == PLACE_SUBMISSION_CLOSED && now.isAfter(cutoff(meeting(snapshot).meetingAt(), now))) {
            access.recordChange(lock, ENDED);
        }
    }

    private void close(RoomVenueAccess access, RoomVenueAccess.LockedRoomVenue lock,
                       Round round, Instant at, String reason) {
        store.jdbc().update("""
                UPDATE venue_round SET collection_closed_at = ?, collection_close_reason = ?
                WHERE room_id = ? AND round_no = ?
                """, utc(at), reason, round.room(), round.number());
        boolean automatic = store.finalizeIfAutomatic(round, lock.snapshot().now());
        access.recordChange(lock, automatic ? FINAL_PLACE_CONFIRMED : PLACE_SUBMISSION_CLOSED);
    }

    static boolean canCloseEarly(int capacity, int complete) {
        return complete >= (capacity + 1) / 2;
    }

    static boolean canStartEarly(int capacity, int complete) {
        return complete >= capacity / 2 + 1;
    }

    static Instant cutoff(Instant meetingAt, Instant now) {
        return MeetingWindow.calculate(meetingAt, now).registrationCutoff();
    }

    private static double distance(SelectedMeeting.Station station, Place place) {
        double lat = Math.toRadians(place.lat() - station.lat());
        double lng = Math.toRadians(place.lng() - station.lng());
        double a = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(station.lat()))
                * Math.cos(Math.toRadians(place.lat())) * Math.pow(Math.sin(lng / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    private static void validateBounds(String bounds) {
        if (bounds == null) {
            return;
        }
        try {
            String[] values = bounds.split(",", -1);
            if (values.length == 4) {
                double west = Double.parseDouble(values[0]);
                double south = Double.parseDouble(values[1]);
                double east = Double.parseDouble(values[2]);
                double north = Double.parseDouble(values[3]);
                if (west >= -180 && east <= 180 && south >= -90 && north <= 90 && west < east && south < north) {
                    return;
                }
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the same response as invalid coordinate ranges.
        }
        throw new VenueException(400, "INVALID_REQUEST");
    }

    RoomVenueAccess access() {
        RoomVenueAccess access = rooms.getIfAvailable();
        if (access == null) {
            throw new VenueException(503, "ROOM_WORKFLOW_UNAVAILABLE");
        }
        return access;
    }

    private Round latest(UUID room) {
        return store.latest(room).orElseThrow(() -> conflict("WRONG_PHASE"));
    }

    Round current(RoomVenueSnapshot snapshot, int requested) {
        if (requested < 1) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        Round round = latest(snapshot.roomId());
        if (round.number() != requested) {
            throw conflict("STALE_ROUND");
        }
        return round;
    }

    static void host(RoomVenueSnapshot snapshot) {
        if (snapshot.actorMember().filter(member -> member.role() == Role.HOST).isEmpty()) {
            throw new VenueException(403, "FORBIDDEN");
        }
    }

    UUID participant(RoomVenueSnapshot snapshot, Round round) {
        return snapshot.actorMember().map(member -> member.participantId())
                .filter(id -> store.eligible(round, id)).orElseThrow(() -> new VenueException(403, "FORBIDDEN"));
    }

    private CategorySetting category(Round round, Category category) {
        return store.categories(round).stream().filter(setting -> setting.category() == category && setting.available())
                .findFirst().orElseThrow(() -> conflict("CATEGORY_DISABLED"));
    }

    static SelectedMeeting meeting(RoomVenueSnapshot snapshot) {
        return snapshot.selectedMeeting().orElseThrow(() -> conflict("WRONG_PHASE"));
    }

    private static void requireOpen(RoomVenueSnapshot snapshot, Round round) {
        if (snapshot.phase() != PLACE_SUBMISSION_OPEN && snapshot.phase() != PLACE_SUBMISSION_CLOSED) {
            throw conflict("WRONG_PHASE");
        }
        if (snapshot.phase() == PLACE_SUBMISSION_CLOSED || !snapshot.now().isBefore(round.closesAt())) {
            throw conflict("DEADLINE_PASSED");
        }
    }

    static VenueException conflict(String code) {
        return new VenueException(409, code);
    }

    CommandKey commandKey(UUID room, UUID user, UUID key, String suffix, Object request) {
        try {
            return new CommandKey(user.toString(), "/api/rooms/" + room + suffix, key.toString(),
                    MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Optional<String> saved(CommandKey key, Instant now) {
        return store.jdbc().query("""
                SELECT request_hash, response_json FROM command_dedup
                WHERE user_id = ? AND request_path = ? AND idempotency_key = ? AND expires_at > ?
                """, (rs, row) -> {
            if (!MessageDigest.isEqual(key.hash(), rs.getBytes("request_hash"))) {
                throw conflict("KEY_REUSED");
            }
            return rs.getString("response_json");
        }, key.user(), key.path(), key.id(), utc(now)).stream().findFirst();
    }

    // Both command paths include the room in their key and save success under the room write lock.
    <T> T deduplicate(RoomVenueSnapshot snapshot, CommandKey key, int status,
                              Class<T> type, Supplier<T> command) {
        var prior = saved(key, snapshot.now());
        if (prior.isPresent()) {
            return json.readValue(prior.get(), type);
        }
        T result = command.get();
        store.jdbc().update("DELETE FROM command_dedup WHERE user_id = ? AND request_path = ? AND idempotency_key = ?",
                key.user(), key.path(), key.id());
        store.jdbc().update("""
                INSERT INTO command_dedup (user_id, request_path, idempotency_key, request_hash,
                    response_status, response_json, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, key.user(), key.path(), key.id(), key.hash(), status, json.writeValueAsString(result),
                utc(snapshot.now().plus(Duration.ofHours(24))));
        return result;
    }

    record CommandKey(String user, String path, String id, byte[] hash) {}
    enum ExtensionMode { MINI_TIMER, EXTEND }
    record Open(UUID selectionId, List<Category> requiredCategories, Instant closesAt) {}
    record Opened(int round, List<CategorySetting> categories, Instant closesAt) {}
    record Nomination(int round, @com.fasterxml.jackson.annotation.JsonProperty(required = true) String pickToken) {}
    record Completion(int round) {}
    record Extension(int round, ExtensionMode mode, Instant closesAt) {}
    record Extended(int round, Instant closesAt) {}
    record Availability(UUID selectionId, List<CategorySetting> categories) {}
    record Search(List<SearchItem> items, Integer nextPage, int radiusMeters, boolean expanded, boolean available) {}
    record SearchItem(Place place, int distanceMeters, String pickToken, boolean registrable) {}
}
