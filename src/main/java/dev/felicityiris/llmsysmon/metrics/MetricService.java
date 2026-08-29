package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuCollector;
import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryCollector;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryMetrics;

import java.time.Instant;

public class MetricService {
    private final CpuCollector cpuCollector;
    private final MemoryCollector memoryCollector;

    public MetricService() {
        this.cpuCollector = new CpuCollector();
        this.memoryCollector = new MemoryCollector();
    }

    public SystemSnapshot collectSnapshot() {
        CpuMetrics cpu = cpuCollector.collect();
        MemoryMetrics memory = memoryCollector.collect();

        return new SystemSnapshot(Instant.now(), cpu, memory);
    }
}
