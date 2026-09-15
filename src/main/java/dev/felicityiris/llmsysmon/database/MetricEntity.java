package dev.felicityiris.llmsysmon.database;

import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.metrics.gpu.GpuMetrics;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "system_metrics")
public class MetricEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ── Timestamp ──────────────────────────────────────────────── */

    @Column(nullable = false)
    private long timestamp;

    /* ── CPU ────────────────────────────────────────────────────── */

    @Column(name = "cpu_usage", nullable = false)
    private double cpuUsage;

    @Column(name = "cpu_current_frequency", nullable = false)
    private long cpuCurrentFrequency;

    @Column(name = "cpu_max_frequency", nullable = false)
    private long cpuMaxFrequency;

    @Column(name = "logical_processors", nullable = false)
    private int logicalProcessors;

    @Column(name = "physical_processors", nullable = false)
    private int physicalProcessors;

    /* ── Memory ─────────────────────────────────────────────────── */

    @Column(name = "memory_total", nullable = false)
    private long memoryTotal;

    @Column(name = "memory_available", nullable = false)
    private long memoryAvailable;

    @Column(name = "memory_used", nullable = false)
    private long memoryUsed;

    @Column(name = "memory_usage", nullable = false)
    private double memoryUsage;

    @Column(name = "swap_total", nullable = false)
    private long swapTotal;

    @Column(name = "swap_used", nullable = false)
    private long swapUsed;

    @Column(name = "swap_usage", nullable = false)
    private double swapUsage;

    /* ── GPU (nullable — may be absent on non-NVIDIA systems) ───── */

    @Column(name = "gpu_usage")
    private Double gpuUsage;

    @Column(name = "vram_used_mb")
    private Long vramUsedMb;

    @Column(name = "vram_total_mb")
    private Long vramTotalMb;

    protected MetricEntity() {}

    // ── Factory ──────────────────────────────────────────────────

    public static MetricEntity from(SystemSnapshot snapshot) {
        MetricEntity entity = new MetricEntity();

        entity.timestamp = snapshot.getTimestamp().toEpochMilli();

        entity.cpuUsage              = snapshot.getCpu().getUsage();
        entity.cpuCurrentFrequency   = snapshot.getCpu().getCurrentFrequency();
        entity.cpuMaxFrequency       = snapshot.getCpu().getMaxFrequency();
        entity.logicalProcessors     = snapshot.getCpu().getLogicalProcessorCount();
        entity.physicalProcessors    = snapshot.getCpu().getPhysicalProcessorCount();

        entity.memoryTotal     = snapshot.getMemory().getTotalBytes();
        entity.memoryAvailable = snapshot.getMemory().getAvailableBytes();
        entity.memoryUsed      = snapshot.getMemory().getUsedBytes();
        entity.memoryUsage     = snapshot.getMemory().getUsage();

        entity.swapTotal = snapshot.getMemory().getTotalSwapBytes();
        entity.swapUsed  = snapshot.getMemory().getUsedSwapBytes();
        entity.swapUsage = snapshot.getMemory().getSwapUsage();

        if (snapshot.getGpu().isAvailable()) {
            entity.gpuUsage    = snapshot.getGpu().getUsage();
            entity.vramUsedMb  = snapshot.getGpu().getVramUsedMB();
            entity.vramTotalMb = snapshot.getGpu().getVramTotalMB();
        }

        return entity;
    }

    public SystemSnapshot toSnapshot() {
        GpuMetrics gpu = (gpuUsage != null)
                ? new GpuMetrics(true, "GPU", gpuUsage,
                        vramUsedMb  != null ? vramUsedMb  : 0L,
                        vramTotalMb != null ? vramTotalMb : 0L)
                : GpuMetrics.unavailable();

        return new SystemSnapshot(
                Instant.ofEpochMilli(timestamp),
                new dev.felicityiris.llmsysmon.metrics.cpu.CpuMetrics(
                        cpuUsage, cpuCurrentFrequency, cpuMaxFrequency,
                        logicalProcessors, physicalProcessors
                ),
                new dev.felicityiris.llmsysmon.metrics.memory.MemoryMetrics(
                        memoryTotal, memoryAvailable, memoryUsed, swapTotal, swapUsed
                ),
                gpu
        );
    }

    // ── Getters ──────────────────────────────────────────────────

    public Long getID()             { return id; }
    public long getTimestamp()      { return timestamp; }
    public double getCpuUsage()     { return cpuUsage; }
    public double getMemoryUsage()  { return memoryUsage; }
    public double getSwapUsage()    { return swapUsage; }
    public Double getGpuUsage()     { return gpuUsage; }
}
