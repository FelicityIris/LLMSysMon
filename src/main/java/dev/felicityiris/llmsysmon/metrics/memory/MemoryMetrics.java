package dev.felicityiris.llmsysmon.metrics.memory;

public class MemoryMetrics {
    private final long totalBytes;
    private final long availableBytes;
    private final long usedBytes;

    private final long totalSwapBytes;
    private final long usedSwapBytes;

    public MemoryMetrics(long totalBytes, long availableBytes, long usedBytes, long totalSwapBytes, long usedSwapBytes) {
        this.totalBytes = totalBytes;
        this.availableBytes = availableBytes;
        this.usedBytes = usedBytes;
        this.totalSwapBytes = totalSwapBytes;
        this.usedSwapBytes = usedSwapBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public double getUsage() {
        if (totalBytes == 0) {
            return 0.0;
        }

        return (double) usedBytes / totalBytes * 100.0;
    }

    public long getTotalSwapBytes() {
        return totalSwapBytes;
    }

    public long getUsedSwapBytes() {
        return usedSwapBytes;
    }

    public double getSwapUsage() {
        if (totalSwapBytes == 0) {
            return 0.0;
        }

        return (double) usedSwapBytes / totalSwapBytes * 100.0;
    }
}
