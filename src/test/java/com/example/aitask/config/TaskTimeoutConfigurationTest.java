package com.example.aitask.config;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.repository.*;
import com.example.aitask.service.TaskTimeoutChecker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TaskTimeoutConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTaskPlatformApplication.class).withPropertyValues("debug=false",
                    "platform.execution.workers=1", "platform.execution.queue-capacity=1");

    @Test
    void defaultIsOffAndMemoryCanEnableTheSameCheckerWithoutExternalServices() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(TaskTimeoutChecker.class));
        runner.withPropertyValues("platform.execution.timeout-enabled=true", "platform.execution.timeout-check-ms=60000")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(TaskTimeoutChecker.class)
                        .hasSingleBean(InMemoryTaskRepository.class).doesNotHaveBean(javax.sql.DataSource.class));
    }

    @Test
    void invalidEnabledFlagOrDeadlineIsExplicitlyRefused() {
        runner.withPropertyValues("platform.execution.timeout-enabled=typo").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("must be true or false"));
        runner.withPropertyValues("platform.execution.timeout-enabled=true", "platform.execution.timeout-ms=0")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("100-86400000ms"));
    }

    @Test
    void missingMysqlLeaseColumnsRefuseStartupWithoutFallback() {
        // Offline schema failure injection. No database or H2; unusable source guards against accidental connection.
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:offline-only")) {
            @Override public List<Map<String,Object>> queryForList(String sql) {
                throw new InvalidDataAccessResourceUsageException("Synthetic missing lease columns");
            }
        };
        new ApplicationContextRunner().withUserConfiguration(TaskTimeoutConfiguration.class)
                .withBean(TaskRepository.class, () -> new JdbcTaskRepository(jdbc,
                        new DataSourceTransactionManager(jdbc.getDataSource()), false, 60000))
                .withPropertyValues("platform.execution.timeout-enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("reviewed 003 migration required"));
    }
}
