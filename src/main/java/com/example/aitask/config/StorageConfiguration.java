package com.example.aitask.config;

import com.example.aitask.repository.JdbcTaskRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateStorageMode(Environment environment) {
        return factory -> {
            String mode = environment.getProperty("platform.storage.mode", "memory");
            if (!"memory".equals(mode) && !"mysql".equals(mode)) {
                throw new IllegalArgumentException("platform.storage.mode must be memory or mysql");
            }
            String prefix = com.example.aitask.service.TaskInputRules.validateAcceptancePrefix(
                    environment.getProperty("platform.acceptance.key-prefix", ""));
            if (!prefix.isEmpty() && !mode.equals("mysql")) {
                throw new IllegalArgumentException("Acceptance scan isolation requires explicit mysql storage");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "platform.storage.mode", havingValue = "mysql")
    static class MysqlConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(
                @Value("${platform.storage.mysql.host}") String host,
                @Value("${platform.storage.mysql.port}") int port,
                @Value("${platform.storage.mysql.username}") String username,
                @Value("${platform.storage.mysql.password}") String password) {
            if (host == null || !host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535) {
                throw new IllegalArgumentException("MySQL host must be a hostname/IPv4 address and port must be valid");
            }
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("MySQL mode requires external database username and password");
            }
            // Lazy pool: constructing configuration does not connect. Database is fixed, not supplied by URL.
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/ai_task_platform"
                    + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&allowMultiQueries=false"
                    + "&connectTimeout=5000&socketTimeout=10000");
            source.setUsername(username);
            source.setPassword(password);
            source.setPoolName("ai-task-business-db");
            source.setMaximumPoolSize(4);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(5000);
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource source) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            // Explicit vendor translation avoids obtaining database metadata just to classify SQL errors.
            jdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("MySQL"));
            return jdbc;
        }

        @Bean
        DataSourceTransactionManager transactionManager(HikariDataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        JdbcTaskRepository jdbcTaskRepository(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager,
                @Value("${platform.outbox.enabled:false}") boolean outboxEnabled,
                @Value("${platform.execution.timeout-enabled:false}") boolean timeoutEnabled,
                @Value("${platform.execution.timeout-ms:60000}") long timeoutMillis,
                @Value("${platform.acceptance.key-prefix:}") String acceptancePrefix) {
            return new JdbcTaskRepository(jdbc, transactionManager, outboxEnabled, timeoutEnabled ? timeoutMillis : 0, acceptancePrefix);
        }
    }
}
