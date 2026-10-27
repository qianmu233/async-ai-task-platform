package com.example.aitask.config;

import com.example.aitask.messaging.*;
import com.example.aitask.service.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateMessagingMode(Environment environment) {
        return factory -> {
            String mode = environment.getProperty("platform.messaging.mode", "local");
            if (!mode.equals("local") && !mode.equals("rabbit")) {
                throw new IllegalArgumentException("platform.messaging.mode must be local or rabbit");
            }
            if (mode.equals("rabbit") && !environment.getProperty("platform.storage.mode", "memory").equals("mysql")) {
                throw new IllegalArgumentException("Rabbit messaging requires mysql storage");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "platform.messaging.mode", havingValue = "local", matchIfMissing = true)
    TaskDispatcher localDispatcher(Executor taskExecutor, TaskWorker worker) {
        return new LocalTaskDispatcher(taskExecutor, worker);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "platform.messaging.mode", havingValue = "rabbit")
    @EnableRabbit
    static class RabbitConfiguration {
        @Bean
        TaskQueueNames taskQueueNames(@Value("${platform.messaging.queue-prefix:p1.ai-task}") String prefix) {
            return new TaskQueueNames(prefix);
        }

        @Bean(destroyMethod = "destroy")
        CachingConnectionFactory taskRabbitConnectionFactory(
                @Value("${platform.messaging.rabbitmq.host:127.0.0.1}") String host,
                @Value("${platform.messaging.rabbitmq.port:5673}") int port,
                @Value("${platform.messaging.rabbitmq.username:}") String username,
                @Value("${platform.messaging.rabbitmq.password:}") String password,
                @Value("${platform.messaging.rabbitmq.virtual-host:/ai-task-platform}") String virtualHost) {
            if (!host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid RabbitMQ host or port");
            }
            if (username.isBlank() || password.isBlank() || !virtualHost.equals("/ai-task-platform")) {
                throw new IllegalArgumentException("RabbitMQ requires external credentials and dedicated /ai-task-platform vhost");
            }
            var factory = new CachingConnectionFactory(host, port);
            factory.setUsername(username);
            factory.setPassword(password);
            factory.setVirtualHost(virtualHost);
            factory.setConnectionTimeout(5000);
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            factory.setChannelCacheSize(16);
            return factory;
        }

        @Bean
        Declarables taskRabbitTopology(TaskQueueNames names) { return names.declarations(); }

        @Bean
        RabbitAdmin taskRabbitAdmin(CachingConnectionFactory taskRabbitConnectionFactory) {
            return new RabbitAdmin(taskRabbitConnectionFactory);
        }

        @Bean
        RabbitTemplate taskRabbitTemplate(CachingConnectionFactory taskRabbitConnectionFactory) {
            var template = new RabbitTemplate(taskRabbitConnectionFactory);
            template.setMandatory(true);
            return template;
        }

        @Bean
        ConfirmedRabbitPublisher rabbitTaskDispatcher(RabbitTemplate taskRabbitTemplate, TaskQueueNames names,
                @Value("${platform.messaging.confirm-timeout-ms:5000}") long timeout) {
            return new ConfirmedRabbitPublisher(taskRabbitTemplate, names, timeout);
        }

        @Bean
        ConsumerPause taskConsumerPause(RabbitListenerEndpointRegistry registry) {
            AtomicBoolean requested = new AtomicBoolean();
            return () -> {
                if (requested.compareAndSet(false, true)) {
                    new Thread(() -> {
                        var container = registry.getListenerContainer(RabbitTaskConsumer.LISTENER_ID);
                        if (container != null) { container.stop(); }
                    }, "p1-consumer-pause").start();
                }
            };
        }

        @Bean
        RabbitTaskConsumer taskRabbitConsumer(TaskWorker worker, ConfirmedRabbitPublisher publisher, ConsumerPause pause,
                @Value("${platform.messaging.consumer-attempts:3}") int attempts,
                @Value("${platform.messaging.consumer-backoff-ms:1000}") long backoff) {
            return new RabbitTaskConsumer(worker, publisher, pause, attempts, backoff, Thread::sleep);
        }

        @Bean
        SimpleRabbitListenerContainerFactory taskRabbitListenerContainerFactory(CachingConnectionFactory taskRabbitConnectionFactory,
                ConsumerPause pause, @Value("${platform.messaging.listener-auto-startup:true}") boolean autoStartup) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(taskRabbitConnectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            factory.setPrefetchCount(1);
            factory.setConcurrentConsumers(2);
            factory.setMaxConcurrentConsumers(2);
            factory.setDefaultRequeueRejected(false);
            factory.setAutoStartup(autoStartup);
            factory.setErrorHandler(failure -> pause.pause());
            return factory;
        }
    }
}
