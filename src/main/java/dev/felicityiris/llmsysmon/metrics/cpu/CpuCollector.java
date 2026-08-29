package dev.felicityiris.llmsysmon.metrics.cpu;

import dev.felicityiris.llmsysmon.metrics.MetricCollector;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

public class CpuCollector implements MetricCollector<CpuMetrics> {
    private final CentralProcessor processor;
    private long[] previousTicks;

    public CpuCollector() {
        SystemInfo systemInfo = new SystemInfo();
        this.processor = systemInfo.getHardware().getProcessor();
        this.previousTicks = processor.getSystemCpuLoadTicks();
    }

    @Override
    public CpuMetrics collect() {
        long[] currentTicks = processor.getSystemCpuLoadTicks();
        double usage = processor.getSystemCpuLoadBetweenTicks(previousTicks) * 100.0;
        previousTicks = currentTicks;

        long currentFrequency = processor.getProcessorIdentifier().getVendorFreq();
        long maxFrequency = processor.getMaxFreq();

        return new CpuMetrics(usage, currentFrequency, maxFrequency, processor.getLogicalProcessorCount(), processor.getPhysicalProcessorCount());
    }
}
