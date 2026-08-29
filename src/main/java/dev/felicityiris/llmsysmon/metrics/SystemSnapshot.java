package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryMetrics;

import java.time.Instant;

public class SystemSnapshot {
    private final Instant timestamp;
    private final CpuMetrics cpu;
    private final MemoryMetrics memory;

    public SystemSnapshot(Instant timestamp, CpuMetrics cpu, MemoryMetrics memory) {
        this.timestamp = timestamp;
        this.cpu = cpu;
        this.memory = memory;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public CpuMetrics getCpu() {
        return cpu;
    }

    public MemoryMetrics getMemory() {
        return memory;
    }
}
