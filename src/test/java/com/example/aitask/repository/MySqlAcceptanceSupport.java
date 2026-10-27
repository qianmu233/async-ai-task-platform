package com.example.aitask.repository;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Test-only environment gate and credential-free failure diagnostics. */
final class MySqlAcceptanceSupport {
    private MySqlAcceptanceSupport() { }

    record Configuration(String host, int port, String username, String password) {
        @Override
        public String toString() {
            return "MySQL acceptance configuration [redacted]";
        }

        String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/ai_task_platform"
                    + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&allowMultiQueries=false"
                    + "&connectTimeout=5000&socketTimeout=15000";
        }
    }

    static Configuration configuration(boolean enabled, Map<String, String> environment) {
        if (!enabled) {
            throw new IllegalArgumentException("Real MySQL test refused: use the explicit mysql-it Maven profile");
        }
        String host = required(environment, "MYSQL_IT_HOST");
        String portText = required(environment, "MYSQL_IT_PORT");
        String username = required(environment, "MYSQL_IT_USERNAME");
        String password = required(environment, "MYSQL_IT_PASSWORD");
        if (!host.matches("[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("Real MySQL test refused: invalid MYSQL_IT_HOST");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("Real MySQL test refused: invalid MYSQL_IT_PORT");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Real MySQL test refused: invalid MYSQL_IT_PORT");
        }
        if (!"ai_task_app".equals(username)) {
            throw new IllegalArgumentException("Real MySQL test refused: MYSQL_IT_USERNAME must be ai_task_app");
        }
        return new Configuration(host, port, username, password);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Real MySQL test refused: missing environment variable " + name);
        }
        return value;
    }

    // Never include vendor messages, JDBC URLs, credential values, or the original cause in reports.
    static String diagnostic(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> remaining = new ArrayDeque<>();
        remaining.add(failure);
        String fallback = "NON_SQL_FAILURE";
        while (!remaining.isEmpty()) {
            Throwable current = remaining.remove();
            if (!visited.add(current)) { continue; }
            if (current instanceof SQLException sql) {
                int code = sql.getErrorCode();
                String state = sql.getSQLState();
                if (code == 1213) { return "DEADLOCK vendorCode=1213"; }
                if (code == 1205) { return "LOCK_WAIT_TIMEOUT vendorCode=1205"; }
                if (state != null && state.startsWith("08")) { return "CONNECTION_FAILURE"; }
                fallback = "SQL_FAILURE vendorCode=" + code;
                if (sql.getNextException() != null) { remaining.add(sql.getNextException()); }
            }
            if (current instanceof java.util.concurrent.TimeoutException) {
                fallback = "WORKER_TIMEOUT";
            }
            if (current.getCause() != null) { remaining.add(current.getCause()); }
        }
        return fallback;
    }

    static AssertionError failure(String operation, Throwable failure) {
        return new AssertionError("Real MySQL acceptance failed: " + operation + " " + diagnostic(failure)
                + "; no retry or in-memory fallback; original diagnostic redacted");
    }
}
