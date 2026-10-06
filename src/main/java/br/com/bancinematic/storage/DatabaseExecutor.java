package br.com.bancinematic.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs every database operation on one dedicated thread, so the server thread never
 * waits for disk or network I/O. Because there is a single worker, operations are also
 * executed in submission order, which keeps "check, write, log" sequences atomic with
 * respect to each other without extra locking.
 *
 * <p>Never call anything that waits for the server thread from inside a task.
 */
public final class DatabaseExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 15;

    /** Null in inline mode. */
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

    /** Runs each task immediately on the calling thread. Intended for tests only. */
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

    /** Finishes the queued operations (up to a time limit) and stops the worker. */
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
