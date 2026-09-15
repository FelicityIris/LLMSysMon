package dev.felicityiris.llmsysmon.ml;

import java.time.Instant;
import java.util.List;

/**
 * Encapsulates the output of a machine learning inference execution,
 * including 5-second future forecasts for CPU, Memory, Swap, and GPU,
 * as well as explicit temporal alignment timestamps.
 */
public record PredictionResult(
        boolean available,
        String modelName,
        int predictionHorizonSeconds,
        Instant predictionTimestamp,
        Instant targetTimestamp,
        double currentCpu,
        double currentMemory,
        double currentSwap,
        double currentGpu,
        double predictedCpu,
        double predictedMemory,
        double predictedSwap,
        double predictedGpu,
        String statusMessage,
        List<RecommendationEngine.Recommendation> recommendations
) {
    public static PredictionResult unavailable(String reason) {
        Instant now = Instant.now();
        return unavailable(reason, now);
    }

    public static PredictionResult unavailable(String reason, Instant now) {
        return new PredictionResult(
                false,
                "N/A",
                5,
                now,
                now.plusSeconds(5),
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                reason,
                List.of(new RecommendationEngine.Recommendation(
                        "AI Optimization Standby",
                        reason,
                        RecommendationEngine.Severity.INFO
                ))
        );
    }
}
