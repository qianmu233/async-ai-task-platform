package com.example.aitask.config;

import com.example.aitask.ratelimit.*;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.rate-limit.enabled", havingValue = "true")
public class SubmissionRateLimitConfiguration {
    @Bean
    LettuceConnectionFactory submissionRedisConnectionFactory(Environment env) {
        // Enabling automatic forwarded-header processing would make the remote peer identity untrustworthy.
        if (!env.getProperty("server.forward-headers-strategy", "NONE").equalsIgnoreCase("NONE")) {
            throw new IllegalArgumentException("Redis IP rate limiting requires server.forward-headers-strategy=NONE");
        }
        String host = env.getProperty("platform.rate-limit.redis.host", "127.0.0.1");
        int port = env.getProperty("platform.rate-limit.redis.port", Integer.class, 6380);
        String password = env.getProperty("platform.rate-limit.redis.password", "");
        int timeout = env.getProperty("platform.rate-limit.redis.timeout-ms", Integer.class, 1000);
        if (!host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535 || password.isBlank()
                || timeout < 100 || timeout > 10000) {
            throw new IllegalArgumentException("Redis limiter requires valid host/port/timeout and external password");
        }
        var server = new RedisStandaloneConfiguration(host, port);
        server.setUsername(env.getProperty("platform.rate-limit.redis.username", "default"));
        server.setPassword(password);
        var options = ClientOptions.builder().autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(timeout)).build()).build();
        var client = LettuceClientConfiguration.builder().clientOptions(options)
                .commandTimeout(Duration.ofMillis(timeout)).shutdownTimeout(Duration.ofMillis(100)).build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    RedisSubmissionRateLimiter submissionRateLimiter(LettuceConnectionFactory submissionRedisConnectionFactory,
                                                    Environment env) {
        return new RedisSubmissionRateLimiter(new StringRedisTemplate(submissionRedisConnectionFactory),
                env.getProperty("platform.rate-limit.namespace", "p1:ai-task:submit:v1"),
                env.getProperty("platform.rate-limit.max-requests", Integer.class, 20),
                env.getProperty("platform.rate-limit.window-ms", Long.class, 60000L));
    }

    @Bean
    WebMvcConfigurer submissionRateLimitMvc(RedisSubmissionRateLimiter limiter) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SubmissionRateLimitInterceptor(limiter));
            }
        };
    }
}
