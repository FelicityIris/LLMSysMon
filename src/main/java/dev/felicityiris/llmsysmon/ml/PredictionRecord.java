package dev.felicityiris.llmsysmon.ml;

import java.time.Instant;

/**
 * Historical record of a generated prediction aligned to its future target timestamp.
 * Stored in-memory to plot the predicted series on the real-time charts alongside actual telemetry.
 */
public record PredictionRecord(
        Instant predictionTimestamp,
        Instant targetTimestamp,
        double predictedCpu,
        double predictedMemory,
        double predictedSwap,
        double predictedGpu,
        String modelName
) {
    public static PredictionRecord from(PredictionResult result) {
        return new PredictionRecord(
                result.predictionTimestamp(),
                result.targetTimestamp(),
                result.predictedCpu(),
                result.predictedMemory(),
                result.predictedSwap(),
                result.predictedGpu(),
                result.modelName()
        );
    }
}
