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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

        RoomPhase next = tied ? WAITING_FOR_HOST_DECISION : position > 0 ? RESULT_READY : RECOVERY_REQUIRED;
        store.jdbc().update("""
                INSERT INTO venue_result (room_id, round_no, result_revision, submitted_count, computed_at, finalized_at)
                VALUES (?, ?, 1, ?, ?, ?)
                """, round.room(), round.number(), submittedCount, utc(now), null);
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
                    standing.position());
        }
        return next;
    }

    void resolve(Round round, long revision, List<PlaceVoting.TieChoice> choices, Instant now) {
        var ties = ties(round);
        if (ties == null || ties.resultRevision() != revision) {
            throw PlaceRegistration.conflict("STALE_RESULT");
        }
        var winners = new EnumMap<Category, UUID>(Category.class);
        if (choices == null || choices.size() != ties.groups().size() || choices.isEmpty()) {
            throw new VenueException(400, "INVALID_TIE_CHOICE");
        }
        for (var choice : choices) {
            if (choice == null || choice.category() == null || choice.candidateId() == null
                    || ties.groups().stream().noneMatch(group -> group.category() == choice.category()
                        && group.candidates().stream().anyMatch(place -> place.candidateId().equals(choice.candidateId())))
                    || winners.putIfAbsent(choice.category(), choice.candidateId()) != null) {
                throw new VenueException(400, "INVALID_TIE_CHOICE");
            }
        }
        store.jdbc().update("""
                UPDATE result_place SET result_status = 'ELIGIBLE'
                WHERE room_id = ? AND round_no = ? AND result_status = 'TIED'
                """, round.room(), round.number());
        for (UUID winner : winners.values()) {
            store.jdbc().update("""
                    UPDATE result_place SET result_status = 'SELECTED'
                    WHERE room_id = ? AND round_no = ? AND candidate_id = ?
                    """, round.room(), round.number(), winner.toString());
        }
        var order = resultPlaces(round).stream().filter(place -> "SELECTED".equals(place.status()))
                .sorted(Comparator.comparingInt(place -> VISIT_ORDER.indexOf(place.place().place().category())))
                .map(place -> place.place().candidateId()).toList();
        saveOrder(round, order);
        store.jdbc().update("""
                UPDATE venue_result SET result_revision = result_revision + 1
                WHERE room_id = ? AND round_no = ?
                """, round.room(), round.number());
    }

    boolean reorder(Round round, long revision, List<UUID> order) {
        var result = read(round);
        if (result == null || result.resultRevision() != revision) {
            throw PlaceRegistration.conflict("STALE_RESULT");
        }
        var current = result.visitOrder();
        if (order == null || order.size() != current.size() || order.stream().anyMatch(Objects::isNull)
                || !new HashSet<>(current).equals(new HashSet<>(order))) {
            throw new VenueException(400, "INVALID_VISIT_ORDER");
        }
        if (current.equals(order)) {
            return false;
        }
        saveOrder(round, order);
        store.jdbc().update("""
                UPDATE venue_result SET result_revision = result_revision + 1
                WHERE room_id = ? AND round_no = ?
                """, round.room(), round.number());
        return true;
    }

    private void saveOrder(Round round, List<UUID> order) {
        store.jdbc().update("""
                UPDATE result_place SET visit_position = NULL WHERE room_id = ? AND round_no = ?
                """, round.room(), round.number());
        for (int index = 0; index < order.size(); index++) {
            store.jdbc().update("""
                    UPDATE result_place SET visit_position = ?
                    WHERE room_id = ? AND round_no = ? AND candidate_id = ?
                    """, index + 1, round.room(), round.number(), order.get(index).toString());
        }
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

    FinalResult read(Round round) {
        var header = store.jdbc().query("""
                SELECT result_revision, submitted_count, finalized_at FROM venue_result
                WHERE room_id = ? AND round_no = ?
                """, (rs, row) -> new ResultHeader(rs.getLong(1), rs.getInt(2), rs.getObject(3) != null), round.room(), round.number());
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
                selected.stream().map(ResultPlace::candidateId).toList(), result.confirmed());
    }

    TieDecision ties(Round round) {
        var revisions = store.jdbc().query("""
                SELECT result_revision FROM venue_result WHERE room_id = ? AND round_no = ? AND finalized_at IS NULL
                """, (rs, row) -> rs.getLong(1), round.room(), round.number());
        if (revisions.isEmpty()) {
            return null;
        }
        var groups = new EnumMap<Category, List<ResultPlace>>(Category.class);
        for (var place : resultPlaces(round)) {
            if ("TIED".equals(place.status())) {
                groups.computeIfAbsent(place.place().place().category(), ignored -> new ArrayList<>()).add(place.place());
            }
        }
        return new TieDecision(round.number(), revisions.get(0), groups.entrySet().stream()
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

    void confirm(Round round, long revision, Instant now) {
        var result = read(round);
        if (result == null || result.resultRevision() != revision) {
            throw PlaceRegistration.conflict("STALE_RESULT");
        }
        if (result.confirmed()) {
            return;
        }
        store.jdbc().update("UPDATE venue_result SET finalized_at = ? WHERE room_id = ? AND round_no = ?",
                utc(now), round.room(), round.number());
    }

    private record ResultHeader(long revision, int submittedCount, boolean confirmed) {}
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
