package dev.felicityiris.llmsysmon.metrics.cpu;

public class CpuMetrics {
    private final double usage;
    private final long currentFrequency;
    private final long maxFrequency;
    private final int logicalProcessorCount;
    private final int physicalProcessorCount;

    public CpuMetrics(double usage, long currentFrequency, long maxFrequency, int logicalProcessorCount, int physicalProcessorCount) {
        this.usage = usage;
        this.currentFrequency = currentFrequency;
        this.maxFrequency = maxFrequency;
        this.logicalProcessorCount = logicalProcessorCount;
        this.physicalProcessorCount = physicalProcessorCount;
    }

    public double getUsage() {
        return usage;
    }

    public long getCurrentFrequency() {
        return currentFrequency;
    }

    public long getMaxFrequency() {
        return maxFrequency;
    }

    public int getLogicalProcessorCount() {
        return logicalProcessorCount;
    }

    public int getPhysicalProcessorCount() {
        return physicalProcessorCount;
    }
}
