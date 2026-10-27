package com.example.aitask.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Instant;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class TaskStatusTest {
    static Stream<Object[]> transitions() {
        return Stream.of(TaskStatus.values()).flatMap(from -> Stream.of(TaskStatus.values())
                .map(to -> new Object[]{from, to}));
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void checksEveryTransition(TaskStatus from, TaskStatus to) {
        boolean legal = (from == TaskStatus.PENDING && (to == TaskStatus.RUNNING || to == TaskStatus.FAILED))
                || (from == TaskStatus.RUNNING && (to == TaskStatus.SUCCESS || to == TaskStatus.FAILED));
        assertEquals(legal, from.canTransitionTo(to));
        if (legal) {
            assertDoesNotThrow(() -> from.requireTransitionTo(to));
        } else {
            assertThrows(IllegalStateException.class, () -> from.requireTransitionTo(to));
        }
    }

    @Test
    void keepsSnapshotsAndTerminalFieldsConsistent() {
        Instant created = Instant.parse("2026-10-12T00:00:00Z");
        TextTask pending = TextTask.pending("test", "hello", created);
        TextTask running = pending.start(created.plusSeconds(1));
        TextTask success = running.succeed("result", created.plusSeconds(2));
        assertEquals(TaskStatus.PENDING, pending.status());
        assertEquals(created.plusSeconds(1), success.startedAt());
        assertEquals(created.plusSeconds(2), success.completedAt());
        assertNull(success.errorCode());
        assertThrows(IllegalStateException.class, () -> success.fail("ERROR", created));
        assertThrows(IllegalArgumentException.class, () -> running.succeed(" ", created));
        assertThrows(IllegalArgumentException.class, () -> running.fail(null, created));
        TextTask rejected = pending.fail("EXECUTION_REJECTED", created);
        assertNull(rejected.startedAt());
        assertNull(rejected.output());
        assertNotNull(rejected.completedAt());
    }
}
