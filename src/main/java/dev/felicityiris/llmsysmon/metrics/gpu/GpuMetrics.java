package dev.felicityiris.llmsysmon.metrics.gpu;

public class GpuMetrics {
    private final boolean available;
    private final String name;
    private final double usage;       // GPU utilization %
    private final long vramUsedMB;    // VRAM used in MB
    private final long vramTotalMB;   // VRAM total in MB

    public GpuMetrics(boolean available, String name, double usage, long vramUsedMB, long vramTotalMB) {
        this.available   = available;
        this.name        = name;
        this.usage       = usage;
        this.vramUsedMB  = vramUsedMB;
        this.vramTotalMB = vramTotalMB;
    }

    /** Returns a sentinel instance used when no GPU data source is reachable. */
    public static GpuMetrics unavailable() {
        return new GpuMetrics(false, "N/A", 0.0, 0L, 0L);
    }

    public boolean isAvailable() {
        return available;
    }

    public String getName() {
        return name;
    }

    public double getUsage() {
        return usage;
    }

    public long getVramUsedMB() {
        return vramUsedMB;
    }

    public long getVramTotalMB() {
        return vramTotalMB;
    }

    /** VRAM utilisation as a percentage (0–100). */
    public double getVramUsage() {
        if (vramTotalMB == 0) {
            return 0.0;
        }
        return (double) vramUsedMB / vramTotalMB * 100.0;
    }
}
