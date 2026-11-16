package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.VenueStateReader.*;
import com.likelion4thon.Meetdle.venue.RegistrationStore.Round;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static com.likelion4thon.Meetdle.venue.RegistrationStore.utc;

@Repository
class VoteResults {
    private static final List<Category> VISIT_ORDER = List.of(Category.FOOD, Category.CAFE, Category.CULTURE, Category.BAR);
    private static final Comparator<ScoredCandidate> RANK = Comparator
            .comparingInt(ScoredCandidate::score).reversed()
            .thenComparingInt(candidate -> candidate.aggregate().dislike())
            .thenComparing(Comparator.comparingInt((ScoredCandidate candidate) -> candidate.aggregate().like()).reversed());

    private final RegistrationStore store;

    VoteResults(RegistrationStore store) {
        this.store = store;
    }

    RoomPhase compute(Round round, int submittedCount, Instant now) {
        var byCategory = new EnumMap<Category, List<ScoredCandidate>>(Category.class);
        for (var candidate : scores(round)) {
            byCategory.computeIfAbsent(candidate.category(), ignored -> new ArrayList<>()).add(candidate);
        }
        var standings = new ArrayList<Standing>();
        boolean tied = false;
        int position = 0;
        for (Category category : VISIT_ORDER) {
            var candidates = byCategory.getOrDefault(category, List.of());
            if (candidates.size() == 1 && candidates.get(0).aggregate() == null) {
                standings.add(new Standing(candidates.get(0), "SELECTED", ++position));
                continue;
            }
            var eligible = candidates.stream().filter(candidate -> candidate.eligible(submittedCount))
                    .sorted(RANK).toList();
            if (eligible.isEmpty()) {
                for (var candidate : candidates) {
                    standings.add(new Standing(candidate, "EXCLUDED", null));
                }
                continue;
            }
            var best = eligible.get(0);
            var leaders = eligible.stream().takeWhile(candidate -> RANK.compare(best, candidate) == 0).toList();
            if (leaders.size() == 1) {
                standings.add(new Standing(best, "SELECTED", ++position));
            } else {
                tied = true;
                for (var leader : leaders) {
                    standings.add(new Standing(leader, "TIED", null));
                }
            }
            for (var candidate : candidates) {
                if (!leaders.contains(candidate)) {
                    standings.add(new Standing(candidate, candidate.eligible(submittedCount) ? "ELIGIBLE" : "EXCLUDED", null));
                }
            }
        }

        RoomPhase next = tied ? WAITING_FOR_HOST_DECISION : position > 0 ? FINAL_PLACE_CONFIRMED : RECOVERY_REQUIRED;
        store.jdbc().update("""
                INSERT INTO venue_result (room_id, round_no, result_revision, submitted_count, computed_at, finalized_at)
                VALUES (?, ?, 1, ?, ?, ?)
                """, round.room(), round.number(), submittedCount, utc(now), next == FINAL_PLACE_CONFIRMED ? utc(now) : null);
        for (var standing : standings) {
            var candidate = standing.candidate();
            var aggregate = candidate.aggregate();
            store.jdbc().update("""
                    INSERT INTO result_place (room_id, round_no, candidate_id, result_status,
                        like_count, neutral_count, dislike_count, score, visit_position)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, round.room(), round.number(), candidate.id(), standing.status(),
                    aggregate == null ? null : aggregate.like(), aggregate == null ? null : aggregate.neutral(),
                    aggregate == null ? null : aggregate.dislike(), aggregate == null ? null : candidate.score(),
                    next == FINAL_PLACE_CONFIRMED ? standing.position() : null);
        }
        return next;
    }

    private List<ScoredCandidate> scores(Round round) {
        return store.jdbc().query("""
                SELECT b.candidate_id, c.category, b.mode,
                    SUM(CASE WHEN m.submitted_at IS NOT NULL AND p.choice = 'LIKE' THEN 1 ELSE 0 END) AS likes,
                    SUM(CASE WHEN m.submitted_at IS NOT NULL AND p.choice = 'NEUTRAL' THEN 1 ELSE 0 END) AS neutrals,
                    SUM(CASE WHEN m.submitted_at IS NOT NULL AND p.choice = 'DISLIKE' THEN 1 ELSE 0 END) AS dislikes
                FROM ballot_candidate b
                JOIN place_candidate c ON c.room_id = b.room_id AND c.round_no = b.round_no
                    AND c.candidate_id = b.candidate_id
                LEFT JOIN vote_preference p ON p.room_id = b.room_id AND p.round_no = b.round_no
                    AND p.candidate_id = b.candidate_id
                LEFT JOIN round_member m ON m.room_id = p.room_id AND m.round_no = p.round_no
                    AND m.participant_id = p.participant_id
                WHERE b.room_id = ? AND b.round_no = ?
                GROUP BY b.candidate_id, c.category, b.mode
                """, (rs, row) -> new ScoredCandidate(rs.getString("candidate_id"),
                Category.valueOf(rs.getString("category")), "AUTO_SELECTED".equals(rs.getString("mode")) ? null
                : new Aggregate(rs.getInt("likes"), rs.getInt("neutrals"), rs.getInt("dislikes"))),
                round.room(), round.number());
    }

    FinalResult finalized(Round round) {
        var header = store.jdbc().query("""
                SELECT result_revision, submitted_count FROM venue_result
                WHERE room_id = ? AND round_no = ? AND finalized_at IS NOT NULL
                """, (rs, row) -> new ResultHeader(rs.getLong(1), rs.getInt(2)), round.room(), round.number());
        if (header.isEmpty()) {
            return null;
        }
        var places = resultPlaces(round);
        var selected = places.stream().filter(place -> "SELECTED".equals(place.status()))
                .sorted(Comparator.comparingInt(PersistedPlace::position))
                .map(PersistedPlace::place).toList();
        var details = places.stream().filter(place -> !"EXCLUDED".equals(place.status()))
                .map(PersistedPlace::place).toList();
        var result = header.get(0);
        return new FinalResult(round.number(), result.revision(), result.submittedCount(), selected, details,
                selected.stream().map(ResultPlace::candidateId).toList());
    }

    TieDecision ties(Round round) {
        Long revision = store.jdbc().queryForObject("""
                SELECT result_revision FROM venue_result WHERE room_id = ? AND round_no = ? AND finalized_at IS NULL
                """, Long.class, round.room(), round.number());
        var groups = new EnumMap<Category, List<ResultPlace>>(Category.class);
        for (var place : resultPlaces(round)) {
            if ("TIED".equals(place.status())) {
                groups.computeIfAbsent(place.place().place().category(), ignored -> new ArrayList<>()).add(place.place());
            }
        }
        return new TieDecision(round.number(), revision, groups.entrySet().stream()
                .map(entry -> new TieGroup(entry.getKey(), entry.getValue())).toList());
    }

    private List<PersistedPlace> resultPlaces(Round round) {
        return store.jdbc().query("""
                SELECT c.*, r.result_status, r.visit_position, r.like_count, r.neutral_count, r.dislike_count
                FROM result_place r JOIN place_candidate c ON c.room_id = r.room_id
                    AND c.round_no = r.round_no AND c.candidate_id = r.candidate_id
                WHERE r.room_id = ? AND r.round_no = ? ORDER BY c.category, c.candidate_id
                """, (rs, row) -> new PersistedPlace(new ResultPlace(UUID.fromString(rs.getString("candidate_id")),
                RegistrationStore.place(rs), aggregate(rs)), rs.getString("result_status"), rs.getInt("visit_position")),
                round.room(), round.number());
    }

    private static Aggregate aggregate(ResultSet rs) throws SQLException {
        return rs.getObject("like_count") == null ? null
                : new Aggregate(rs.getInt("like_count"), rs.getInt("neutral_count"), rs.getInt("dislike_count"));
    }

    private record ResultHeader(long revision, int submittedCount) {}
    private record PersistedPlace(ResultPlace place, String status, int position) {}
    private record Standing(ScoredCandidate candidate, String status, Integer position) {}

    private record ScoredCandidate(String id, Category category, Aggregate aggregate) {
        int score() {
            return 2 * aggregate.like() + aggregate.neutral() - 3 * aggregate.dislike();
        }

        boolean eligible(int submittedCount) {
            return score() >= 0 && aggregate.dislike() * 2 <= submittedCount;
        }
    }
}
