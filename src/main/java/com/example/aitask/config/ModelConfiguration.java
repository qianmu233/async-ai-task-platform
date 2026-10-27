package com.example.aitask.config;

import com.example.aitask.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class ModelConfiguration {
    @Bean static BeanFactoryPostProcessor validateModelMode(Environment env) {
        return factory -> {
            String mode = env.getProperty("platform.model.mode", "mock");
            if (!mode.equals("mock") && !mode.equals("openai-compatible")) {
                throw new IllegalArgumentException("Model mode must be mock or openai-compatible");
            }
            if (mode.equals("openai-compatible")) {
                var settings = settings(env);
                long lease = env.getProperty("platform.execution.timeout-ms", Long.class, 60000L);
                // 15s reserve covers existing bounded connection acquisition + write transaction budgets.
                if (!"true".equalsIgnoreCase(env.getProperty("platform.execution.timeout-enabled", "false"))
                        || settings.requestTimeoutMillis() + 15000 > lease) {
                    throw new IllegalArgumentException("HTTP model requires worker timeout protection and request timeout + 15000ms <= execution timeout");
                }
            }
        };
    }
    @Bean @ConditionalOnProperty(name="platform.model.mode",havingValue="mock",matchIfMissing=true)
    MockModelClient mockModelClient() { return new MockModelClient(); }

    @Bean @ConditionalOnProperty(name="platform.model.mode",havingValue="openai-compatible")
    OpenAiCompatibleModelClient openAiCompatibleModelClient(Environment env, ObjectMapper mapper) {
        return new OpenAiCompatibleModelClient(settings(env), mapper);
    }
    private static OpenAiModelSettings settings(Environment env) {
        return new OpenAiModelSettings(OpenAiModelSettings.parseBaseUrl(env.getProperty("platform.model.base-url", "")),
                env.getProperty("platform.model.api-key", ""), env.getProperty("platform.model.name", ""),
                env.getProperty("platform.model.connect-timeout-ms", Long.class, 2000L),
                env.getProperty("platform.model.request-timeout-ms", Long.class, 10000L),
                env.getProperty("platform.model.max-response-bytes", Integer.class, 131072));
    }
}
