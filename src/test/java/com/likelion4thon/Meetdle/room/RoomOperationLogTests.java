package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion4thon.Meetdle.contract.RoomPhase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class RoomOperationLogTests {
    @Test
    void logsPhaseChangeOnlyAfterCommit(CapturedOutput output) {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:room-operation-log", "sa", "")));
        UUID rolledBackRoom = UUID.randomUUID();
        transactions.executeWithoutResult(transaction -> {
            RoomOperationLog.phaseChanged(rolledBackRoom, RoomPhase.REGION_CONFIRMED, RoomPhase.PLACE_SUBMISSION_OPEN);
            transaction.setRollbackOnly();
        });
        assertThat(output.getOut()).doesNotContain(rolledBackRoom.toString());

        UUID committedRoom = UUID.randomUUID();
        transactions.executeWithoutResult(transaction -> {
            RoomOperationLog.phaseChanged(committedRoom, RoomPhase.REGION_CONFIRMED, RoomPhase.PLACE_SUBMISSION_OPEN);
            assertThat(output.getOut()).doesNotContain(committedRoom.toString());
        });
        assertThat(output.getOut()).contains("room_phase_changed requestId=- roomId=" + committedRoom
                + " from=REGION_CONFIRMED to=PLACE_SUBMISSION_OPEN");
    }
}
