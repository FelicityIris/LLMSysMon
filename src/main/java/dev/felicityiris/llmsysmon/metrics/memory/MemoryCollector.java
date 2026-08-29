package dev.felicityiris.llmsysmon.metrics.memory;

import dev.felicityiris.llmsysmon.metrics.MetricCollector;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;

public class MemoryCollector implements MetricCollector<MemoryMetrics> {
    private final GlobalMemory memory;

    public MemoryCollector() {
        SystemInfo systemInfo = new SystemInfo();
        this.memory = systemInfo.getHardware().getMemory();
    }

    @Override
    public MemoryMetrics collect() {
        long totalBytes = memory.getTotal();
        long availableBytes = memory.getAvailable();
        long usedBytes = totalBytes - availableBytes;

        long totalSwapBytes = memory.getVirtualMemory().getSwapTotal();
        long usedSwapBytes = memory.getVirtualMemory().getSwapUsed();

        return new MemoryMetrics(totalBytes, availableBytes, usedBytes, totalSwapBytes, usedSwapBytes);
    }
}
