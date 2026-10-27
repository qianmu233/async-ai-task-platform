package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deliberately NOT a database: limited SQL test double, serialized by a Java lock.
 * Exercises repository control flow, SQL predicates, mapping and transaction requests.
 * Does not verify MySQL syntax, row locks, driver behavior, isolation or deadlocks.
 */
final class OfflineJdbcFixture {
    static final Instant NOW = Instant.parse("2026-10-12T08:00:00.123456Z");
    record Stored(TextTask task, String normalizationVersion, long version) { }
    record Operation(long transactionId, String sql, List<Object> arguments) { }
    record Completion(long transactionId, boolean committed, boolean readOnly) { }

    final Map<UUID, Stored> rows = new HashMap<>();
    // Outbox INSERT control-flow contract only. Does NOT emulate dispatcher SQL or MySQL row locking.
    final Map<UUID, UUID> outboxEvents = new HashMap<>();
    final List<Operation> operations = new CopyOnWriteArrayList<>();
    final List<Completion> completions = new CopyOnWriteArrayList<>();
    final Transactions transactions = new Transactions();
    final Jdbc jdbc = new Jdbc();
    DataAccessException insertFailure;
    DataAccessException outboxFailure;
    Integer forcedUpdateCount;

    JdbcTaskRepository repository() {
        return new JdbcTaskRepository(jdbc, transactions);
    }

    final class Tx extends SimpleTransactionStatus {
        final long id;
        final boolean readOnly;
        final Map<UUID, Stored> before;
        final Map<UUID, UUID> outboxBefore;
        Tx(long id, boolean readOnly) {
            super(true);
            this.id = id;
            this.readOnly = readOnly;
            this.before = new HashMap<>(rows);
            this.outboxBefore = new HashMap<>(outboxEvents);
        }
    }

    final class Transactions implements PlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicLong sequence = new AtomicLong();
        private final ThreadLocal<Deque<Tx>> active = ThreadLocal.withInitial(ArrayDeque::new);

        boolean isActive() {
            return !active.get().isEmpty();
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertNotNull(definition);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
            assertEquals(10, definition.getTimeout());
            lock.lock();
            Tx tx = new Tx(sequence.incrementAndGet(), definition.isReadOnly());
            active.get().push(tx);
            return tx;
        }

        @Override
        public void commit(TransactionStatus status) {
            complete((Tx) status, true);
        }

        @Override
        public void rollback(TransactionStatus status) {
            Tx tx = (Tx) status;
            rows.clear();
            rows.putAll(tx.before);
            outboxEvents.clear();
            outboxEvents.putAll(tx.outboxBefore);
            complete(tx, false);
        }

        private void complete(Tx tx, boolean committed) {
            assertSame(tx, active.get().pop());
            completions.add(new Completion(tx.id, committed, tx.readOnly));
            tx.setCompleted();
            if (active.get().isEmpty()) {
                active.remove();
            }
            lock.unlock();
        }

        Tx current() {
            assertTrue(isActive(), "JDBC calls must be inside repository transaction boundaries");
            return active.get().peek();
        }
    }

    final class Jdbc extends JdbcTemplate {
        private final Pattern statusPredicate = Pattern.compile("status\\s*=\\s*'(PENDING|RUNNING)'");

        @Override
        public int update(String sql, Object... args) {
            Tx transaction = transactions.current();
            assertFalse(transaction.readOnly, "Writes must not be attempted in read-only transactions");
            operations.add(new Operation(transaction.id, sql, List.of(args)));
            if (sql.stripLeading().startsWith("INSERT INTO ai_task_outbox")) {
                outboxEvents.put(UUID.fromString((String) args[0]), UUID.fromString((String) args[1]));
                if (outboxFailure != null) { throw outboxFailure; }
                return 1;
            }
            if (sql.stripLeading().startsWith("INSERT")) {
                if (insertFailure != null) { throw insertFailure; }
                UUID id = UUID.fromString((String) args[0]);
                String key = (String) args[1];
                if (rows.containsKey(id) || rows.values().stream().anyMatch(row -> row.task.idempotencyKey().equals(key))) {
                    throw new DuplicateKeyException("Simulated unique constraint violation");
                }
                rows.put(id, new Stored(new TextTask(id, key, (String) args[2], TaskStatus.PENDING,
                        null, null, NOW, null, null), (String) args[3], 0));
                return 1;
            }
            if (forcedUpdateCount != null) { return forcedUpdateCount; }
            boolean claim = sql.contains("SET status = 'RUNNING'");
            boolean success = sql.contains("SET status = 'SUCCESS'");
            boolean fail = sql.contains("SET status = 'FAILED'");
            assertTrue(claim || success || fail, "Unsupported SQL in offline fixture");
            UUID id = UUID.fromString((String) args[claim ? 0 : 1]);
            Stored current = rows.get(id);
            if (current == null) { return 0; }
            String where = sql.substring(sql.indexOf("WHERE"));
            var predicate = statusPredicate.matcher(where);
            String expected = predicate.find() ? predicate.group(1)
                    : where.contains("status = ?") ? (String) args[2] : null;
            if (expected != null && !current.task.status().name().equals(expected)) { return 0; }
            TextTask before = current.task;
            // Apply SQL directly: intentionally do not use the production domain transition guard.
            TextTask after = new TextTask(id, before.idempotencyKey(), before.input(),
                    claim ? TaskStatus.RUNNING : success ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                    success ? (String) args[0] : null, fail ? (String) args[0] : null,
                    before.createdAt(), claim ? NOW : before.startedAt(), claim ? null : NOW);
            rows.put(id, new Stored(after, current.normalizationVersion, current.version + 1));
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            Tx transaction = transactions.current();
            operations.add(new Operation(transaction.id, sql, List.of(args)));
            Stored stored;
            if (sql.contains("WHERE idempotency_key = ?")) {
                stored = rows.values().stream().filter(row -> row.task.idempotencyKey().equals(args[0]))
                        .findFirst().orElse(null);
            } else {
                assertTrue(sql.contains("WHERE task_id = ?"));
                stored = rows.get(UUID.fromString((String) args[0]));
            }
            if (stored == null) { return List.of(); }
            try {
                return List.of(mapper.mapRow(resultSet(stored), 0));
            } catch (SQLException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    private ResultSet resultSet(Stored stored) {
        Map<String, Object> fields = new HashMap<>();
        TextTask task = stored.task;
        fields.put("task_id", task.id().toString());
        fields.put("idempotency_key", task.idempotencyKey());
        fields.put("input_text", task.input());
        fields.put("normalization_version", stored.normalizationVersion);
        fields.put("status", task.status().name());
        fields.put("output_text", task.output());
        fields.put("error_code", task.errorCode());
        fields.put("created_at", local(task.createdAt()));
        fields.put("started_at", local(task.startedAt()));
        fields.put("completed_at", local(task.completedAt()));
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getString") || method.getName().equals("getObject")) {
                        return fields.get((String) args[0]);
                    }
                    throw new UnsupportedOperationException("Unexpected ResultSet method: " + method.getName());
                });
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
