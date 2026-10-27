package com.example.aitask.repository;

import com.example.aitask.outbox.OutboxSubmissionDispatcher;
import com.example.aitask.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Offline transaction/control-flow evidence only; reuse existing fixture, no real DB claim. */
class TaskOutboxTransactionTest {
    @Test
    void registrationAndEventShareOneCommitAndKeyReuseDoesNotAppendOrPublishAgain() {
        var fixture = new OfflineJdbcFixture();
        var repository = new JdbcTaskRepository(fixture.jdbc, fixture.transactions, true);
        var service = new TaskService(repository, new OutboxSubmissionDispatcher());
        var first = service.submit("outbox-key", "hello");
        assertThat(first.created()).isTrue();
        assertThat(fixture.outboxEvents).hasSize(1).containsValue(first.task().id());
        var inserts = fixture.operations.stream().filter(op -> op.sql().stripLeading().startsWith("INSERT")).toList();
        assertThat(inserts).hasSize(2);
        assertThat(inserts.get(0).transactionId()).isEqualTo(inserts.get(1).transactionId());
        assertThat(fixture.completions).hasSize(1);
        assertThat(fixture.completions.get(0).committed()).isTrue();
        var reused = service.submit("outbox-key", "hello");
        assertThat(reused.created()).isFalse();
        assertThat(reused.task().id()).isEqualTo(first.task().id());
        assertThat(fixture.outboxEvents).hasSize(1);
    }

    @Test
    void outboxFailureRollsBackBothWritesAndIsNotReinterpretedAsTaskIdempotency() {
        for (var failure : List.of(new DataAccessResourceFailureException("synthetic storage outage"),
                new DuplicateKeyException("synthetic event collision"))) {
            var fixture = new OfflineJdbcFixture();
            fixture.outboxFailure = failure; // Failure after fixture writes both entries; rollback restores BOTH snapshots.
            var repository = new JdbcTaskRepository(fixture.jdbc, fixture.transactions, true);
            assertThatThrownBy(() -> repository.createOrGet("rollback", "hello")).isSameAs(failure);
            assertThat(fixture.rows).isEmpty();
            assertThat(fixture.outboxEvents).isEmpty();
            assertThat(fixture.completions).hasSize(1);
            assertThat(fixture.completions.get(0).committed()).isFalse();
            assertThat(fixture.operations).noneMatch(op -> op.sql().contains("WHERE idempotency_key = ?"));
        }
    }
}
