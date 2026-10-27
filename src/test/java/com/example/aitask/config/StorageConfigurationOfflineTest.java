package com.example.aitask.config;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.repository.JdbcTaskRepository;
import com.example.aitask.repository.TaskRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** All pools remain lazy and no test calls getConnection(). No database credentials are real. */
class StorageConfigurationOfflineTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTaskPlatformApplication.class)
            .withPropertyValues("debug=false", "platform.execution.workers=1", "platform.execution.queue-capacity=1");

    private ApplicationContextRunner mysql() {
        return runner.withPropertyValues("platform.storage.mode=mysql",
                "platform.storage.mysql.host=127.0.0.1", "platform.storage.mysql.port=3306",
                "platform.storage.mysql.username=offline-test-placeholder",
                "platform.storage.mysql.password=offline-test-placeholder");
    }

    @Test
    void defaultMemoryHasNoDataSourceEvenWhenSqlInitIsRequested() {
        runner.withPropertyValues("spring.sql.init.mode=always").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TaskRepository.class)
                    .hasSingleBean(InMemoryTaskRepository.class).doesNotHaveBean(DataSource.class)
                    .doesNotHaveBean(DataSourceScriptDatabaseInitializer.class);
        });
    }

    @Test
    void mysqlWiresOneRepositoryWithoutConnectingOrCreatingASchema() {
        mysql().withPropertyValues("spring.sql.init.mode=always").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TaskRepository.class)
                    .hasSingleBean(JdbcTaskRepository.class).doesNotHaveBean(InMemoryTaskRepository.class)
                    .doesNotHaveBean(DataSourceScriptDatabaseInitializer.class);
            HikariDataSource source = context.getBean(HikariDataSource.class);
            assertThat(source.isRunning()).isFalse();
            assertThat(source.getJdbcUrl()).contains("/ai_task_platform?").doesNotContain("xxl_job")
                    .doesNotContain("createDatabaseIfNotExist").contains("forceConnectionTimeZoneToSession=true");
        });
    }

    @Test
    void mysqlRequiresExternalCredentialsAndDoesNotFallBackToMemory() {
        mysql().withPropertyValues("platform.storage.mysql.password=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "MySQL mode requires external database username and password");
        });
    }

    @Test
    void unknownStorageModeFailsExplicitly() {
        runner.withPropertyValues("platform.storage.mode=typo").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("platform.storage.mode must be memory or mysql");
        });
    }

    @Test
    void hostCannotInjectADifferentDatabaseOrConnectionOptions() {
        mysql().withPropertyValues("platform.storage.mysql.host=127.0.0.1/xxl_job?option=value").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "MySQL host must be a hostname/IPv4 address and port must be valid");
        });
    }

    @Test
    void invalidPortFailsBeforePoolConstruction() {
        mysql().withPropertyValues("platform.storage.mysql.port=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "MySQL host must be a hostname/IPv4 address and port must be valid");
        });
    }
}
