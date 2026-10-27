package com.example.aitask.outbox;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

/** DB time + version CAS. No row lock or transaction is held during broker I/O. */
public final class JdbcOutboxRepository implements OutboxRepository {
    public static final String INSERT = """
            INSERT INTO ai_task_outbox
                (event_id, task_id, event_type, status, attempts, next_attempt_at, created_at, updated_at, version)
            VALUES (?, ?, 'TASK_SUBMITTED', 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
            """;
    private static final String DUE = """
            ((status IN ('PENDING','RETRY') AND next_attempt_at <= UTC_TIMESTAMP(6))
             OR (status = 'DISPATCHING' AND locked_until <= UTC_TIMESTAMP(6)))
            """;
    private static final String OWNED = """
            WHERE event_id = ? AND status = 'DISPATCHING' AND claim_token = ?
                  AND locked_until > UTC_TIMESTAMP(6)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxSettings settings;
    private final UUID taskScope;
    private final String acceptancePrefix;
    private record Candidate(UUID id, UUID taskId, String type, int attempts, long version) { }

    public JdbcOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxSettings settings) {
        this(jdbc, manager, settings, null);
    }

    /** Optional narrow scope for targeted dispatch/data-isolated acceptance; the production scheduler uses no scope. */
    public JdbcOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxSettings settings, UUID taskScope) {
        this(jdbc, manager, settings, taskScope, "");
    }

    public JdbcOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxSettings settings,
                               UUID taskScope, String acceptancePrefix) {
        this.acceptancePrefix = com.example.aitask.service.TaskInputRules.validateAcceptancePrefix(acceptancePrefix);
        this.jdbc = jdbc;
        this.settings = settings;
        this.taskScope = taskScope;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public void verifySchema() {
        // Read-only startup gate: missing/unmigrated schema cannot fall back to direct publishing.
        jdbc.queryForList("""
                SELECT event_id, task_id, event_type, status, attempts, next_attempt_at, claim_token,
                       locked_until, sent_at, isolated_at, last_error_code, created_at, updated_at, version
                FROM ai_task_outbox WHERE 1 = 0
                """);
    }

    @Override
    public Acquisition claim() {
        return Objects.requireNonNull(transaction.execute(tx -> {
            var arguments = new java.util.ArrayList<Object>();
            if (taskScope != null) { arguments.add(taskScope.toString()); }
            if (!acceptancePrefix.isEmpty()) { arguments.add(acceptancePrefix + "%"); }
            var rows = jdbc.query("SELECT event_id, task_id, event_type, attempts, version FROM ai_task_outbox WHERE "
                            + DUE + (taskScope == null ? "" : " AND task_id = ?")
                            + (acceptancePrefix.isEmpty() ? "" : " AND task_id IN (SELECT task_id FROM ai_text_task WHERE idempotency_key LIKE ?)")
                            + " ORDER BY next_attempt_at, event_id LIMIT 1", (row, number) -> new Candidate(
                    UUID.fromString(row.getString("event_id")), UUID.fromString(row.getString("task_id")),
                    row.getString("event_type"), row.getInt("attempts"), row.getLong("version")),
                    arguments.toArray());
            if (rows.isEmpty()) { return Acquisition.empty(); }
            Candidate row = rows.get(0);
            if (row.attempts() >= settings.maxAttempts()) {
                int count = jdbc.update("""
                        UPDATE ai_task_outbox SET status = 'ISOLATED', claim_token = NULL, locked_until = NULL,
                            isolated_at = UTC_TIMESTAMP(6), last_error_code = 'ATTEMPTS_EXHAUSTED',
                            updated_at = UTC_TIMESTAMP(6), version = version + 1
                        WHERE event_id = ? AND version = ? AND
                        """ + DUE, row.id().toString(), row.version());
                return new Acquisition(null, changed(count));
            }
            UUID token = UUID.randomUUID();
            int count = jdbc.update("""
                    UPDATE ai_task_outbox SET status = 'DISPATCHING', attempts = attempts + 1, claim_token = ?,
                        locked_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                        updated_at = UTC_TIMESTAMP(6), version = version + 1
                    WHERE event_id = ? AND version = ? AND
                    """ + DUE, token.toString(), settings.leaseMillis() * 1000, row.id().toString(), row.version());
            return changed(count) ? new Acquisition(new Claim(row.id(), row.taskId(), row.type(), row.attempts() + 1, token), false)
                    : Acquisition.empty();
        }));
    }

    @Override
    public boolean markSent(Claim claim) {
        return Objects.requireNonNull(transaction.execute(tx -> changed(jdbc.update("""
                UPDATE ai_task_outbox SET status = 'SENT', claim_token = NULL, locked_until = NULL,
                    sent_at = UTC_TIMESTAMP(6), last_error_code = NULL, updated_at = UTC_TIMESTAMP(6), version = version + 1
                """ + OWNED, claim.eventId().toString(), claim.token().toString()))));
    }

    @Override
    public boolean markFailure(Claim claim, String code, long retryDelayMillis, boolean isolate) {
        if (!code.matches("[A-Z_]{1,64}") || retryDelayMillis < 0 || retryDelayMillis > settings.maxBackoffMillis()) {
            throw new IllegalArgumentException("Invalid Outbox failure code or backoff");
        }
        return Objects.requireNonNull(transaction.execute(tx -> changed(jdbc.update("""
                UPDATE ai_task_outbox SET status = ?, claim_token = NULL, locked_until = NULL,
                    next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                    isolated_at = CASE WHEN ? = 'ISOLATED' THEN UTC_TIMESTAMP(6) ELSE NULL END,
                    last_error_code = ?, updated_at = UTC_TIMESTAMP(6), version = version + 1
                """ + OWNED, isolate ? "ISOLATED" : "RETRY", retryDelayMillis * 1000,
                isolate ? "ISOLATED" : "RETRY", code, claim.eventId().toString(), claim.token().toString()))));
    }

    private boolean changed(int count) {
        if (count < 0 || count > 1) { throw new DataIntegrityViolationException("Outbox update must affect at most one event"); }
        return count == 1;
    }
}
