package dev.felicityiris.llmsysmon.ml;

import dev.felicityiris.llmsysmon.database.MetricRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that orchestrates telemetry export, subprocess-based Python inference,
 * and passes the forecast to RecommendationEngine.
 * Designed to never crash or block the UI thread under any ML failure.
 */
public class PredictionService {

    private final MetricRepository metricRepository;
    private final DatasetExporter datasetExporter;
    private final ExecutorService executor;
    private String pythonExecutable;

    public PredictionService(MetricRepository metricRepository) {
        this.metricRepository = metricRepository;
        this.datasetExporter = new DatasetExporter(metricRepository);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ML-Inference-Worker");
            t.setDaemon(true);
            return t;
        });
        this.pythonExecutable = resolvePythonPath();
    }

    /**
     * Resolves the Python executable to use:
     * 1. Project-local virtual environment (.venv/Scripts/python.exe)
     * 2. System PATH ("python")
     */
    private static String resolvePythonPath() {
        Path venvPythonWindows = Paths.get(".venv", "Scripts", "python.exe");
        if (Files.exists(venvPythonWindows)) {
            return venvPythonWindows.toAbsolutePath().toString();
        }

        Path venvPythonUnix = Paths.get(".venv", "bin", "python");
        if (Files.exists(venvPythonUnix)) {
            return venvPythonUnix.toAbsolutePath().toString();
        }

        return "python";
    }

    /**
     * Asynchronously executes telemetry export and ML inference.
     */
    public CompletableFuture<PredictionResult> runInferenceAsync() {
        return CompletableFuture.supplyAsync(this::runInferenceSync, executor);
    }

    /**
     * Synchronously exports recent telemetry and invokes Python inference.
     */
    public PredictionResult runInferenceSync() {
        try {
            Path csvPath = Paths.get("ml", "data", "system_metrics.csv");
            datasetExporter.exportAll(csvPath);

            if (!Files.exists(csvPath) || Files.size(csvPath) == 0) {
                return PredictionResult.unavailable("Awaiting system telemetry collection...");
            }

            // Verify if at least one model checkpoint exists
            Path savedModelsDir = Paths.get("ml", "saved_models");
            if (!Files.exists(savedModelsDir) ||
                    (!Files.exists(savedModelsDir.resolve("gru.pt")) && !Files.exists(savedModelsDir.resolve("lstm.pt")))) {
                return PredictionResult.unavailable("Models not trained yet (run: python -m ml.training.train --model gru)");
            }

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    "-m",
                    "ml.inference.predict",
                    "--json",
                    "--input",
                    csvPath.toString()
            );

            pb.directory(new File("."));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String jsonOutput = output.toString().trim();

            if (exitCode != 0 || !jsonOutput.contains("\"status\": \"success\"")) {
                String errorMsg = extractField(jsonOutput, "\"message\":\\s*\"([^\"]+)\"");
                if (errorMsg.isEmpty()) {
                    errorMsg = "Inference unavailable (exit code: " + exitCode + ")";
                }
                return PredictionResult.unavailable(errorMsg);
            }

            return parseInferenceJson(jsonOutput);

        } catch (Exception e) {
            return PredictionResult.unavailable("Inference service offline: " + e.getMessage());
        }
    }

    /**
     * Lightweight regex-based JSON parser to eliminate external JSON library requirements.
     */
    private PredictionResult parseInferenceJson(String json) {
        String model = extractField(json, "\"model\":\\s*\"([^\"]+)\"");
        if (model.isEmpty()) model = "AI";

        int horizon = 5;
        try {
            String hStr = extractField(json, "\"prediction_horizon_seconds\":\\s*(\\d+)");
            if (!hStr.isEmpty()) horizon = Integer.parseInt(hStr);
        } catch (Exception ignored) {}

        double curCpu = extractDouble(json, "\"current\"[^{]*\\{[^}]*\"cpu\":\\s*([0-9.]+)");
        double curMem = extractDouble(json, "\"current\"[^{]*\\{[^}]*\"memory\":\\s*([0-9.]+)");
        double curSwap = extractDouble(json, "\"current\"[^{]*\\{[^}]*\"swap\":\\s*([0-9.]+)");

        double predCpu = extractDouble(json, "\"predicted\"[^{]*\\{[^}]*\"cpu\":\\s*([0-9.]+)");
        double predMem = extractDouble(json, "\"predicted\"[^{]*\\{[^}]*\"memory\":\\s*([0-9.]+)");
        double predSwap = extractDouble(json, "\"predicted\"[^{]*\\{[^}]*\"swap\":\\s*([0-9.]+)");

        List<RecommendationEngine.Recommendation> recs = RecommendationEngine.generate(
                curCpu, predCpu, curMem, predMem, curSwap, predSwap
        );

        return new PredictionResult(
                true,
                model.toUpperCase(),
                horizon,
                curCpu, curMem, curSwap,
                predCpu, predMem, predSwap,
                "Model: " + model.toUpperCase() + " (Active)",
                recs
        );
    }

    private static String extractField(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static double extractDouble(String text, String regex) {
        String val = extractField(text, regex);
        if (val.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
