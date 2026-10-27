package com.example.aitask.config;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ModelConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTaskPlatformApplication.class).withPropertyValues("debug=false",
                    "platform.execution.workers=1", "platform.execution.queue-capacity=1");
    private ApplicationContextRunner http() {
        return runner.withPropertyValues("platform.model.mode=openai-compatible", "platform.model.base-url=http://127.0.0.1:1/v1",
                "platform.model.api-key=test-only", "platform.model.name=local-test", "platform.execution.timeout-enabled=true",
                "platform.execution.timeout-check-ms=60000");
    }
    @Test void defaultStaysMockAndExplicitHttpReplacesItWithoutCallingProviderOnStartup() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ModelClient.class).hasSingleBean(MockModelClient.class));
        http().run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ModelClient.class)
                .hasSingleBean(OpenAiCompatibleModelClient.class).doesNotHaveBean(MockModelClient.class));
    }
    @Test void invalidCredentialsEndpointModeAndDeadlineBudgetRefuseStartup() {
        runner.withPropertyValues("platform.model.mode=typo").run(ctx -> assertThat(ctx).hasFailed());
        for(String invalid : new String[]{"platform.model.api-key=", "platform.model.name=",
                "platform.model.base-url=http://remote.invalid/v1", "platform.model.base-url=https://user:secret@example.invalid/v1",
                "platform.model.connect-timeout-ms=20000", "platform.model.max-response-bytes=0",
                "platform.execution.timeout-enabled=false", "platform.execution.timeout-ms=20000"}) {
            http().withPropertyValues(invalid).run(ctx -> assertThat(ctx).hasFailed());
        }
    }
}
