package com.cadence.flagservice.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;

/**
 * Java 21 virtual threads for the two places this service is I/O-bound and bursty:
 * metric ingestion and scheduled release-health evaluation.
 *
 * <h2>Why not a ThreadPoolTaskExecutor backed by a virtual thread factory?</h2>
 * Handing {@code Thread.ofVirtual().factory()} to a {@code ThreadPoolTaskExecutor} compiles and runs,
 * but it is the wrong shape. A thread pool exists to <i>ration</i> expensive threads; virtual threads
 * are cheap and are meant to be created per task. Pooling them reintroduces exactly the queueing and
 * pool-exhaustion behaviour Loom removes, and worse, a pooled virtual thread carries its thread-locals
 * between unrelated tasks.
 *
 * <p>{@link SimpleAsyncTaskExecutor} with {@code setVirtualThreads(true)} is the Spring 6.1 construct
 * for this: one new virtual thread per task, no pool. The {@code concurrencyLimit} is not a pool size —
 * it is back-pressure on the <i>downstream</i> resource (Redis connections), which is finite even though
 * the threads are not. Ingestion may fan out thousands of concurrent short writes without ever
 * exhausting a thread pool, which is the property the ingestion pipeline needs.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer, SchedulingConfigurer {

    public static final String INGESTION_EXECUTOR = "ingestionExecutor";

    private final CadenceProperties properties;

    public AsyncConfig(CadenceProperties properties) {
        this.properties = properties;
    }

    /**
     * Backs {@code @Async(INGESTION_EXECUTOR)} on the metric-ingestion entry point, so
     * {@code POST /sdk/v1/events} can return 202 the instant the payload is parsed.
     */
    @Bean(name = INGESTION_EXECUTOR)
    public Executor ingestionExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("cadence-ingest-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.getMetrics().getIngestionConcurrency());
        executor.setTaskTerminationTimeout(5_000);
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return ingestionExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // Ingestion is fire-and-forget; an exception here must be logged loudly, never swallowed silently.
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    /**
     * The scheduler that runs the rollback watcher, the snapshot writer and stage transitions.
     * Virtual threads again: every one of these tasks is dominated by waiting on Redis or Postgres,
     * and a slow Postgres query must never delay the 30-second watcher tick for other flags.
     */
    @Bean
    public SimpleAsyncTaskScheduler taskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("cadence-sched-");
        scheduler.setVirtualThreads(true);
        scheduler.setConcurrencyLimit(64);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
