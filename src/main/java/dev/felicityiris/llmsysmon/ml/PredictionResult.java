package dev.felicityiris.llmsysmon.ml;

import java.util.List;

/**
 * Encapsulates the output of a machine learning inference execution.
 */
public record PredictionResult(
        boolean available,
        String modelName,
        int predictionHorizonSeconds,
        double currentCpu,
        double currentMemory,
        double currentSwap,
        double predictedCpu,
        double predictedMemory,
        double predictedSwap,
        String statusMessage,
        List<RecommendationEngine.Recommendation> recommendations
) {
    public static PredictionResult unavailable(String reason) {
        return new PredictionResult(
                false,
                "N/A",
                5,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                reason,
                List.of(new RecommendationEngine.Recommendation(
                        "AI Optimization Standby",
                        reason,
                        RecommendationEngine.Severity.INFO
                ))
        );
    }
}
