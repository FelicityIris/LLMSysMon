package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;

import java.time.Instant;

public class SystemSnapshot {
    private final Instant timestamp;
    private final CpuMetrics cpu;

    public SystemSnapshot(Instant timestamp, CpuMetrics cpu) {
        this.timestamp = timestamp;
        this.cpu = cpu;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public CpuMetrics getCpu() {
        return cpu;
    }
}
