package com.classsync;

import javafx.concurrent.Task;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs database/file work off the JavaFX Application Thread.
 *
 * work      runs on a background thread (never touch UI controls there)
 * onSuccess runs on the JavaFX thread with the result
 * onError   runs on the JavaFX thread with the exception
 *
 * Results are dropped if the user logged out / changed since the task started.
 */
public class Background {

    private final Session session;
    private final Set<Task<?>> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool;

    public Background(Session session) {
        this.session = session;
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "classsync-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public <T> Task<T> run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        long startedIn = session.generation();
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> {
            running.remove(task);
            if (session.generation() == startedIn) {
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            running.remove(task);
            if (session.generation() == startedIn) {
                onError.accept(task.getException());
            }
        });
        task.setOnCancelled(e -> running.remove(task));
        running.add(task);
        pool.submit(task);
        return task;
    }

    /** Called on logout: user-specific work is cancelled. */
    public void cancelAll() {
        for (Task<?> t : running) {
            t.cancel(true);
        }
        running.clear();
    }

    /** Called from Application.stop(). */
    public void shutdown() {
        cancelAll();
        pool.shutdownNow();
    }
}
