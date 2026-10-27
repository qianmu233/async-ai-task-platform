package com.example.aitask.config;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.outbox.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.example.aitask.messaging.*;
import com.example.aitask.service.TaskDispatcher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OutboxConfigurationTest {
    @Test
    void acceptanceNamespaceCannotSilentlyUseMemoryOrLikeWildcards() {
        runner.withPropertyValues("platform.acceptance.key-prefix=phase5:unique:").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("requires explicit mysql"));
        runner.withPropertyValues("platform.acceptance.key-prefix=phase5:%:").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("without LIKE wildcards"));
    }
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTaskPlatformApplication.class).withPropertyValues("debug=false",
                    "platform.execution.workers=1", "platform.execution.queue-capacity=1");
    @Test
    void defaultModeHasNoOutboxOrSchedulerAndInvalidModesAreExplicitlyRefused() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(OutboxRepository.class)
                .doesNotHaveBean(OutboxPoller.class));
        runner.withPropertyValues("platform.outbox.enabled=typo").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("must be true or false"));
        runner.withPropertyValues("platform.outbox.enabled=true").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("requires explicit mysql"));
    }
    @Test
    void retriesAreBoundedWithCappedBackoffAndInvalidBudgetsAreRefused() {
        var settings = new OutboxSettings(20, 8, 60000, 1000, 60000);
        assertThat(settings.backoffMillis(1)).isEqualTo(1000);
        assertThat(settings.backoffMillis(8)).isEqualTo(60000);
        assertThatThrownBy(() -> new OutboxSettings(20, 0, 60000, 1000, 60000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxSettings(20, 8, 500, 1000, 60000)).isInstanceOf(IllegalArgumentException.class);
    }

    // Lite configuration registered explicitly below; NEVER a component-scanned application configuration.
    static class OfflineDependencies {
        @Bean JdbcTemplate jdbc(Environment env) {
            // Offline schema contract only; any attempted real connection fails rather than falling back.
            return new JdbcTemplate(new DriverManagerDataSource("jdbc:offline-only")) {
                @Override public List<Map<String, Object>> queryForList(String sql) {
                    if (env.getProperty("offline.schema.fail", Boolean.class, false)) {
                        throw new DataAccessResourceFailureException("Synthetic missing schema");
                    }
                    return List.of();
                }
            };
        }
        @Bean PlatformTransactionManager manager(JdbcTemplate jdbc) { return new DataSourceTransactionManager(jdbc.getDataSource()); }
        @Bean ConfirmedRabbitPublisher publisher() {
            return new ConfirmedRabbitPublisher(new RabbitTemplate(), new TaskQueueNames("p1.offline"), 1000);
        }
    }

    @Test
    void enabledWiringSelectsOutboxSubmissionAndMissingSchemaRefusesStartup() {
        var enabled = new ApplicationContextRunner().withUserConfiguration(OutboxConfiguration.class, OfflineDependencies.class)
                .withPropertyValues("platform.outbox.enabled=true", "platform.storage.mode=mysql",
                        "platform.messaging.mode=rabbit", "platform.outbox.poll-interval-ms=60000");
        enabled.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(OutboxRepository.class).hasSingleBean(OutboxPoller.class);
            assertThat(ctx.getBean(TaskDispatcher.class)).isInstanceOf(OutboxSubmissionDispatcher.class);
        });
        enabled.withPropertyValues("offline.schema.fail=true").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("reviewed manual migration required"));
    }
}
