package br.com.bancinematic.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExecutorTest {
    @Test
    void tasksRunOnADedicatedThreadNotOnTheCaller() throws Exception {
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            Thread caller = Thread.currentThread();
            Thread worker = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);

            assertNotEquals(caller, worker);
            assertEquals("BanCinematic-Database", worker.getName());
        }
    }

    @Test
    void callerIsNotBlockedWhileATaskIsRunning() throws Exception {
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            CompletableFuture<Void> slow = executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(slow.isDone(), "A chamada deve voltar antes de a tarefa terminar.");
            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void tasksRunInSubmissionOrderOneAtATime() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            CompletableFuture<?> last = null;
            for (int i = 0; i < 50; i++) {
                int number = i;
                last = executor.execute(() -> order.add(number));
            }
            last.get(5, TimeUnit.SECONDS);
        }

        for (int i = 0; i < 50; i++) assertEquals(i, order.get(i));
    }

    @Test
    void closeFinishesQueuedWorkBeforeReturning() throws Exception {
        List<Integer> done = Collections.synchronizedList(new ArrayList<>());
        DatabaseExecutor executor = DatabaseExecutor.create();
        for (int i = 0; i < 20; i++) {
            int number = i;
            executor.execute(() -> {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                done.add(number);
            });
        }

        executor.close();

        assertEquals(20, done.size(), "Punições pendentes precisam chegar ao banco ao desligar.");
    }

    @Test
    void submitAfterCloseFailsTheFutureInsteadOfThrowing() {
        DatabaseExecutor executor = DatabaseExecutor.create();
        executor.close();

        CompletableFuture<Integer> future = executor.submit(() -> 1);

        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void exceptionsBecomeFailedFuturesAndDoNotKillTheWorker() throws Exception {
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            CompletableFuture<Integer> failing = executor.submit(() -> {
                throw new IllegalStateException("falha de teste");
            });
            assertThrows(ExecutionException.class, () -> failing.get(5, TimeUnit.SECONDS));

            assertEquals(7, executor.submit(() -> 7).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void inlineModeRunsOnTheCallingThread() {
        DatabaseExecutor executor = DatabaseExecutor.inline();

        assertEquals(Thread.currentThread(), executor.submit(Thread::currentThread).join());
        assertTrue(executor.submit(() -> {
            throw new IllegalStateException("x");
        }).isCompletedExceptionally());
    }
}
