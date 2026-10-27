package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.service.TaskTimeoutChecker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL only AFTER human 003 migration approval. Unique rows, no DDL/backfill/cleanup/MQ/model retry. */
class TaskExecutionLeaseIT {
    @Test
    void databaseDeadlineOwnershipAndTwoTimeoutHandlersProtectFinalState() throws Exception {
        assertTrue(Boolean.getBoolean("phase4b.lease.enabled"), "Use explicit -Pworker-lease-it");
        assertEquals("true", System.getenv("WORKER_LEASE_IT_SCHEMA_APPROVED"),
                "REFUSED: reviewed manual 003 migration required; no connection or write executed");
        var config = MySqlAcceptanceSupport.configuration(true, System.getenv());
        String run = UUID.randomUUID().toString();
        List<Connection> connections = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<JdbcTemplate> jdbc = new ArrayList<>();
            List<DataSourceTransactionManager> managers = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Properties credentials = new Properties();
                credentials.setProperty("user", config.username()); credentials.setProperty("password", config.password());
                Connection connection;
                try { connection = DriverManager.getConnection(config.jdbcUrl(), credentials); }
                finally { credentials.clear(); }
                connections.add(connection);
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                var source = new SingleConnectionDataSource(connection, true);
                var db = new JdbcTemplate(source); db.setQueryTimeout(10);
                db.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("MySQL"));
                assertEquals("ai_task_platform", connection.getCatalog());
                assertTrue(db.queryForObject("SELECT VERSION()", String.class).matches("8\\.0\\.\\d+.*"));
                assertTrue(db.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_task_app@"));
                assertEquals(1, db.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                        WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_text_task'
                        AND CONSTRAINT_NAME='ck_task_execution_ownership' AND ENFORCED='YES'
                        """, Integer.class));
                preflightSchemaAndPrivileges(db, run);
                jdbc.add(db); managers.add(new DataSourceTransactionManager(source));
            }
            var a = jdbc.get(0); var b = jdbc.get(1);
            long sessionA = a.queryForObject("SELECT CONNECTION_ID()", Long.class);
            long sessionB = b.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotEquals(sessionA, sessionB);
            assertEquals(a.queryForObject("SELECT @@server_uuid", String.class), b.queryForObject("SELECT @@server_uuid", String.class));
            var normal = new JdbcTaskRepository(a, managers.get(0), true, 15000);
            normal.verifyExecutionTimeoutSchema();
            UUID success = normal.createOrGet("lease-it:" + run + ":normal", "hello lease").task().id();
            var owner = normal.claimExecution(success);
            assertNotNull(owner.token()); assertNotNull(owner.deadline());
            var stranger = new TaskRepository.Execution(owner.task(), UUID.randomUUID(), owner.deadline());
            assertThrows(TaskStateConflictException.class, () -> normal.succeedExecution(stranger, "forged output"));
            assertThrows(TaskStateConflictException.class, () -> normal.failExecution(stranger, "MODEL_CALL_FAILED"));
            assertEquals(TaskStatus.SUCCESS, normal.succeedExecution(owner, "[MOCK] hello lease").status());
            assertFinal(a, success, "SUCCESS", null);
            assertThrows(TaskStateConflictException.class, () -> normal.failExecution(owner, "OLD_OWNER"));

            var expiredA = new JdbcTaskRepository(a, managers.get(0), true, 500);
            var expiredB = new JdbcTaskRepository(b, managers.get(1), true, 500);
            UUID timeout = expiredA.createOrGet("lease-it:" + run + ":timeout", "not retried").task().id();
            var old = expiredA.claimExecution(timeout);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Boolean.TRUE.equals(a.queryForObject(
                    "SELECT execution_deadline <= UTC_TIMESTAMP(6) FROM ai_text_task WHERE task_id=?", Boolean.class, timeout.toString()))) {
                assertTrue(System.nanoTime() < deadline, "Real DB deadline did not expire");
                Thread.sleep(50);
            }
            assertThrows(TaskStateConflictException.class, () -> expiredA.succeedExecution(old, "late output"));
            assertThrows(TaskStateConflictException.class, () -> expiredA.failExecution(old, "MODEL_CALL_FAILED"));
            assertEquals(TaskStatus.RUNNING, expiredA.findById(timeout).orElseThrow().status());
            CountDownLatch start = new CountDownLatch(1);
            var checkerA = new TaskTimeoutChecker(expiredA, 1);
            var checkerB = new TaskTimeoutChecker(expiredB, 1);
            Future<Integer> first = pool.submit(() -> { start.await(); return checkerA.check(timeout); });
            Future<Integer> second = pool.submit(() -> { start.await(); return checkerB.check(timeout); });
            start.countDown();
            int changedA = first.get(15, TimeUnit.SECONDS), changedB = second.get(15, TimeUnit.SECONDS);
            assertEquals(1, changedA + changedB);
            assertFinal(a, timeout, "FAILED", "TASK_TIMEOUT");
            assertFinal(b, timeout, "FAILED", "TASK_TIMEOUT");
            assertEquals(0, checkerA.check(timeout));
            assertThrows(TaskStateConflictException.class, () -> expiredA.succeedExecution(old, "late output"));
            assertThrows(TaskStateConflictException.class, () -> expiredA.failExecution(old, "MODEL_CALL_FAILED"));
            assertFinal(a, timeout, "FAILED", "TASK_TIMEOUT");

            // New test-only ownerless legacy record demonstrates migration compatibility, not old data mutation.
            var legacy = new JdbcTaskRepository(a, managers.get(0));
            UUID legacyId = legacy.createOrGet("lease-it:" + run + ":legacy", "manual review only").task().id();
            legacy.start(legacyId);
            assertEquals(0, checkerA.check(legacyId));
            assertEquals(TaskStatus.RUNNING, legacy.findById(legacyId).orElseThrow().status());
            assertNull(a.queryForObject("SELECT execution_token FROM ai_text_task WHERE task_id=?", String.class, legacyId.toString()));
            assertEquals(2, a.queryForObject("SELECT COUNT(*) FROM ai_task_outbox WHERE task_id IN (?,?)", Integer.class,
                    success.toString(), timeout.toString()));
            System.out.printf("WORKER_LEASE_IT RESULT run=%s connectionA=%d connectionB=%d normalSuccess=1 "
                            + "timeoutChanged=%d timeoutLosers=1 oldOwnerRejected=true wrongOwnerRejected=true "
                            + "legacyIgnored=true terminalVersions=2 retainedTasks=3 retainedOutbox=2 modelRetries=0%n",
                    run, sessionA, sessionB, changedA + changedB);
        } catch (SQLException | RuntimeException failure) {
            throw MySqlAcceptanceSupport.failure("worker-execution-lease", failure);
        } finally {
            pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS);
            for (Connection connection : connections) { connection.close(); }
        }
    }

    private void preflightSchemaAndPrivileges(JdbcTemplate db, String run) {
        // Read-only gate on BOTH sessions, before this test creates any task or event.
        assertEquals("InnoDB", db.queryForObject("""
                SELECT ENGINE FROM information_schema.TABLES
                WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_text_task'
                """, String.class));
        assertEquals(2, db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_text_task' AND IS_NULLABLE='YES'
                AND ((COLUMN_NAME='execution_token' AND DATA_TYPE='char' AND CHARACTER_MAXIMUM_LENGTH=36
                    AND COLLATION_NAME='ascii_bin')
                    OR (COLUMN_NAME='execution_deadline' AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6))
                """, Integer.class));
        assertEquals("status,execution_deadline,task_id", db.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_text_task'
                AND INDEX_NAME='idx_task_execution_deadline'
                """, String.class));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_text_task'
                AND CONSTRAINT_NAME='ck_task_lifecycle' AND ENFORCED='YES'
                """, Integer.class));
        List<String> grants = db.queryForList("SHOW GRANTS", String.class);
        boolean businessGrant = false;
        for (String grant : grants) {
            if (grant.startsWith("GRANT USAGE ON *.* TO ") && !grant.contains("WITH GRANT OPTION")) { continue; }
            boolean allowed = grant.startsWith("GRANT SELECT, INSERT, UPDATE ON `ai_task_platform`.* TO ")
                    && !grant.contains("WITH GRANT OPTION");
            assertTrue(allowed, "Acceptance requires only SELECT/INSERT/UPDATE on the P1 database; grant text redacted");
            businessGrant = true;
        }
        assertTrue(businessGrant, "P1 business permissions required");
        System.out.printf("WORKER_LEASE_IT PREFLIGHT run=%s connection=%d mysql=%s schema003=true "
                        + "engine=InnoDB permissions=SELECT,INSERT,UPDATE%n", run,
                db.queryForObject("SELECT CONNECTION_ID()", Long.class), db.queryForObject("SELECT VERSION()", String.class));
    }

    private void assertFinal(JdbcTemplate jdbc, UUID id, String status, String error) {
        var row = jdbc.queryForMap("""
                SELECT status,version,error_code,output_text,completed_at,execution_token,execution_deadline
                FROM ai_text_task WHERE task_id=?
                """, id.toString());
        assertEquals(status, row.get("status")); assertEquals(2, ((Number)row.get("version")).intValue());
        assertEquals(error, row.get("error_code")); assertNotNull(row.get("completed_at"));
        assertNull(row.get("execution_token")); assertNull(row.get("execution_deadline"));
        if ("FAILED".equals(status)) { assertNull(row.get("output_text")); }
        else { assertEquals("[MOCK] hello lease", row.get("output_text")); }
    }
}
