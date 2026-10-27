package com.example.aitask.config;

import com.example.aitask.repository.TaskRepository;
import com.example.aitask.service.TaskTimeoutChecker;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
public class TaskTimeoutConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateExecutionTimeouts(Environment env) {
        return factory -> {
            String enabled = env.getProperty("platform.execution.timeout-enabled", "false");
            if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Execution timeout-enabled must be true or false");
            }
            if (enabled.equalsIgnoreCase("true")) {
                TaskRepository.validateExecutionTimeout(env.getProperty("platform.execution.timeout-ms", Long.class, 60000L));
                TaskRepository.validateTimeoutBatch(env.getProperty("platform.execution.timeout-batch-size", Integer.class, 20));
                long poll = env.getProperty("platform.execution.timeout-check-ms", Long.class, 1000L);
                if (poll < 100 || poll > 60000) { throw new IllegalArgumentException("Timeout check interval must be 100-60000ms"); }
            }
        };
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="platform.execution.timeout-enabled",havingValue="true")
    @EnableScheduling // Reuses Spring's existing scheduling infrastructure, including the Outbox scheduler.
    static class Enabled {
        @Bean
        TaskTimeoutChecker taskTimeoutChecker(TaskRepository repository, Environment env) {
            try { repository.verifyExecutionTimeoutSchema(); }
            catch (DataAccessException unavailable) {
                throw new IllegalStateException("Worker timeout schema/storage unavailable; reviewed 003 migration required");
            }
            return new TaskTimeoutChecker(repository, env.getProperty("platform.execution.timeout-batch-size", Integer.class, 20));
        }
    }
}
