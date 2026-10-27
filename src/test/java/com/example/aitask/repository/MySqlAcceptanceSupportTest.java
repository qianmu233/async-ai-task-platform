package com.example.aitask.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MySqlAcceptanceSupportTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("MYSQL_IT_HOST", "127.0.0.1", "MYSQL_IT_PORT", "3306",
                "MYSQL_IT_USERNAME", "ai_task_app", "MYSQL_IT_PASSWORD", "synthetic-test-secret"));
    }

    @Test
    void explicitOptInRequiredEvenWithCompleteEnvironment() {
        assertThrows(IllegalArgumentException.class,
                () -> MySqlAcceptanceSupport.configuration(false, environment()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL_IT_HOST", "MYSQL_IT_PORT", "MYSQL_IT_USERNAME", "MYSQL_IT_PASSWORD"})
    void missingEnvironmentRefusedWithoutLeakingOtherValues(String name) {
        var environment = environment();
        environment.remove(name);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> MySqlAcceptanceSupport.configuration(true, environment));
        assertTrue(failure.getMessage().contains(name));
        assertFalse(failure.getMessage().contains("synthetic-test-secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "not-a-port"})
    void invalidPortRefused(String port) {
        var environment = environment();
        environment.put("MYSQL_IT_PORT", port);
        assertThrows(IllegalArgumentException.class,
                () -> MySqlAcceptanceSupport.configuration(true, environment));
    }

    @Test
    void rejectsUrlInjectionAndAdministratorAccount() {
        var unsafeHost = environment();
        unsafeHost.put("MYSQL_IT_HOST", "localhost/other_database?password=synthetic-test-secret");
        assertThrows(IllegalArgumentException.class,
                () -> MySqlAcceptanceSupport.configuration(true, unsafeHost));
        var unsafeAccount = environment();
        unsafeAccount.put("MYSQL_IT_USERNAME", "root");
        assertThrows(IllegalArgumentException.class,
                () -> MySqlAcceptanceSupport.configuration(true, unsafeAccount));
    }

    @Test
    void configurationUsesFixedDatabaseAndRedactedRepresentation() {
        var config = MySqlAcceptanceSupport.configuration(true, environment());
        assertTrue(config.jdbcUrl().contains("/ai_task_platform?"));
        assertFalse(config.jdbcUrl().contains(config.password()));
        assertFalse(config.toString().contains(config.password()));
    }

    @Test
    void classifiesDatabaseFailuresWithoutExposingExceptionMessagesOrCauses() {
        int[] codes = {1213, 1205, 0, 1062};
        String[] states = {"40001", "HY000", "08001", "23000"};
        String[] categories = {"DEADLOCK", "LOCK_WAIT_TIMEOUT", "CONNECTION_FAILURE", "SQL_FAILURE"};
        for (int index = 0; index < codes.length; index++) {
            var sql = new SQLException("synthetic-test-secret", states[index], codes[index]);
            var wrapped = new RuntimeException("synthetic-test-secret", sql);
            var failure = MySqlAcceptanceSupport.failure("fixture", wrapped);
            assertTrue(failure.getMessage().contains(categories[index]));
            assertFalse(failure.getMessage().contains("synthetic-test-secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void followsSqlExceptionChainAndClassifiesWorkerTimeout() {
        var outer = new SQLException("synthetic", "HY000", 0);
        outer.setNextException(new SQLException("synthetic", "40001", 1213));
        assertTrue(MySqlAcceptanceSupport.diagnostic(outer).startsWith("DEADLOCK"));
        assertEquals("WORKER_TIMEOUT",
                MySqlAcceptanceSupport.diagnostic(new java.util.concurrent.TimeoutException()));
    }
}
