package br.com.bancinematic.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DatabaseExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 15;

    private final ExecutorService service;

    private DatabaseExecutor(ExecutorService service) {
        this.service = service;
    }

    public static DatabaseExecutor create() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "BanCinematic-Database");
            thread.setDaemon(true);
            return thread;
        };
        return new DatabaseExecutor(Executors.newSingleThreadExecutor(factory));
    }

    public static DatabaseExecutor inline() {
        return new DatabaseExecutor(null);
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (service == null) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        try {
            return CompletableFuture.supplyAsync(task, service);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Void> execute(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public void close() {
        if (service == null) return;
        service.shutdown();
        try {
            if (!service.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException exception) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
