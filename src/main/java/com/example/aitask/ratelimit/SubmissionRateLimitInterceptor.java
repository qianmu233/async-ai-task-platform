package com.example.aitask.ratelimit;

import com.example.aitask.api.TaskController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public final class SubmissionRateLimitInterceptor implements HandlerInterceptor {
    private final SubmissionRateLimiter limiter;

    public SubmissionRateLimitInterceptor(SubmissionRateLimiter limiter) { this.limiter = limiter; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod()) && handler instanceof HandlerMethod method
                && method.getBeanType() == TaskController.class && method.getMethod().getName().equals("submit")) {
            // Only the direct transport peer. Forwarded/X-Forwarded-For/client identity headers are ignored.
            var decision = limiter.acquire(request.getRemoteAddr());
            if (!decision.allowed()) { throw RateLimitException.exceeded(decision.retryAfterSeconds()); }
        }
        return true;
    }
}
