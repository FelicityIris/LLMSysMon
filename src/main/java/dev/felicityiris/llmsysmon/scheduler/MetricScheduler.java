package dev.felicityiris.llmsysmon.scheduler;

import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MetricScheduler {
    private final MetricService metricService;
    private final SchedulerConfig config;
    private final ScheduledExecutorService executor;
    private final Consumer<SystemSnapshot> snapshotConsumer;

    public MetricScheduler(MetricService metricService, SchedulerConfig config, Consumer<SystemSnapshot> snapshotConsumer) {
        this.metricService = metricService;
        this.config = config;
        this.snapshotConsumer = snapshotConsumer;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        executor.scheduleAtFixedRate(this::collect, 0, config.getIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void collect() {
        try {
            SystemSnapshot snapshot = metricService.collectSnapshot();
            snapshotConsumer.accept(snapshot);
        } catch (Exception e) {
            System.err.println("Failed to collect system metrics: " + e.getMessage());
        }
    }

    public void stop() {
        executor.shutdownNow();
    }
}
