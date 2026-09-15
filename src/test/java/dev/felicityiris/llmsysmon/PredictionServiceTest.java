package dev.felicityiris.llmsysmon;

import dev.felicityiris.llmsysmon.database.DatabaseConfig;
import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.ml.PredictionResult;
import dev.felicityiris.llmsysmon.ml.PredictionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PredictionServiceTest {

    @Test
    public void testInferenceExecution() {
        DatabaseConfig dbConfig = new DatabaseConfig();
        MetricRepository repo = new MetricRepository(dbConfig.getEntityManagerFactory());
        PredictionService service = new PredictionService(repo);

        PredictionResult result = service.runInferenceSync();
        System.out.println("Result available: " + result.available());
        System.out.println("Model: " + result.modelName());
        System.out.println("Status Message: " + result.statusMessage());
        System.out.println("Current CPU: " + result.currentCpu() + "% | Pred CPU: " + result.predictedCpu() + "%");
        System.out.println("Current RAM: " + result.currentMemory() + "% | Pred RAM: " + result.predictedMemory() + "%");
        System.out.println("Current Swap: " + result.currentSwap() + "% | Pred Swap: " + result.predictedSwap() + "%");
        System.out.println("Current GPU: " + result.currentGpu() + "% | Pred GPU: " + result.predictedGpu() + "%");
        System.out.println("Prediction Time: " + result.predictionTimestamp() + " | Target Time: " + result.targetTimestamp());

        assertTrue(result.available(), "Prediction should be available: " + result.statusMessage());
        assertNotNull(result.modelName());
        assertNotNull(result.predictionTimestamp());
        assertNotNull(result.targetTimestamp());
        assertTrue(result.predictedGpu() >= 0.0 && result.predictedGpu() <= 100.0);
        assertFalse(result.recommendations().isEmpty());

        dbConfig.close();
    }
}
