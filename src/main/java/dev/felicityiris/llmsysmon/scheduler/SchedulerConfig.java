package dev.felicityiris.llmsysmon.scheduler;

public class SchedulerConfig {
    private final long intervalMillis;

    public SchedulerConfig(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Interval must be greater than zero.");
        }

        this.intervalMillis = intervalMillis;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }
}
