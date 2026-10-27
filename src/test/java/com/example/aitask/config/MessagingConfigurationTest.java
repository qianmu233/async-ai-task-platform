package com.example.aitask.config;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.service.TaskDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MessagingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTaskPlatformApplication.class).withPropertyValues("debug=false",
                    "platform.execution.workers=1", "platform.execution.queue-capacity=1");

    @Test
    void localModeHasDispatcherWithoutAnyBrokerConnectionFactory() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(TaskDispatcher.class)
                .doesNotHaveBean(ConnectionFactory.class));
    }

    @Test
    void unknownModeAndRabbitWithoutMysqlAreExplicitlyRefused() {
        runner.withPropertyValues("platform.messaging.mode=typo").run(context ->
                assertThat(context).hasFailed().getFailure().hasMessageContaining("platform.messaging.mode must be local or rabbit"));
        runner.withPropertyValues("platform.messaging.mode=rabbit").run(context ->
                assertThat(context).hasFailed().getFailure().hasMessageContaining("Rabbit messaging requires mysql storage"));
    }

    @Test
    void rabbitComponentsWireLazilyWithListenerDisabledWithoutRealConnections() {
        runner.withPropertyValues("platform.messaging.mode=rabbit", "platform.storage.mode=mysql",
                "platform.storage.mysql.host=127.0.0.1", "platform.storage.mysql.port=3306",
                "platform.storage.mysql.username=offline", "platform.storage.mysql.password=offline",
                "platform.messaging.rabbitmq.username=offline", "platform.messaging.rabbitmq.password=offline",
                "platform.messaging.listener-auto-startup=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TaskDispatcher.class)
                        .hasSingleBean(ConnectionFactory.class));
    }
}
