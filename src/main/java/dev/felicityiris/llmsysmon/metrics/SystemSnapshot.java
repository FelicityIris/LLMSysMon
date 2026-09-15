package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;
import dev.felicityiris.llmsysmon.metrics.gpu.GpuMetrics;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryMetrics;

import java.time.Instant;

public class SystemSnapshot {
    private final Instant     timestamp;
    private final CpuMetrics  cpu;
    private final MemoryMetrics memory;
    private final GpuMetrics  gpu;

    public SystemSnapshot(Instant timestamp, CpuMetrics cpu, MemoryMetrics memory, GpuMetrics gpu) {
        this.timestamp = timestamp;
        this.cpu       = cpu;
        this.memory    = memory;
        this.gpu       = gpu;
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

    public GpuMetrics getGpu() {
        return gpu;
    }
}
