package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.VenueStateReader.*;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RegistrationStore {
    private final JdbcTemplate jdbc;

    RegistrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    Optional<Round> latest(UUID room) {
        return jdbc.query("""
                SELECT round_no, collection_closes_at, collection_closed_at, collection_extended_at,
                    mini_timer_used_at, vote_closes_at, vote_extension_count, recovery_reason FROM venue_round
                WHERE room_id = ? ORDER BY round_no DESC LIMIT 1
                """, (rs, row) -> new Round(room.toString(), rs.getInt("round_no"),
                instant(rs, "collection_closes_at"), instant(rs, "collection_closed_at"),
                instant(rs, "collection_extended_at") != null, instant(rs, "mini_timer_used_at") != null,
                instant(rs, "vote_closes_at"), rs.getInt("vote_extension_count"),
                rs.getString("recovery_reason")), room.toString()).stream().findFirst();
    }

    List<CategorySetting> categories(Round round) {
        return jdbc.query("""
                SELECT category, required, available, radius_meters FROM round_category
                WHERE room_id = ? AND round_no = ? ORDER BY required DESC, category
                """, (rs, row) -> new CategorySetting(Category.valueOf(rs.getString("category")),
                rs.getBoolean("required"), rs.getBoolean("available"), rs.getInt("radius_meters")),
                round.room(), round.number());
    }

    List<UUID> completed(Round round) {
        return jdbc.query("""
                SELECT participant_id FROM round_member WHERE room_id = ? AND round_no = ?
                AND registration_completed_at IS NOT NULL ORDER BY participant_id
                """, (rs, row) -> UUID.fromString(rs.getString(1)), round.room(), round.number());
    }

    boolean eligible(Round round, UUID participant) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM round_member WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, Integer.class, round.room(), round.number(), participant.toString()) > 0;
    }

    List<Place> nominations(Round round, UUID participant) {
        return jdbc.query("""
                SELECT c.name, c.address, c.lat, c.lng, c.category, c.detail_url
                FROM place_nomination n JOIN place_candidate c
                  ON c.room_id = n.room_id AND c.round_no = n.round_no AND c.candidate_id = n.candidate_id
                WHERE n.room_id = ? AND n.round_no = ? AND n.participant_id = ? ORDER BY n.category
                """, (rs, row) -> place(rs), round.room(), round.number(), participant.toString());
    }

    List<CategoryCount> counts(Round round) {
        return jdbc.query("""
                SELECT r.category, COUNT(c.candidate_id) AS candidate_count
                FROM round_category r LEFT JOIN place_candidate c
                  ON r.room_id = c.room_id AND r.round_no = c.round_no AND r.category = c.category
                WHERE r.room_id = ? AND r.round_no = ? AND r.available = TRUE
                GROUP BY r.category ORDER BY r.category
                """, (rs, row) -> new CategoryCount(Category.valueOf(rs.getString("category")),
                rs.getInt("candidate_count")), round.room(), round.number());
    }

    boolean hasRequiredCandidates(Round round, List<CategoryCount> counts) {
        return categories(round).stream().filter(CategorySetting::required)
                .allMatch(setting -> count(counts, setting.category()) > 0);
    }

    boolean finalizeIfAutomatic(Round round, Instant now) {
        var counts = counts(round);
        if (categories(round).stream().anyMatch(setting ->
                count(counts, setting.category()) != (setting.required() ? 1 : 0))) {
            return false;
        }
        var candidates = jdbc.query("""
                SELECT candidate_id FROM place_candidate WHERE room_id = ? AND round_no = ?
                ORDER BY CASE category WHEN 'FOOD' THEN 1 WHEN 'CAFE' THEN 2 WHEN 'CULTURE' THEN 3 ELSE 4 END
                """, (rs, row) -> rs.getString(1), round.room(), round.number());
        if (candidates.isEmpty()) {
            return false;
        }
        jdbc.update("""
                INSERT INTO venue_result (room_id, round_no, result_revision, submitted_count, computed_at, finalized_at)
                VALUES (?, ?, 1, 0, ?, ?)
                """, round.room(), round.number(), utc(now), utc(now));
        int position = 1;
        for (String candidate : candidates) {
            jdbc.update("""
                    INSERT INTO ballot_candidate (room_id, round_no, candidate_id, mode)
                    VALUES (?, ?, ?, 'AUTO_SELECTED')
                    """, round.room(), round.number(), candidate);
            jdbc.update("""
                    INSERT INTO result_place (room_id, round_no, candidate_id, result_status, visit_position)
                    VALUES (?, ?, ?, 'SELECTED', ?)
                    """, round.room(), round.number(), candidate, position++);
        }
        return true;
    }

    private static int count(List<CategoryCount> counts, Category category) {
        return counts.stream().filter(candidate -> candidate.category() == category)
                .mapToInt(CategoryCount::count).findFirst().orElse(0);
    }

    boolean hasRequiredNominations(Round round, List<Place> nominations) {
        return categories(round).stream().filter(CategorySetting::required)
                .allMatch(setting -> nominations.stream().anyMatch(place -> place.category() == setting.category()));
    }

    boolean nominate(Round round, UUID participant, Category category, PickTokens.Pick pick, Instant now) {
        String participantId = participant.toString();
        String categoryName = category.name();
        String previous = jdbc.query("""
                SELECT candidate_id FROM place_nomination WHERE room_id = ? AND round_no = ?
                AND participant_id = ? AND category = ?
                """, (rs, row) -> rs.getString(1), round.room(), round.number(), participantId, categoryName)
                .stream().findFirst().orElse(null);
        String candidate = pick == null ? null : candidate(round, pick);
        if (java.util.Objects.equals(previous, candidate)) {
            return false;
        }
        if (previous != null) {
            jdbc.update("""
                    DELETE FROM place_nomination WHERE room_id = ? AND round_no = ?
                    AND participant_id = ? AND category = ?
                    """, round.room(), round.number(), participantId, categoryName);
        }
        if (candidate != null) {
            jdbc.update("""
                    INSERT INTO place_nomination (room_id, round_no, participant_id, category, candidate_id, nominated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, round.room(), round.number(), participantId, categoryName, candidate, utc(now));
        }
        if (previous != null) {
            jdbc.update("""
                    DELETE FROM place_candidate WHERE room_id = ? AND round_no = ? AND candidate_id = ?
                    AND NOT EXISTS (SELECT 1 FROM place_nomination WHERE room_id = ? AND round_no = ? AND candidate_id = ?)
                    """, round.room(), round.number(), previous, round.room(), round.number(), previous);
        }
        return true;
    }

    int eligibleCount(Round round) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM round_member WHERE room_id = ? AND round_no = ?
                """, Integer.class, round.room(), round.number());
    }

    private String candidate(Round round, PickTokens.Pick pick) {
        Place place = pick.place();
        var existing = jdbc.query("""
                SELECT candidate_id, category FROM place_candidate WHERE room_id = ? AND round_no = ? AND provider_place_id = ?
                """, (rs, row) -> {
            if (!place.category().name().equals(rs.getString("category"))) {
                throw new VenueException(400, "CATEGORY_MISMATCH");
            }
            return rs.getString("candidate_id");
        }, round.room(), round.number(), pick.providerId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO place_candidate (room_id, round_no, candidate_id, category, provider_place_id,
                    name, address, lat, lng, detail_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, round.room(), round.number(), id, place.category().name(), pick.providerId(),
                place.name(), place.address(), place.lat(), place.lng(),
                place.detailUrl() == null ? null : place.detailUrl().toString());
        return id;
    }

    static Place place(ResultSet rs) throws SQLException {
        String url = rs.getString("detail_url");
        return new Place(rs.getString("name"), rs.getString("address"), rs.getDouble("lat"),
                rs.getDouble("lng"), Category.valueOf(rs.getString("category")), url == null ? null : URI.create(url));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Round(String room, int number, Instant closesAt, Instant closedAt, boolean extended,
                 boolean miniTimerUsed, Instant voteClosesAt, int voteExtensionCount, String recoveryReason) {}
}
