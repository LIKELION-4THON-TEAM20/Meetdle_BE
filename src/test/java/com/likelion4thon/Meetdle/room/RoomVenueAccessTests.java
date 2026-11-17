package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.LockedRoomVenue;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.RoomVenueSnapshot;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.PlanningOptions;
import com.likelion4thon.Meetdle.planning.options.PlanningOptionsService;
import com.likelion4thon.Meetdle.planning.selection.MeetingSelectionService;
import com.likelion4thon.Meetdle.planning.selection.dto.SelectMeetingRequest;
import com.likelion4thon.Meetdle.planning.subway.SubwayData;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

/**
 * 창환님 장소 코드가 쓰는 RoomVenueAccess(방 창구) 구현 테스트.
 * 각 테스트는 트랜잭션 안에서 돌고 끝나면 되돌린다 (lockForWrite는 쓰기 트랜잭션이 필요).
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(RoomApiTestConfig.class)
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class RoomVenueAccessTests {

    private static final UUID ROOM = BoundaryFixtures.ROOM_ID;
    private static final AuthenticatedUser HOST = BoundaryFixtures.HOST;
    private static final UUID MEMBER_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private RoomVenueAccess access;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlanningOptionsService planningOptionsService;

    @Autowired
    private MeetingSelectionService meetingSelectionService;

    @BeforeEach
    void resetClock() {
        clock.set(BoundaryFixtures.NOW);
    }

    @Test
    void readGivesMemberSnapshot() {
        RoomVenueSnapshot snapshot = access.read(ROOM, MEMBER_USER);

        assertThat(snapshot.roomId()).isEqualTo(ROOM);
        assertThat(snapshot.phase()).isEqualTo(RoomPhase.WAITING_FOR_DEPARTURE);
        assertThat(snapshot.revision()).isZero();
        assertThat(snapshot.capacity()).isEqualTo(4);
        assertThat(snapshot.actorMember()).contains(
                new RoomMember(BoundaryFixtures.MEMBER_PARTICIPANT, RoomMember.Role.MEMBER));
        assertThat(snapshot.memberParticipantIds()).containsExactlyInAnyOrder(BoundaryFixtures.HOST_PARTICIPANT,
                BoundaryFixtures.MEMBER_PARTICIPANT, BoundaryFixtures.MEMBER_WITHOUT_PLANNING_INPUT);
        assertThat(snapshot.selectedMeeting()).isEmpty();
        assertThat(snapshot.now()).isEqualTo(BoundaryFixtures.NOW);
    }

    @Test
    void readRejectsOutsidersAndUnknownRooms() {
        assertThatThrownBy(() -> access.read(ROOM, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> access.read(UUID.randomUUID(), HOST.userId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void snapshotCarriesConfirmedMeeting() {
        complete("20000000-0000-0000-0000-000000000001", "강남");
        complete("20000000-0000-0000-0000-000000000002", "홍대입구");
        PlanningOptions options = planningOptionsService.calculate(HOST, ROOM, 0).options();
        var meeting = meetingSelectionService.select(HOST, ROOM, new SelectMeetingRequest(options.optionsId(),
                options.slots().get(0).slotId(), options.regions().get(0).candidateId(), null));

        RoomVenueSnapshot snapshot = access.read(ROOM, HOST.userId());

        assertThat(snapshot.phase()).isEqualTo(RoomPhase.REGION_CONFIRMED);
        assertThat(snapshot.selectedMeeting()).contains(meeting);
        assertThat(snapshot.revision()).isEqualTo(2); // 계산 1 + 확정 1
    }

    @Test
    void lockKnowsWhoIsActing() {
        LockedRoomVenue byHost = access.lockForWrite(ROOM, new RoomVenueAccess.UserActor(HOST.userId()));
        assertThat(byHost.snapshot().actorMember().map(RoomMember::role)).contains(RoomMember.Role.HOST);

        LockedRoomVenue byScheduler = access.lockForWrite(ROOM, RoomVenueAccess.SystemActor.DEADLINE_SCHEDULER);
        assertThat(byScheduler.snapshot().actorMember()).isEmpty();

        LockedRoomVenue byStranger = access.lockForWrite(ROOM, new RoomVenueAccess.UserActor(UUID.randomUUID()));
        assertThat(byStranger.snapshot().actorMember()).isEmpty();
    }

    @Test
    void recordChangeFollowsPhaseRulesAndAlwaysBumpsRevision() {
        jdbc.update("UPDATE room SET phase = 'REGION_CONFIRMED' WHERE room_id = ?", ROOM.toString());
        LockedRoomVenue lock = access.lockForWrite(ROOM, new RoomVenueAccess.UserActor(HOST.userId()));

        access.recordChange(lock, RoomPhase.PLACE_SUBMISSION_OPEN);
        assertThat(phase()).isEqualTo("PLACE_SUBMISSION_OPEN");
        assertThat(revision()).isEqualTo(1);

        // 단계 그대로 (예: 마감 연장) → revision만 +1
        access.recordChange(lock, RoomPhase.PLACE_SUBMISSION_OPEN);
        assertThat(revision()).isEqualTo(2);

        // 규칙에 없는 점프는 막는다: 등록 중 → 바로 투표 중 (X)
        assertThatThrownBy(() -> access.recordChange(lock, RoomPhase.VOTING_OPEN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(phase()).isEqualTo("PLACE_SUBMISSION_OPEN");
    }

    @Test
    void lockNeedsTransaction() throws InterruptedException {
        // 트랜잭션이 없는 다른 스레드에서 부르면 막힌다 (잠금이 바로 풀려 버리니까)
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread outside = new Thread(() -> {
            try {
                access.lockForWrite(ROOM, RoomVenueAccess.SystemActor.DEADLINE_SCHEDULER);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        outside.start();
        outside.join();
        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
    }

    private String phase() {
        return jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?", String.class, ROOM.toString());
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM room WHERE room_id = ?", Long.class, ROOM.toString());
    }

    private void complete(String participantId, String stationName) {
        String station = SubwayData.stationIdOf(SubwayData.key(stationName)).toString();
        jdbc.update("""
                        INSERT INTO member_input (participant_id, origin_station_id, destination_station_id,
                                                  input_revision, completed_at)
                        VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP(6))
                        """, participantId, station, station);
        for (int hour : new int[] {19, 20}) {
            jdbc.update("INSERT INTO member_availability (participant_id, available_date, hour_of_day) VALUES (?, ?, ?)",
                    participantId, java.sql.Date.valueOf("2030-01-02"), hour);
        }
    }
}
