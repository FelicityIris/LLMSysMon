package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuCollector;
import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;
import dev.felicityiris.llmsysmon.metrics.gpu.GpuCollector;
import dev.felicityiris.llmsysmon.metrics.gpu.GpuMetrics;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryCollector;
import dev.felicityiris.llmsysmon.metrics.memory.MemoryMetrics;

import java.time.Instant;

public class MetricService {
    private final CpuCollector    cpuCollector;
    private final MemoryCollector memoryCollector;
    private final GpuCollector    gpuCollector;

    public MetricService() {
        this.cpuCollector    = new CpuCollector();
        this.memoryCollector = new MemoryCollector();
        this.gpuCollector    = new GpuCollector();
    }

    public SystemSnapshot collectSnapshot() {
        CpuMetrics    cpu    = cpuCollector.collect();
        MemoryMetrics memory = memoryCollector.collect();
        GpuMetrics    gpu    = gpuCollector.collect();

        return new SystemSnapshot(Instant.now(), cpu, memory, gpu);
    }
}
