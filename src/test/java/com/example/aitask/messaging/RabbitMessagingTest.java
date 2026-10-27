package com.example.aitask.messaging;

import com.example.aitask.api.ApiExceptionHandler;
import com.example.aitask.api.TaskController;
import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.service.*;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Offline transport-contract tests. No real RabbitMQ or MySQL claim is made. */
class RabbitMessagingTest {
    private final TaskQueueNames names = new TaskQueueNames("p1.offline");
    private final FakeTemplate template = new FakeTemplate();
    private final ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(template, names, 100);
    private final List<Long> acknowledgements = new ArrayList<>();
    private final AtomicInteger pauses = new AtomicInteger();
    private final AtomicInteger waits = new AtomicInteger();
    private final Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("basicAck")) {
                    assertEquals(false, arguments[1]);
                    acknowledgements.add((Long) arguments[0]);
                    return null;
                }
                if (method.getName().equals("toString")) { return "Offline ACK recorder"; }
                throw new AssertionError("Unexpected channel operation: " + method.getName());
            });

    @Test
    void persistentConfirmedPublishAndPendingRetryPreserveOneTaskAndApiContract() throws Exception {
        var repository = new InMemoryTaskRepository();
        var service = new TaskService(repository, publisher);
        var mvc = MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String body = "{\"idempotencyKey\":\"repair\",\"input\":\"hello\"}";
        template.mode = "nack";
        var failed = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DISPATCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.taskId").exists()).andReturn();
        UUID id = repository.createOrGet("repair", "hello").task().id();
        assertTrue(failed.getResponse().getContentAsString().contains(id.toString()));
        assertEquals(TaskStatus.PENDING, repository.findById(id).orElseThrow().status());
        template.mode = "ack";
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        assertEquals(id, TaskMessages.id(template.messages.get(1)));
        assertEquals(MessageDeliveryMode.PERSISTENT, template.messages.get(1).getMessageProperties().getDeliveryMode());
        repository.start(id);
        repository.succeed(id, "done");
        service.submit("repair", "hello");
        assertEquals(2, template.messages.size(), "Terminal tasks are never republished");
        assertThrows(TaskException.class, () -> service.submit("repair", "changed"));
    }

    @Test
    void returnTimeoutAndConnectionFailuresAreNeverSuccessfulPublications() {
        for (String mode : List.of("returned", "timeout", "connection")) {
            template.mode = mode;
            var error = assertThrows(TaskDispatchException.class, () -> publisher.dispatch(UUID.randomUUID()));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("synthetic-private-detail"));
        }
    }

    @Test
    void successAndDuplicateAckAfterCommitWithoutSecondModelCall() {
        var repository = new InMemoryTaskRepository();
        UUID id = repository.createOrGet("success", "hello").task().id();
        var calls = new AtomicInteger();
        var consumer = consumer(new TaskWorker(repository, input -> {
            calls.incrementAndGet();
            return "result";
        }));
        consumer.receive(delivery(id), channel);
        assertEquals(TaskStatus.SUCCESS, repository.findById(id).orElseThrow().status());
        consumer.receive(delivery(id), channel);
        assertEquals(1, calls.get());
        assertEquals(2, acknowledgements.size());
        assertTrue(template.messages.isEmpty());
    }

    @Test
    void modelBusinessFailureIsPersistedAndAcknowledged() {
        var repository = new InMemoryTaskRepository();
        UUID id = repository.createOrGet("model-failure", "[MOCK_FAIL]").task().id();
        consumer(new TaskWorker(repository, new MockModelClient())).receive(delivery(id), channel);
        assertEquals("MODEL_CALL_FAILED", repository.findById(id).orElseThrow().errorCode());
        assertEquals(1, acknowledgements.size());
        assertTrue(template.messages.isEmpty());
    }

    @Test
    void temporaryClaimFailureBacksOffAndRecoversWithoutCallingModelTwice() {
        AtomicInteger claims = new AtomicInteger();
        var repository = new InMemoryTaskRepository() {
            @Override public TextTask start(UUID id) {
                if (claims.incrementAndGet() == 1) { throw new DataAccessResourceFailureException("offline outage"); }
                return super.start(id);
            }
        };
        UUID id = repository.createOrGet("recover", "hello").task().id();
        consumer(new TaskWorker(repository, new MockModelClient())).receive(delivery(id), channel);
        assertEquals(2, claims.get());
        assertEquals(1, waits.get());
        assertEquals(1, acknowledgements.size());
        assertEquals(TaskStatus.SUCCESS, repository.findById(id).orElseThrow().status());
    }

    @Test
    void exhaustedClaimFailuresAreIsolatedBeforeAckAndLeavePending() {
        AtomicInteger claims = new AtomicInteger();
        var repository = new InMemoryTaskRepository() {
            @Override public TextTask start(UUID id) {
                claims.incrementAndGet();
                throw new DataAccessResourceFailureException("offline outage");
            }
        };
        UUID id = repository.createOrGet("exhaust", "hello").task().id();
        consumer(new TaskWorker(repository, input -> { fail("Model must not run"); return null; }))
                .receive(delivery(id), channel);
        assertEquals(3, claims.get());
        assertEquals(2, waits.get());
        assertEquals(TaskStatus.PENDING, repository.findById(id).orElseThrow().status());
        assertEquals("STORAGE_RETRY_EXHAUSTED", parkedReason());
        assertEquals(1, acknowledgements.size());
    }

    @Test
    void completionFailureIsNotRetriedOrRewrittenAsModelFailure() {
        var repository = new InMemoryTaskRepository() {
            @Override public TextTask succeed(UUID id, String output) {
                throw new DataAccessResourceFailureException("offline completion outage");
            }
        };
        AtomicInteger calls = new AtomicInteger();
        UUID id = repository.createOrGet("completion", "hello").task().id();
        consumer(new TaskWorker(repository, input -> { calls.incrementAndGet(); return "done"; }))
                .receive(delivery(id), channel);
        assertEquals(1, calls.get());
        assertEquals(0, waits.get());
        assertEquals(TaskStatus.RUNNING, repository.findById(id).orElseThrow().status());
        assertEquals("COMPLETION_UNKNOWN", parkedReason());
        assertEquals(1, acknowledgements.size());
    }

    @Test
    void malformedMissingAndRunningMessagesAreQuarantinedWithoutModelCall() {
        var repository = new InMemoryTaskRepository();
        var consumer = consumer(new TaskWorker(repository, input -> { fail("Must not run"); return null; }));
        consumer.receive(new Message(new byte[0], new MessageProperties()), channel);
        assertEquals("INVALID_MESSAGE", parkedReason());
        consumer.receive(delivery(UUID.randomUUID()), channel);
        assertEquals("TASK_NOT_FOUND", parkedReason());
        UUID id = repository.createOrGet("running", "hello").task().id();
        repository.start(id);
        consumer.receive(delivery(id), channel);
        assertEquals("RUNNING_UNRESOLVED", parkedReason());
        assertEquals(3, acknowledgements.size());
    }

    @Test
    void isolationFailurePausesAndLeavesDeliveryUnacknowledgedWithoutRequeueLoop() {
        template.mode = "nack";
        consumer(new TaskWorker(new InMemoryTaskRepository(), new MockModelClient()))
                .receive(delivery(UUID.randomUUID()), channel);
        assertEquals(1, pauses.get());
        assertTrue(acknowledgements.isEmpty());
    }

    @Test
    void topologyIsDurableAndDedicatedWithoutAutoDelete() {
        assertThrows(IllegalArgumentException.class, () -> new TaskQueueNames("p2.other"));
        var declarations = names.declarations();
        assertEquals(2, declarations.getDeclarablesByType(Queue.class).size());
        for (Queue queue : declarations.getDeclarablesByType(Queue.class)) {
            assertTrue(queue.isDurable());
            assertFalse(queue.isAutoDelete());
            assertFalse(queue.isExclusive());
        }
    }

    @Test
    void ackChannelFailurePausesAfterCommitWithoutRunningModelAgain() {
        var repository = new InMemoryTaskRepository();
        UUID id = repository.createOrGet("ack-failure", "hello").task().id();
        var brokenChannel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                (proxy, method, args) -> { throw new java.io.IOException("synthetic ACK failure"); });
        consumer(new TaskWorker(repository, new MockModelClient())).receive(delivery(id), brokenChannel);
        assertEquals(TaskStatus.SUCCESS, repository.findById(id).orElseThrow().status());
        assertEquals(1, pauses.get());
        assertTrue(template.messages.isEmpty());
    }

    private RabbitTaskConsumer consumer(TaskWorker worker) {
        return new RabbitTaskConsumer(worker, publisher, pauses::incrementAndGet, 3, 100,
                millis -> waits.incrementAndGet());
    }
    private Message delivery(UUID id) {
        Message message = TaskMessages.task(id);
        message.getMessageProperties().setDeliveryTag(42);
        return message;
    }
    private Object parkedReason() {
        assertEquals("park", template.routes.get(template.routes.size() - 1));
        return template.messages.get(template.messages.size() - 1).getMessageProperties().getHeaders().get("x-isolation-reason");
    }

    private static class FakeTemplate extends RabbitTemplate {
        String mode = "ack";
        final List<Message> messages = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        @Override public void send(String exchange, String route, Message message, CorrelationData correlation) {
            messages.add(message);
            routes.add(route);
            switch (mode) {
                case "timeout" -> { }
                case "connection" -> throw new org.springframework.amqp.AmqpConnectException(
                        new java.net.ConnectException("synthetic-private-detail"));
                case "returned" -> {
                    correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", exchange, route));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                }
                default -> correlation.getFuture().complete(new CorrelationData.Confirm(mode.equals("ack"), null));
            }
        }
    }
}
