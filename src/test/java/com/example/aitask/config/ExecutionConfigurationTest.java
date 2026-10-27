package com.example.aitask.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionConfigurationTest {
    @Test
    void boundsQueueAndRejectsOverflow() throws Exception {
        var executor = new ExecutionConfiguration().taskExecutor(1, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        var config = new ExecutionConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.taskExecutor(0, 1));
        assertThrows(IllegalArgumentException.class, () -> config.taskExecutor(1, 0));
    }
}
