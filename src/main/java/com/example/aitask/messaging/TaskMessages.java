package com.example.aitask.messaging;

import org.springframework.amqp.core.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 36-byte canonical UUID, schema header v1. Never Java deserialization or input text on the task wire. */
public final class TaskMessages {
    private TaskMessages() { }
    public static Message task(UUID id) {
        return MessageBuilder.withBody(id.toString().getBytes(StandardCharsets.US_ASCII))
                .setContentType("application/vnd.ai-task-id.v1")
                .setContentEncoding("US-ASCII")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(UUID.randomUUID().toString()).setHeader("x-task-schema", 1).build();
    }
    public static UUID id(Message message) {
        if (message.getBody().length != 36 || !Integer.valueOf(1).equals(
                message.getMessageProperties().getHeaders().get("x-task-schema"))) {
            throw new IllegalArgumentException("Invalid task message schema");
        }
        String value = new String(message.getBody(), StandardCharsets.US_ASCII);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) { throw new IllegalArgumentException("Task ID must be canonical"); }
        return id;
    }
}
