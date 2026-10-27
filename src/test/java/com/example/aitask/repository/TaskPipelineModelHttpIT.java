package com.example.aitask.repository;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.api.TaskController;
import com.example.aitask.messaging.*;
import com.example.aitask.model.*;
import com.example.aitask.outbox.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual app HTTP + MySQL + Rabbit + loopback model HTTP. Never a paid/third-party LLM test. */
class TaskPipelineModelHttpIT {
    @TestConfiguration(proxyBeanMethods=false)
    @Profile("local-model-http-it")
    static class NoGlobalSchedules {
        @Bean static BeanFactoryPostProcessor removeGlobalSchedulersForDataIsolation() {
            return factory -> {
                var registry = (BeanDefinitionRegistry) factory;
                String scheduled = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                if (registry.containsBeanDefinition(scheduled)) { registry.removeBeanDefinition(scheduled); }
            };
        }
    }
    @Test void realInfrastructureCompletesLocalHttpModelAndDoesNotRetry429OrDuplicates() throws Exception {
        assertTrue(Boolean.getBoolean("phase4c.model.http.enabled"), "Use explicit -Pmodel-http-it");
        assertEquals("true", System.getenv("OUTBOX_IT_SCHEMA_APPROVED"));
        assertEquals("true", System.getenv("WORKER_LEASE_IT_SCHEMA_APPROVED"));
        var mysql = MySqlAcceptanceSupport.configuration(true, System.getenv());
        String run = UUID.randomUUID().toString();
        var properties = new HashMap<String,Object>();
        ConfigurableApplicationContext app = null;
        try (var model = new LocalModelHttpServer()) {
            properties.put("platform.storage.mode", "mysql");
            properties.put("platform.storage.mysql.host", mysql.host());
            properties.put("platform.storage.mysql.port", mysql.port());
            properties.put("platform.storage.mysql.username", mysql.username());
            properties.put("platform.storage.mysql.password", mysql.password());
            properties.put("platform.messaging.mode", "rabbit");
            properties.put("platform.messaging.queue-prefix", "p1.it.model-http." + run);
            properties.put("platform.messaging.rabbitmq.host", "127.0.0.1");
            properties.put("platform.messaging.rabbitmq.port", 5673);
            properties.put("platform.messaging.rabbitmq.username", required("RABBITMQ_USERNAME"));
            properties.put("platform.messaging.rabbitmq.password", required("RABBITMQ_PASSWORD"));
            properties.put("platform.messaging.rabbitmq.virtual-host", "/ai-task-platform");
            properties.put("platform.outbox.enabled", true);
            properties.put("platform.execution.timeout-enabled", true);
            properties.put("platform.execution.timeout-ms", 30000);
            properties.put("platform.model.mode", "openai-compatible");
            properties.put("platform.model.base-url", model.baseUrl().toString());
            properties.put("platform.model.api-key", model.token());
            properties.put("platform.model.name", "local-test");
            properties.put("platform.model.connect-timeout-ms", 500);
            properties.put("platform.model.request-timeout-ms", 2000);
            properties.put("platform.model.max-response-bytes", 4096);
            properties.put("platform.rate-limit.enabled", false);
            properties.put("server.address", "127.0.0.1"); properties.put("server.port", 0);
            properties.put("logging.level.root", "OFF"); properties.put("debug", false);
            var application = new SpringApplication(AiTaskPlatformApplication.class, NoGlobalSchedules.class);
            application.setAdditionalProfiles("local-model-http-it");
            application.addInitializers(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("private-local-model-it", properties)));
            app = application.run("--debug=false", "--logging.level.root=OFF", "--spring.main.banner-mode=off");
            assertFalse(app.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"),
                    "Refuse global polling of existing task/event data in acceptance");
            assertInstanceOf(OpenAiCompatibleModelClient.class, app.getBean(ModelClient.class));
            var jdbc = app.getBean(JdbcTemplate.class);
            assertEquals("ai_task_platform", jdbc.queryForObject("SELECT DATABASE()", String.class));
            String mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
            assertTrue(mysqlVersion.startsWith("8.0."));
            assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_task_app@"));
            app.getBean(TaskRepository.class).verifyExecutionTimeoutSchema();
            var manager = app.getBean(PlatformTransactionManager.class);
            var publisher = app.getBean(ConfirmedRabbitPublisher.class);
            var settings = app.getBean(OutboxSettings.class);
            var admin = app.getBean(RabbitAdmin.class);
            var listener = (SimpleMessageListenerContainer) app.getBean(RabbitListenerEndpointRegistry.class)
                    .getListenerContainer(RabbitTaskConsumer.LISTENER_ID);
            AtomicInteger deliveries = new AtomicInteger();
            var actual = (ChannelAwareMessageListener) listener.getMessageListener();
            listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                actual.onMessage(message, channel); deliveries.incrementAndGet();
            });
            RestClient http = RestClient.create("http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port"));
            String key = "model-http-it:" + run + ":ok";
            var accepted = http.post().uri("/api/tasks").body(new TaskController.SubmitRequest(key, "hello HTTP model 中文"))
                    .retrieve().toEntity(TaskController.SubmitResponse.class);
            assertEquals(202, accepted.getStatusCode().value());
            UUID success = accepted.getBody().taskId();
            assertTask(jdbc, success, "PENDING", 0, null);
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ai_task_outbox WHERE task_id=?", String.class, success.toString()));
            AtomicBoolean lockFreeDuringModel = new AtomicBoolean();
            // A different physical connection can acquire the row lock while model HTTP is executing.
            Properties credentials = new Properties();
            credentials.setProperty("user", mysql.username()); credentials.setProperty("password", mysql.password());
            try (Connection inspection = DriverManager.getConnection(mysql.jdbcUrl(), credentials)) {
                credentials.clear();
                inspection.setAutoCommit(false);
                try (var statement = inspection.createStatement()) { statement.execute("SET SESSION innodb_lock_wait_timeout=1"); }
                model.onRequest = () -> {
                    try (var statement = inspection.prepareStatement("SELECT status,execution_token FROM ai_text_task WHERE task_id=? FOR UPDATE")) {
                        statement.setString(1, success.toString());
                        try (var row = statement.executeQuery()) {
                            lockFreeDuringModel.set(row.next() && "RUNNING".equals(row.getString(1)) && row.getString(2) != null);
                        }
                        inspection.commit();
                    } catch (SQLException unavailable) { throw MySqlAcceptanceSupport.failure("model-no-transaction-lock", unavailable); }
                };
                assertEquals(1, dispatch(jdbc, manager, settings, publisher, success).markedSent());
                await(() -> terminal(jdbc, success, "SUCCESS") && deliveries.get() == 1);
                assertTrue(lockFreeDuringModel.get(), "Model HTTP must not hold task transaction/row lock");
                model.onRequest = () -> {};
            } finally { credentials.clear(); }
            var result = http.get().uri("/api/tasks/"+success+"/result").retrieve().toEntity(TaskController.ResultResponse.class);
            assertEquals(200, result.getStatusCode().value());
            assertEquals("[LOCAL HTTP] hello HTTP model 中文", result.getBody().output());
            assertTask(jdbc, success, "SUCCESS", 2, null);
            publisher.dispatch(success);
            await(() -> deliveries.get() == 2);
            assertEquals(1, model.calls.get(), "Duplicate message cannot call the HTTP model again");
            assertEquals(200, http.post().uri("/api/tasks").body(new TaskController.SubmitRequest(key, "hello HTTP model 中文"))
                    .retrieve().toEntity(TaskController.SubmitResponse.class).getStatusCode().value());

            model.scenario(LocalModelHttpServer.Scenario.RATE_LIMITED);
            UUID failed = http.post().uri("/api/tasks").body(new TaskController.SubmitRequest("model-http-it:"+run+":429", "rate limit test"))
                    .retrieve().body(TaskController.SubmitResponse.class).taskId();
            assertEquals(1, dispatch(jdbc, manager, settings, publisher, failed).markedSent());
            await(() -> terminal(jdbc, failed, "FAILED") && deliveries.get() == 3);
            assertTask(jdbc, failed, "FAILED", 2, "MODEL_RATE_LIMITED");
            assertEquals(2, model.calls.get());
            assertTrue(model.validAuthorization.get());
            var names = app.getBean(TaskQueueNames.class);
            assertEquals(0, admin.getQueueInfo(names.work()).getMessageCount());
            assertEquals(0, admin.getQueueInfo(names.parking()).getMessageCount());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_outbox WHERE task_id IN (?,?) AND status='SENT'",
                    Integer.class, success.toString(), failed.toString()));
            System.out.printf("MODEL_HTTP_IT RESULT run=%s mysql=%s localModelOnly=true thirdPartyCalls=0 httpAccepted=2 "
                    + "eventsSent=2 deliveries=3 modelHttpCalls=2 duplicateSuppressed=true rateLimitFailed=true "
                    + "modelLockFree=true taskVersions=2 retainedTasks=2 retainedEvents=2%n", run, mysqlVersion);
        } catch (RuntimeException | SQLException failure) { throw MySqlAcceptanceSupport.failure("local-model-http-pipeline", failure); }
        finally { if(app != null){app.close();} properties.clear(); }
    }
    private OutboxDispatcher.Result dispatch(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxSettings settings,
                                             ConfirmedRabbitPublisher publisher, UUID task) {
        var scoped = new JdbcOutboxRepository(jdbc, manager, settings, task);
        return new OutboxDispatcher(scoped, (event,id) -> publisher.publish(TaskMessages.task(id), "task"), settings).dispatchBatch();
    }
    private boolean terminal(JdbcTemplate jdbc, UUID task, String status) {
        return status.equals(jdbc.queryForObject("SELECT status FROM ai_text_task WHERE task_id=?", String.class, task.toString()));
    }
    private void assertTask(JdbcTemplate jdbc, UUID id, String status, int version, String error) {
        var row=jdbc.queryForMap("SELECT status,version,error_code,completed_at,execution_token,execution_deadline FROM ai_text_task WHERE task_id=?", id.toString());
        assertEquals(status,row.get("status")); assertEquals(version,((Number)row.get("version")).intValue());
        assertEquals(error,row.get("error_code")); assertNull(row.get("execution_token")); assertNull(row.get("execution_deadline"));
        if(version==2){assertNotNull(row.get("completed_at"));}
    }
    private String required(String name) { String value=System.getenv(name); assertTrue(value!=null&&!value.isBlank(),"Missing external "+name);return value; }
    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){if(condition.getAsBoolean()){return;}Thread.sleep(50);}
        fail("Real local-model pipeline deadline exceeded; no fallback");
    }
}
