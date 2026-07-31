package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.exception.TaskCancelledException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

class TaskCancellationRegistryTest {

    private TaskCancellationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TaskCancellationRegistry();
    }

    @Test
    void requestCancel_setsFlagAndCancelsHttp() {
        Long taskId = 42L;
        CompletableFuture<String> http = new CompletableFuture<>();
        registry.registerHttp(taskId, http);

        registry.requestCancel(taskId);

        Assertions.assertTrue(registry.isCancelled(taskId));
        Assertions.assertTrue(http.isCancelled());
        Assertions.assertThrows(TaskCancelledException.class, () -> registry.throwIfCancelled(taskId));
    }

    @Test
    void clear_allowsRerun() {
        Long taskId = 7L;
        registry.requestCancel(taskId);
        registry.clear(taskId);
        Assertions.assertFalse(registry.isCancelled(taskId));
        Assertions.assertDoesNotThrow(() -> registry.throwIfCancelled(taskId));
    }

    @Test
    void registerHttp_whenAlreadyCancelled_cancelsImmediately() {
        Long taskId = 9L;
        registry.requestCancel(taskId);
        CompletableFuture<String> http = new CompletableFuture<>();
        Assertions.assertThrows(TaskCancelledException.class, () -> registry.registerHttp(taskId, http));
        Assertions.assertTrue(http.isCancelled());
    }

    @Test
    void hasActiveWork_tracksPipelineFuture() {
        Long taskId = 11L;
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> pipeline = CompletableFuture.runAsync(() -> {
            started.set(true);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        registry.registerPipeline(taskId, pipeline);
        Assertions.assertTrue(registry.hasActiveWork(taskId));
        pipeline.join();
        registry.unregisterPipeline(taskId);
        Assertions.assertFalse(registry.hasActiveWork(taskId));
        Assertions.assertTrue(started.get());
    }
}
