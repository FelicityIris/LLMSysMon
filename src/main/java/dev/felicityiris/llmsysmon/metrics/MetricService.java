package dev.felicityiris.llmsysmon.metrics;

import dev.felicityiris.llmsysmon.metrics.cpu.CpuCollector;
import dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics;

import java.time.Instant;

public class MetricService {
    private final CpuCollector cpuCollector;

    public MetricService() {
        this.cpuCollector = new CpuCollector();
    }

    public SystemSnapshot collectSnapshot() {
        CpuMetrics cpu = cpuCollector.collect();

        return new SystemSnapshot(Instant.now(), cpu);
    }
}
