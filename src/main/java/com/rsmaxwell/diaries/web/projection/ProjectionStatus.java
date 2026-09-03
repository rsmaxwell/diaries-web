package com.rsmaxwell.diaries.web.projection;

import java.time.Instant;

public record ProjectionStatus(
        boolean mqttConnected,
        boolean subscriptionsAcknowledged,
        boolean ready,
        String reason,
        long replayEpoch,
        Instant replayStartedAt,
        Instant lastAcceptedUpdateAt,
        long invalidMessageCount,
        long tombstoneCount) {

    public static ProjectionStatus starting() {
        return new ProjectionStatus(false, false, false, "starting", 0, null, null, 0, 0);
    }
}
