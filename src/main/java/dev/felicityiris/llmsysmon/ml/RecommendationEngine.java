package dev.felicityiris.llmsysmon.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * Heuristic recommendation engine that evaluates current and predicted resource metrics
 * (CPU, Memory, Swap, and GPU) to produce human-readable, non-destructive system optimization advice.
 */
public class RecommendationEngine {

    // Conservative percentage thresholds
    private static final double CPU_CRITICAL_THRESHOLD = 85.0;
    private static final double CPU_HIGH_THRESHOLD     = 70.0;
    private static final double CPU_SPIKE_DELTA        = 25.0;

    private static final double MEMORY_CRITICAL_THRESHOLD = 90.0;
    private static final double MEMORY_HIGH_THRESHOLD     = 80.0;
    private static final double MEMORY_INCREASE_DELTA     = 15.0;

    private static final double SWAP_ACTIVE_THRESHOLD   = 10.0;
    private static final double SWAP_INCREASE_DELTA     = 5.0;

    private static final double GPU_CRITICAL_THRESHOLD = 85.0;
    private static final double GPU_HIGH_THRESHOLD     = 70.0;
    private static final double GPU_SPIKE_DELTA        = 25.0;

    public record Recommendation(String summary, String detail, Severity severity) {}

    public enum Severity {
        OPTIMAL,
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Backward-compatible overload without GPU.
     */
    public static List<Recommendation> generate(
            double currentCpu, double predictedCpu,
            double currentMemory, double predictedMemory,
            double currentSwap, double predictedSwap
    ) {
        return generate(currentCpu, predictedCpu, currentMemory, predictedMemory, currentSwap, predictedSwap, 0.0, 0.0);
    }

    /**
     * Generates recommendations based on current and ML-predicted metrics (including GPU).
     */
    public static List<Recommendation> generate(
            double currentCpu, double predictedCpu,
            double currentMemory, double predictedMemory,
            double currentSwap, double predictedSwap,
            double currentGpu, double predictedGpu
    ) {
        List<Recommendation> recommendations = new ArrayList<>();

        // 1. CPU Analysis
        if (predictedCpu >= CPU_CRITICAL_THRESHOLD) {
            recommendations.add(new Recommendation(
                    "Critical CPU Demand Forecasted",
                    String.format("CPU load is predicted to hit %.1f%%. Consider pausing background batch tasks or compiling jobs.", predictedCpu),
                    Severity.CRITICAL
            ));
        } else if (predictedCpu >= CPU_HIGH_THRESHOLD || (predictedCpu - currentCpu >= CPU_SPIKE_DELTA)) {
            recommendations.add(new Recommendation(
                    "High CPU Utilization Expected",
                    String.format("CPU utilization is rising (current: %.1f%% → predicted: %.1f%%). Verify CPU-intensive workloads.", currentCpu, predictedCpu),
                    Severity.WARNING
            ));
        }

        // 2. Memory Analysis
        if (predictedMemory >= MEMORY_CRITICAL_THRESHOLD) {
            recommendations.add(new Recommendation(
                    "Severe Memory Pressure Approaching",
                    String.format("RAM usage is projected at %.1f%%. Consider closing unused high-memory applications to avoid paging.", predictedMemory),
                    Severity.CRITICAL
            ));
        } else if (predictedMemory >= MEMORY_HIGH_THRESHOLD || (predictedMemory - currentMemory >= MEMORY_INCREASE_DELTA)) {
            recommendations.add(new Recommendation(
                    "Memory Pressure Increasing",
                    String.format("Memory consumption is projected to rise to %.1f%%. Monitor active processes.", predictedMemory),
                    Severity.WARNING
            ));
        }

        // 3. Swap / Thrashing Analysis
        if (predictedSwap >= SWAP_ACTIVE_THRESHOLD && (predictedSwap - currentSwap >= SWAP_INCREASE_DELTA)) {
            recommendations.add(new Recommendation(
                    "Swap Influx Detected (Paging Risk)",
                    String.format("Swap usage is predicted to grow by %.1f%%. Virtual memory paging may degrade I/O responsiveness.", (predictedSwap - currentSwap)),
                    Severity.WARNING
            ));
        } else if (predictedSwap >= 25.0) {
            recommendations.add(new Recommendation(
                    "Heavy Swap Activity",
                    String.format("Swap is active at %.1f%%. Memory thrashing is likely if additional applications are launched.", predictedSwap),
                    Severity.WARNING
            ));
        }

        // 4. GPU Analysis
        if (predictedGpu >= GPU_CRITICAL_THRESHOLD) {
            recommendations.add(new Recommendation(
                    "Critical GPU Demand Forecasted",
                    String.format("GPU load is predicted to reach %.1f%%. Consider reducing rendering load or GPU compute batches.", predictedGpu),
                    Severity.CRITICAL
            ));
        } else if (predictedGpu >= GPU_HIGH_THRESHOLD || (predictedGpu - currentGpu >= GPU_SPIKE_DELTA)) {
            recommendations.add(new Recommendation(
                    "Elevated GPU Activity Expected",
                    String.format("GPU utilization is rising (current: %.1f%% → predicted: %.1f%%).", currentGpu, predictedGpu),
                    Severity.WARNING
            ));
        }

        // 5. Default Optimal state
        if (recommendations.isEmpty()) {
            recommendations.add(new Recommendation(
                    "System Resources Healthy",
                    String.format("Resource levels are stable (CPU: %.1f%%, RAM: %.1f%%, GPU: %.1f%%). No performance bottlenecks anticipated.",
                            predictedCpu, predictedMemory, predictedGpu),
                    Severity.OPTIMAL
            ));
        }

        return recommendations;
    }
}
