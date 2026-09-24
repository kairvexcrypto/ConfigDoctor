package ru.warndev.configdoctor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DoctorService implements AutoCloseable {
    private final TargetFiles files;
    private final String rulesFile;
    private final ReportWriter writer;
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RuleCatalog catalog;

    public DoctorService(TargetFiles files, String rulesFile, Path reports) throws DoctorException {
        this.files = files;
        this.rulesFile = TargetFiles.validate(rulesFile);
        this.writer = new ReportWriter(reports);
        this.catalog = RuleCatalog.parse(files.read(rulesFile));
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), runnable -> {
                    Thread thread = new Thread(runnable, "ConfigDoctor-IO");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public List<String> profiles() {
        return List.copyOf(catalog.profiles().keySet());
    }

    public CompletableFuture<List<CheckReport>> check(String id) {
        RuleCatalog snapshot = catalog;
        if (id != null && !snapshot.profiles().containsKey(id)) {
            return CompletableFuture.failedFuture(new DoctorException("PROFILE_UNKNOWN", "Профиль не найден"));
        }
        return submit(() -> {
            List<CheckReport> reports = new ArrayList<>();
            for (Profile profile : snapshot.profiles().values()) {
                if (id != null && !id.equals(profile.id())) {
                    continue;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new DoctorException("CANCELLED", "Проверка прервана");
                }
                Validator validator = new Validator();
                try {
                    Object document = new SafeYaml().parse(files.read(profile.file()));
                    reports.add(validator.check(profile, document));
                } catch (DoctorException error) {
                    reports.add(validator.failure(profile, error));
                }
            }
            return List.copyOf(reports);
        });
    }

    public CompletableFuture<Integer> reload() {
        return submit(() -> {
            RuleCatalog replacement = RuleCatalog.parse(files.read(rulesFile));
            catalog = replacement;
            return replacement.profiles().size();
        });
    }

    public CompletableFuture<Path> export(List<CheckReport> reports) {
        List<CheckReport> snapshot = List.copyOf(reports);
        return submit(() -> writer.write(snapshot));
    }

    private <T> CompletableFuture<T> submit(Operation<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new DoctorException("CLOSED", "Сервис остановлен"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            executor.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                try {
                    result.complete(operation.run());
                } catch (DoctorException error) {
                    result.completeExceptionally(error);
                } catch (RuntimeException error) {
                    result.completeExceptionally(new DoctorException("INTERNAL", "Внутренняя ошибка проверки"));
                }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(new DoctorException("BUSY", "Очередь заполнена; повторите позже"));
        }
        if (closed.get()) {
            result.cancel(false);
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        pending.forEach(future -> future.cancel(false));
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run() throws DoctorException;
    }
}
