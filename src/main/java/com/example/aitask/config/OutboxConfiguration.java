package com.example.aitask.config;

import com.example.aitask.outbox.*;
import com.example.aitask.messaging.*;
import com.example.aitask.service.TaskDispatcher;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateOutboxMode(Environment env) {
        return factory -> {
            String value = env.getProperty("platform.outbox.enabled", "false");
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("platform.outbox.enabled must be true or false");
            }
            if (value.equalsIgnoreCase("true") && (!env.getProperty("platform.storage.mode", "memory").equals("mysql")
                    || !env.getProperty("platform.messaging.mode", "local").equals("rabbit"))) {
                throw new IllegalArgumentException("Outbox requires explicit mysql storage and rabbit messaging");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
    @EnableScheduling
    static class Enabled {
        @Bean
        OutboxSettings outboxSettings(Environment env) {
            long poll = env.getProperty("platform.outbox.poll-interval-ms", Long.class, 1000L);
            long confirm = env.getProperty("platform.messaging.confirm-timeout-ms", Long.class, 5000L);
            var settings = new OutboxSettings(env.getProperty("platform.outbox.batch-size", Integer.class, 20),
                    env.getProperty("platform.outbox.max-attempts", Integer.class, 8),
                    env.getProperty("platform.outbox.lease-ms", Long.class, 60000L),
                    env.getProperty("platform.outbox.base-backoff-ms", Long.class, 1000L),
                    env.getProperty("platform.outbox.max-backoff-ms", Long.class, 60000L));
            if (poll < 100 || poll > 60000 || settings.leaseMillis() < confirm + 20000) {
                throw new IllegalArgumentException("Outbox poll must be 100-60000ms; lease must exceed confirm timeout plus 20000ms");
            }
            return settings;
        }

        @Bean
        JdbcOutboxRepository outboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxSettings settings, Environment env) {
            var repository = new JdbcOutboxRepository(jdbc, manager, settings, null,
                    env.getProperty("platform.acceptance.key-prefix", ""));
            try { repository.verifySchema(); }
            catch (DataAccessException unavailable) {
                throw new IllegalStateException("Outbox schema/storage unavailable; reviewed manual migration required");
            }
            return repository;
        }

        @Bean @Primary
        TaskDispatcher outboxSubmissionDispatcher() { return new OutboxSubmissionDispatcher(); }

        @Bean
        OutboxDispatcher outboxDispatcher(JdbcOutboxRepository repository, ConfirmedRabbitPublisher publisher,
                                          OutboxSettings settings) {
            return new OutboxDispatcher(repository, (eventId, taskId) -> publisher.publish(
                    MessageBuilder.fromMessage(TaskMessages.task(taskId)).setMessageId(eventId.toString()).build(), "task"), settings);
        }

        @Bean
        OutboxPoller outboxPoller(OutboxDispatcher dispatcher) { return new OutboxPoller(dispatcher); }
    }
}
