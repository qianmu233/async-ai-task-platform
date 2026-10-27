package com.example.aitask.messaging;

import com.example.aitask.service.TaskException;
import com.example.aitask.service.TaskWorker;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;
import java.util.Arrays;

public class RabbitTaskConsumer {
    public static final String LISTENER_ID = "p1TaskWorker";
    private static final Logger log = LoggerFactory.getLogger(RabbitTaskConsumer.class);
    @FunctionalInterface
    public interface Backoff { void waitBeforeRetry(long millis) throws InterruptedException; }
    private final TaskWorker worker;
    private final ConfirmedRabbitPublisher publisher;
    private final ConsumerPause pause;
    private final int attempts;
    private final long backoffMillis;
    private final Backoff backoff;

    public RabbitTaskConsumer(TaskWorker worker, ConfirmedRabbitPublisher publisher, ConsumerPause pause,
                              int attempts, long backoffMillis, Backoff backoff) {
        if (attempts < 1 || attempts > 5 || backoffMillis < 100 || backoffMillis > 5000) {
            throw new IllegalArgumentException("Consumer attempts must be 1-5 and backoff 100-5000 ms");
        }
        this.worker = worker;
        this.publisher = publisher;
        this.pause = pause;
        this.attempts = attempts;
        this.backoffMillis = backoffMillis;
        this.backoff = backoff;
    }

    @RabbitListener(id = LISTENER_ID, queues = "${platform.messaging.queue-prefix:p1.ai-task}.tasks",
            containerFactory = "taskRabbitListenerContainerFactory")
    public void receive(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String reason = "INVALID_MESSAGE";
        try {
            var id = TaskMessages.id(message);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    var outcome = worker.execute(id);
                    if (outcome == TaskWorker.Outcome.IN_PROGRESS) {
                        // No ownership/lease yet: quarantine rather than silently discard a crashed RUNNING task.
                        reason = "RUNNING_UNRESOLVED";
                        break;
                    }
                    acknowledge(channel, tag);
                    return;
                } catch (TaskWorker.StorageFailure failure) {
                    if (failure.stage() == TaskWorker.Stage.COMPLETION) {
                        reason = "COMPLETION_UNKNOWN";
                        break; // The model may have run; never retry it here.
                    }
                    reason = "STORAGE_RETRY_EXHAUSTED";
                    if (attempt < attempts) { backoff.waitBeforeRetry(backoffMillis); }
                }
            }
        } catch (IllegalArgumentException invalid) {
            reason = "INVALID_MESSAGE";
        } catch (TaskException missing) {
            reason = missing.kind() == TaskException.Kind.TASK_NOT_FOUND ? "TASK_NOT_FOUND" : "TASK_ERROR";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Leave the delivery unacknowledged; stop rather than publish from an interrupted thread.
            pause.pause();
            return;
        } catch (RuntimeException unexpected) {
            reason = "UNEXPECTED_FAILURE";
        }
        isolate(message, channel, tag, reason);
    }

    private void isolate(Message original, Channel channel, long tag, String reason) {
        // Bounded body and trusted headers only: do not forward arbitrary header graphs or stack traces.
        Message parked = MessageBuilder.withBody(Arrays.copyOf(original.getBody(), Math.min(4096, original.getBody().length)))
                .setContentType("application/octet-stream").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-task-schema", 1).setHeader("x-isolation-reason", reason)
                .setHeader("x-original-body-size", original.getBody().length).build();
        try {
            publisher.publish(parked, "park");
        } catch (ConfirmedRabbitPublisher.PublicationFailure failure) {
            log.error("P1 consumer paused: isolation publish failed; delivery remains unacknowledged");
            pause.pause(); // No basicNack(requeue=true) loop. Closing the container requeues the unacked delivery.
            return;
        }
        log.warn("P1 message isolated: {}", reason);
        acknowledge(channel, tag);
    }

    private void acknowledge(Channel channel, long tag) {
        try { channel.basicAck(tag, false); }
        catch (IOException | RuntimeException failure) {
            log.error("P1 consumer paused: ACK channel failure; database results are not overwritten");
            pause.pause();
        }
    }
}
