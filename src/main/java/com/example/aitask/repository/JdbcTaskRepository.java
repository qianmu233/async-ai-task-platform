package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.service.TaskException;
import com.example.aitask.service.TaskInputRules;
import com.example.aitask.outbox.JdbcOutboxRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL-only SQL. No JVM locks, background scan, schema initialization, or model calls. */
public class JdbcTaskRepository implements TaskRepository {
    static final String SELECT = """
            SELECT task_id, idempotency_key, input_text, normalization_version, status,
                   output_text, error_code, created_at, started_at, completed_at
            FROM ai_text_task
            """;
    static final String INSERT = """
            INSERT INTO ai_text_task
                (task_id, idempotency_key, input_text, normalization_version, status, created_at, version)
            VALUES (?, ?, ?, ?, 'PENDING', UTC_TIMESTAMP(6), 0)
            """;
    static final String CLAIM = """
            UPDATE ai_text_task
            SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6), version = version + 1
            WHERE task_id = ? AND status = 'PENDING'
            """;
    static final String SUCCEED = """
            UPDATE ai_text_task
            SET status = 'SUCCESS', output_text = ?, error_code = NULL,
                completed_at = UTC_TIMESTAMP(6), version = version + 1
            WHERE task_id = ? AND status = 'RUNNING'
            """;
    static final String FAIL = """
            UPDATE ai_text_task
            SET status = 'FAILED', output_text = NULL, error_code = ?,
                completed_at = UTC_TIMESTAMP(6), version = version + 1
            WHERE task_id = ? AND status = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final boolean outboxEnabled;
    private final long executionTimeoutMillis;
    private final String acceptancePrefix;
    private final RowMapper<TextTask> mapper = (row, number) -> {
        if (!TaskInputRules.NORMALIZATION_VERSION.equals(row.getString("normalization_version"))) {
            throw new DataIntegrityViolationException("Unsupported task input normalization version");
        }
        return new TextTask(UUID.fromString(row.getString("task_id")), row.getString("idempotency_key"),
                row.getString("input_text"), TaskStatus.valueOf(row.getString("status")),
                row.getString("output_text"), row.getString("error_code"),
                instant(row, "created_at"), instant(row, "started_at"), instant(row, "completed_at"));
    };

    public JdbcTaskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, false);
    }

    public JdbcTaskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, boolean outboxEnabled) {
        this(jdbc, manager, outboxEnabled, 0);
    }

    public JdbcTaskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, boolean outboxEnabled,
                              long executionTimeoutMillis) {
        this(jdbc, manager, outboxEnabled, executionTimeoutMillis, "");
    }

    public JdbcTaskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, boolean outboxEnabled,
                             long executionTimeoutMillis, String acceptancePrefix) {
        this.acceptancePrefix = TaskInputRules.validateAcceptancePrefix(acceptancePrefix);
        this.jdbc = jdbc;
        if (executionTimeoutMillis != 0) { TaskRepository.validateExecutionTimeout(executionTimeoutMillis); }
        this.executionTimeoutMillis = executionTimeoutMillis;
        this.outboxEnabled = outboxEnabled;
        this.write = transaction(manager, false);
        this.read = transaction(manager, true);
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        template.setTimeout(10);
        return template;
    }

    @Override
    public Registration createOrGet(String key, String input) {
        String normalized = TaskInputRules.normalizeInput(input);
        TaskInputRules.validate(key, normalized);
        UUID id = UUID.randomUUID();
        try {
            return Objects.requireNonNull(write.execute(transaction -> {
                try {
                    jdbc.update(INSERT, id.toString(), key, normalized, TaskInputRules.NORMALIZATION_VERSION);
                } catch (DuplicateKeyException duplicate) {
                    throw new TaskInsertCollision(duplicate);
                }
                if (outboxEnabled) {
                    // Same JdbcTemplate/DataSource and the SAME write transaction. No nested REQUIRES_NEW append.
                    if (jdbc.update(JdbcOutboxRepository.INSERT, UUID.randomUUID().toString(), id.toString()) != 1) {
                        throw new DataIntegrityViolationException("Task Outbox append must insert exactly one event");
                    }
                }
                return new Registration(require(id), true);
            }));
        } catch (TaskInsertCollision collision) {
            DuplicateKeyException duplicate = collision.original;
            // The failed insert transaction has ended before a fresh transaction reads the winning row.
            Optional<TextTask> winner = Objects.requireNonNull(read.execute(transaction ->
                    jdbc.query(SELECT + " WHERE idempotency_key = ?", mapper, key).stream().findFirst()));
            if (winner.isEmpty()) {
                // Could be an unrelated primary-key collision; never reinterpret it as key idempotency.
                throw duplicate;
            }
            TextTask task = winner.get();
            if (!task.input().equals(normalized)) {
                throw new TaskException(TaskException.Kind.IDEMPOTENCY_CONFLICT,
                        "This idempotencyKey was already used with a different normalized input");
            }
            return new Registration(task, false);
        }
    }

    private static final class TaskInsertCollision extends RuntimeException {
        private final DuplicateKeyException original;
        private TaskInsertCollision(DuplicateKeyException original) { this.original = original; }
    }

    @Override
    public Optional<TextTask> findById(UUID id) {
        return Objects.requireNonNull(read.execute(transaction -> find(id)));
    }

    @Override
    public TextTask start(UUID id) {
        requireTokenlessMode();
        return Objects.requireNonNull(write.execute(transaction -> {
            ensureUpdated(jdbc.update(CLAIM, id.toString()), id, TaskStatus.PENDING);
            return require(id);
        }));
    }

    @Override
    public TextTask succeed(UUID id, String output) {
        requireTokenlessMode();
        TextTask.validateOutput(output);
        return Objects.requireNonNull(write.execute(transaction -> {
            ensureUpdated(jdbc.update(SUCCEED, output, id.toString()), id, TaskStatus.RUNNING);
            return require(id);
        }));
    }

    @Override
    public TextTask fail(UUID id, TaskStatus expectedStatus, String errorCode) {
        if (expectedStatus == TaskStatus.RUNNING) { requireTokenlessMode(); }
        expectedStatus.requireTransitionTo(TaskStatus.FAILED);
        TextTask.validateErrorCode(errorCode);
        return Objects.requireNonNull(write.execute(transaction -> {
            ensureUpdated(jdbc.update(FAIL, errorCode, id.toString(), expectedStatus.name()), id, expectedStatus);
            return require(id);
        }));
    }

    @Override
    public Execution claimExecution(UUID id) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.claimExecution(id); }
        UUID token = UUID.randomUUID();
        return Objects.requireNonNull(write.execute(tx -> {
            lockTask(id); // Establish the row lock BEFORE the statement that starts the execution deadline.
            ensureUpdated(jdbc.update("""
                    UPDATE ai_text_task SET status='RUNNING', started_at=UTC_TIMESTAMP(6),
                        execution_token=?, execution_deadline=TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                        version=version+1 WHERE task_id=? AND status='PENDING'
                    """, token.toString(), executionTimeoutMillis * 1000, id.toString()), id, TaskStatus.PENDING);
            TextTask task = require(id);
            LocalDateTime deadline = jdbc.queryForObject("SELECT execution_deadline FROM ai_text_task WHERE task_id=?",
                    LocalDateTime.class, id.toString());
            return new Execution(task, token, Objects.requireNonNull(deadline).toInstant(ZoneOffset.UTC));
        }));
    }

    private static final String OWNED_EXECUTION = """
            WHERE task_id=? AND status='RUNNING' AND execution_token=? AND execution_deadline > UTC_TIMESTAMP(6)
            """;

    @Override
    public TextTask succeedExecution(Execution execution, String output) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.succeedExecution(execution, output); }
        TextTask.validateOutput(output);
        return completeExecution(execution, output, null);
    }

    @Override
    public TextTask failExecution(Execution execution, String code) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.failExecution(execution, code); }
        TextTask.validateErrorCode(code);
        return completeExecution(execution, null, code);
    }

    private TextTask completeExecution(Execution execution, String output, String code) {
        UUID id = execution.task().id();
        if (execution.token() == null) { throw new TaskStateConflictException(id, TaskStatus.RUNNING, TaskStatus.RUNNING); }
        return Objects.requireNonNull(write.execute(tx -> {
            // UTC_TIMESTAMP is statement-scoped: acquire the lock first, then evaluate a fresh completion statement.
            lockTask(id);
            ensureUpdated(jdbc.update("""
                    UPDATE ai_text_task SET status=?, output_text=?, error_code=?, completed_at=UTC_TIMESTAMP(6),
                        execution_token=NULL, execution_deadline=NULL, version=version+1
                    """ + OWNED_EXECUTION, code == null ? "SUCCESS" : "FAILED", output, code,
                    id.toString(), execution.token().toString()), id, TaskStatus.RUNNING);
            return require(id);
        }));
    }

    @Override
    public int expireRunning(int batchSize, UUID taskScope) {
        requireProtectedMode();
        TaskRepository.validateTimeoutBatch(batchSize);
        return Objects.requireNonNull(write.execute(tx -> {
            String due = """
                    WHERE status='RUNNING' AND execution_token IS NOT NULL AND execution_deadline <= UTC_TIMESTAMP(6)
                    """ + (taskScope == null ? "" : " AND task_id=?")
                    + (acceptancePrefix.isEmpty() ? "" : " AND idempotency_key LIKE ?");
            var arguments = new java.util.ArrayList<Object>();
            if (taskScope != null) { arguments.add(taskScope.toString()); }
            if (!acceptancePrefix.isEmpty()) { arguments.add(acceptancePrefix + "%"); }
            arguments.add(batchSize);
            // A scanning UPDATE can lock secondary-index records before primary rows and deadlock
            // with a finishing worker (primary row first). Read candidates without locks, then
            // conditionally update each primary key; never lock non-expired model executions.
            var candidates = jdbc.queryForList("SELECT task_id FROM ai_text_task " + due
                    + " ORDER BY execution_deadline, task_id LIMIT ?", String.class, arguments.toArray());
            int count = 0;
            for (String id : candidates) {
                String sql = """
                        UPDATE ai_text_task SET status='FAILED', output_text=NULL, error_code='TASK_TIMEOUT',
                            completed_at=UTC_TIMESTAMP(6), execution_token=NULL, execution_deadline=NULL, version=version+1
                        WHERE task_id=? AND status='RUNNING' AND execution_token IS NOT NULL
                            AND execution_deadline <= UTC_TIMESTAMP(6)
                        """ + (acceptancePrefix.isEmpty() ? "" : " AND idempotency_key LIKE ?");
                int changed = acceptancePrefix.isEmpty() ? jdbc.update(sql, id) : jdbc.update(sql, id, acceptancePrefix + "%");
                if (changed < 0 || changed > 1) { throw new DataIntegrityViolationException("Invalid timeout affected row count"); }
                count += changed;
            }
            if (count < 0 || count > batchSize) { throw new DataIntegrityViolationException("Invalid timeout affected row count"); }
            return count;
        }));
    }

    @Override
    public void verifyExecutionTimeoutSchema() {
        requireProtectedMode();
        jdbc.queryForList("SELECT execution_token, execution_deadline FROM ai_text_task WHERE 1=0");
    }

    private void requireProtectedMode() {
        if (executionTimeoutMillis == 0) { throw new IllegalStateException("Execution timeouts not enabled"); }
    }

    private void requireTokenlessMode() {
        if (executionTimeoutMillis != 0) { throw new IllegalStateException("Execution ownership token required"); }
    }

    private void lockTask(UUID id) {
        if (jdbc.query(SELECT + " WHERE task_id=? FOR UPDATE", mapper, id.toString()).isEmpty()) {
            throw new TaskException(TaskException.Kind.TASK_NOT_FOUND, "Task not found");
        }
    }

    private void ensureUpdated(int count, UUID id, TaskStatus expected) {
        if (count == 0) {
            TextTask current = require(id);
            throw new TaskStateConflictException(id, current.status(), expected);
        }
        if (count != 1) {
            throw new DataIntegrityViolationException("Task transition must update exactly one row");
        }
    }

    private Optional<TextTask> find(UUID id) {
        return jdbc.query(SELECT + " WHERE task_id = ?", mapper, id.toString()).stream().findFirst();
    }

    private TextTask require(UUID id) {
        return find(id).orElseThrow(() -> new TaskException(TaskException.Kind.TASK_NOT_FOUND, "Task not found"));
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        LocalDateTime value = row.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
