package com.rsmaxwell.diaries.web.projection;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProjectionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final long quietPeriodMillis;
    private final long replayTimeoutMillis;
    private final ExecutorService updater;
    private final ScheduledExecutorService timer;
    private final AtomicReference<ProjectionSnapshot> snapshot = new AtomicReference<>(ProjectionSnapshot.empty());
    private final AtomicReference<ProjectionStatus> status = new AtomicReference<>(ProjectionStatus.starting());

    private MutableProjectionState active = new MutableProjectionState();
    private MutableProjectionState staging;
    private long generation;
    private long replayEpoch;
    private boolean subscriptionsAcknowledged;
    private ScheduledFuture<?> quietFuture;
    private ScheduledFuture<?> timeoutFuture;
    private boolean closed;

    public ProjectionService(Duration quietPeriod, Duration replayTimeout) {
        this.quietPeriodMillis = positiveMillis(quietPeriod, "quiet period");
        this.replayTimeoutMillis = positiveMillis(replayTimeout, "replay timeout");
        if (replayTimeoutMillis <= quietPeriodMillis) {
            throw new IllegalArgumentException("replay timeout must exceed quiet period");
        }
        this.updater = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diaries-web-projection");
            thread.setDaemon(true);
            return thread;
        });
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diaries-web-replay-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static long positiveMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        long millis = duration.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return millis;
    }

    public ProjectionSnapshot snapshot() {
        return snapshot.get();
    }

    public ProjectionStatus status() {
        return status.get();
    }

    public CompletableFuture<Void> beginReplay(boolean reconnect) {
        return submit(() -> {
            ensureOpen();
            replayEpoch++;
            staging = new MutableProjectionState();
            subscriptionsAcknowledged = false;
            cancelReplayTimers();
            Instant now = Instant.now();
            ProjectionStatus previous = status.get();
            status.set(new ProjectionStatus(
                    true,
                    false,
                    false,
                    reconnect ? "rebuilding after reconnect" : "replaying retained state",
                    replayEpoch,
                    now,
                    previous.lastAcceptedUpdateAt(),
                    previous.invalidMessageCount(),
                    previous.tombstoneCount()));
            snapshot.set(ProjectionSnapshot.build(generation, active, SourceConnectionState.REPLAYING));
            long epoch = replayEpoch;
            timeoutFuture = timer.schedule(
                    () -> updater.execute(() -> replayTimedOut(epoch)),
                    replayTimeoutMillis,
                    TimeUnit.MILLISECONDS);
            log.info("Starting retained replay generation {} (reconnect={})", epoch, reconnect);
        });
    }

    public CompletableFuture<Void> subscriptionsAcknowledged() {
        return submit(() -> {
            ensureOpen();
            if (staging == null) {
                return;
            }
            subscriptionsAcknowledged = true;
            ProjectionStatus previous = status.get();
            status.set(new ProjectionStatus(
                    true,
                    true,
                    false,
                    previous.reason(),
                    previous.replayEpoch(),
                    previous.replayStartedAt(),
                    previous.lastAcceptedUpdateAt(),
                    previous.invalidMessageCount(),
                    previous.tombstoneCount()));
            scheduleQuietCompletion(replayEpoch);
        });
    }

    public CompletableFuture<Void> accept(ProjectionEvent event) {
        Objects.requireNonNull(event, "event");
        return submit(() -> {
            ensureOpen();
            ProjectionStatus previous = status.get();
            long tombstones = previous.tombstoneCount()
                    + (event instanceof ProjectionEvent.Tombstone ? 1 : 0);
            Instant acceptedAt = Instant.now();

            if (staging != null) {
                staging.apply(event);
                status.set(copyStatus(previous, acceptedAt, previous.invalidMessageCount(), tombstones));
                if (subscriptionsAcknowledged) {
                    scheduleQuietCompletion(replayEpoch);
                }
                return;
            }

            boolean changed = active.apply(event);
            status.set(copyStatus(previous, acceptedAt, previous.invalidMessageCount(), tombstones));
            if (changed) {
                generation++;
                snapshot.set(ProjectionSnapshot.build(generation, active, SourceConnectionState.READY));
            }
        });
    }

    public CompletableFuture<Void> recordInvalidMessage() {
        return submit(() -> {
            ProjectionStatus previous = status.get();
            status.set(copyStatus(
                    previous,
                    previous.lastAcceptedUpdateAt(),
                    previous.invalidMessageCount() + 1,
                    previous.tombstoneCount()));
        });
    }

    public CompletableFuture<Void> disconnected(String reason) {
        return submit(() -> {
            staging = null;
            subscriptionsAcknowledged = false;
            cancelReplayTimers();
            ProjectionStatus previous = status.get();
            status.set(new ProjectionStatus(
                    false,
                    false,
                    false,
                    reason == null || reason.isBlank() ? "MQTT disconnected" : reason,
                    previous.replayEpoch(),
                    previous.replayStartedAt(),
                    previous.lastAcceptedUpdateAt(),
                    previous.invalidMessageCount(),
                    previous.tombstoneCount()));
            snapshot.set(ProjectionSnapshot.build(generation, active, SourceConnectionState.DISCONNECTED));
        });
    }

    public CompletableFuture<Void> failed(String reason, Throwable throwable) {
        return submit(() -> {
            staging = null;
            cancelReplayTimers();
            ProjectionStatus previous = status.get();
            status.set(new ProjectionStatus(
                    previous.mqttConnected(),
                    previous.subscriptionsAcknowledged(),
                    false,
                    reason,
                    previous.replayEpoch(),
                    previous.replayStartedAt(),
                    previous.lastAcceptedUpdateAt(),
                    previous.invalidMessageCount(),
                    previous.tombstoneCount()));
            snapshot.set(ProjectionSnapshot.build(generation, active, SourceConnectionState.FAILED));
            log.error("Projection failed: {}", reason, throwable);
        });
    }

    private ProjectionStatus copyStatus(
            ProjectionStatus previous,
            Instant acceptedAt,
            long invalidMessages,
            long tombstones) {
        return new ProjectionStatus(
                previous.mqttConnected(),
                previous.subscriptionsAcknowledged(),
                previous.ready(),
                previous.reason(),
                previous.replayEpoch(),
                previous.replayStartedAt(),
                acceptedAt,
                invalidMessages,
                tombstones);
    }

    private void scheduleQuietCompletion(long epoch) {
        if (quietFuture != null) {
            quietFuture.cancel(false);
        }
        quietFuture = timer.schedule(
                () -> updater.execute(() -> completeReplay(epoch)),
                quietPeriodMillis,
                TimeUnit.MILLISECONDS);
    }

    private void completeReplay(long epoch) {
        if (closed || staging == null || epoch != replayEpoch || !subscriptionsAcknowledged) {
            return;
        }
        active = staging;
        staging = null;
        generation++;
        cancelReplayTimers();
        ProjectionSnapshot next = ProjectionSnapshot.build(generation, active, SourceConnectionState.READY);
        snapshot.set(next);
        ProjectionStatus previous = status.get();
        status.set(new ProjectionStatus(
                true,
                true,
                true,
                "ready",
                previous.replayEpoch(),
                previous.replayStartedAt(),
                previous.lastAcceptedUpdateAt(),
                previous.invalidMessageCount(),
                previous.tombstoneCount()));
        log.info(
                "Published replay generation {} as snapshot {} (diaries={}, pages={}, fragments={}, marquees={})",
                epoch,
                generation,
                next.diariesById().size(),
                next.pagesById().size(),
                next.fragmentsById().size(),
                next.marqueesById().size());
    }

    private void replayTimedOut(long epoch) {
        if (closed || staging == null || epoch != replayEpoch) {
            return;
        }
        staging = null;
        if (quietFuture != null) {
            quietFuture.cancel(false);
            quietFuture = null;
        }
        ProjectionStatus previous = status.get();
        status.set(new ProjectionStatus(
                previous.mqttConnected(),
                previous.subscriptionsAcknowledged(),
                false,
                "retained replay timed out",
                previous.replayEpoch(),
                previous.replayStartedAt(),
                previous.lastAcceptedUpdateAt(),
                previous.invalidMessageCount(),
                previous.tombstoneCount()));
        log.warn("Retained replay generation {} timed out", epoch);
    }

    private void cancelReplayTimers() {
        if (quietFuture != null) {
            quietFuture.cancel(false);
            quietFuture = null;
        }
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private CompletableFuture<Void> submit(Runnable task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        updater.execute(() -> {
            try {
                task.run();
                result.complete(null);
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("projection service is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelReplayTimers();
        updater.shutdown();
        timer.shutdown();
        try {
            updater.awaitTermination(5, TimeUnit.SECONDS);
            timer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
