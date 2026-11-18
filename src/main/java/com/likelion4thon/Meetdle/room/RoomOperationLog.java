package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.common.logging.RequestOperationLog;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

public final class RoomOperationLog {
    private static final Logger log = LoggerFactory.getLogger(RoomOperationLog.class);

    private RoomOperationLog() {}

    public static void created(UUID roomId, int capacity) {
        String requestId = RequestOperationLog.currentRequestId();
        afterCommit(() -> log.info("room_created requestId={} roomId={} capacity={}", requestId, roomId, capacity));
    }

    public static void phaseChanged(UUID roomId, RoomPhase from, RoomPhase to) {
        if (from != to) {
            String requestId = RequestOperationLog.currentRequestId();
            afterCommit(() -> log.info("room_phase_changed requestId={} roomId={} from={} to={}",
                    requestId, roomId, from, to));
        }
    }

    private static void afterCommit(Runnable writeLog) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeLog.run();
                }
            });
        }
    }
}
