package com.example.aitask.messaging;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.repository.TaskRepository;
import com.example.aitask.service.TaskDispatcher;
import com.example.aitask.service.TaskDispatchException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Confirm + mandatory return checks; deliberately no claim of a MySQL/MQ transaction. */
public class ConfirmedRabbitPublisher implements TaskDispatcher {
    private final RabbitTemplate template;
    private final TaskQueueNames names;
    private final long timeoutMillis;

    public ConfirmedRabbitPublisher(RabbitTemplate template, TaskQueueNames names, long timeoutMillis) {
        if (timeoutMillis < 100 || timeoutMillis > 30000) {
            throw new IllegalArgumentException("Rabbit confirm timeout must be 100-30000 ms");
        }
        this.template = template;
        this.names = names;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public boolean shouldDispatch(TaskRepository.Registration registration) {
        // A retry under the same key repairs a failed/ambiguous publish without a new row.
        return registration.task().status() == TaskStatus.PENDING;
    }

    @Override
    public void dispatch(UUID id) {
        try { publish(TaskMessages.task(id), "task"); }
        catch (PublicationFailure failure) { throw new TaskDispatchException(id); }
    }

    public void publish(Message message, String route) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(names.exchange(), route, message, correlation);
            var confirm = correlation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) { throw new PublicationFailure(); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new PublicationFailure();
        } catch (Exception failure) {
            // No original exception body, credentials, input, or broker diagnostic in API/log/report.
            throw new PublicationFailure();
        }
    }

    public static class PublicationFailure extends RuntimeException {
        public PublicationFailure() { super("Rabbit publication not confirmed or not routed"); }
    }
}
