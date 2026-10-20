package dev.felicityiris.llmsysmon.ui;

import dev.felicityiris.llmsysmon.database.DatabaseConfig;
import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.ml.DatasetExporter;
import dev.felicityiris.llmsysmon.ml.LlmConfig;
import dev.felicityiris.llmsysmon.ml.LlmRecommendation;
import dev.felicityiris.llmsysmon.ml.LocalLlmRecommendationService;
import dev.felicityiris.llmsysmon.ml.PredictionRecord;
import dev.felicityiris.llmsysmon.ml.PredictionResult;
import dev.felicityiris.llmsysmon.ml.PredictionService;
import dev.felicityiris.llmsysmon.ml.RecommendationEngine;
import dev.felicityiris.llmsysmon.scheduler.MetricScheduler;
import dev.felicityiris.llmsysmon.scheduler.SchedulerConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class DashboardController {
    private static final int LIVE_HISTORY_SIZE = 60;
    private static final int MAX_PREDICTION_HISTORY = 120;

    // ── Stat card labels (MD3 cards) ─────────────────────────────
    @FXML private Label cpuValue;
    @FXML private Label cpuSubValue;      // "3.60 GHz  ·  16 cores"

    @FXML private Label memoryValue;
    @FXML private Label memorySubValue;   // "11.2 / 31.8 GB"

    @FXML private Label swapValue;
    @FXML private Label swapSubValue;     // "0.5 / 4.0 GB" | "No swap configured"

    @FXML private Label gpuValue;
    @FXML private Label gpuSubValue;      // "0.5 / 4.0 GB VRAM" | "N/A"

    // ── AI Prediction Card labels ────────────────────────────────
    @FXML private Label predictionModelBadge;
    @FXML private Label predictionHorizonLabel;
    @FXML private Label predictedCpuValue;
    @FXML private Label currentCpuCompareLabel;
    @FXML private Label predictedMemoryValue;
    @FXML private Label currentMemoryCompareLabel;
    @FXML private Label predictedSwapValue;
    @FXML private Label currentSwapCompareLabel;
    @FXML private Label predictedGpuValue;
    @FXML private Label currentGpuCompareLabel;
    @FXML private Label recommendationSummary;
    @FXML private Label recommendationDetail;
    @FXML private Label predictionAccuracyLabel;
    @FXML private Label llmStatusLabel;
    @FXML private Label llmLastUpdatedLabel;

    // ── Charts ───────────────────────────────────────────────────
    @FXML private LineChart<Number, Number> cpuChart;
    @FXML private LineChart<Number, Number> memoryChart;
    @FXML private LineChart<Number, Number> swapChart;
    @FXML private LineChart<Number, Number> gpuChart;

    // ── Time range selector ──────────────────────────────────────
    @FXML private ComboBox<String> timeRangeSelector;

    // ── State ────────────────────────────────────────────────────
    private final Deque<SystemSnapshot> liveHistory = new ArrayDeque<>();
    private final Deque<PredictionRecord> predictionHistory = new ArrayDeque<>();

    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> cpuPredSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> memoryPredSeries;
    private XYChart.Series<Number, Number> swapSeries;
    private XYChart.Series<Number, Number> swapPredSeries;
    private XYChart.Series<Number, Number> gpuSeries;
    private XYChart.Series<Number, Number> gpuPredSeries;

    private MetricScheduler                   scheduler;
    private DatabaseConfig                    databaseConfig;
    private MetricRepository                  metricRepository;
    private PredictionService                 predictionService;
    private LocalLlmRecommendationService     llmService;

    /** Tracks the most recent prediction so we can pass it to the LLM service on each tick. */
    private volatile PredictionResult         latestPredictionResult;

    // ── Lifecycle ─────────────────────────────────────────────────

    public void initialize() {
        setupCharts();

        timeRangeSelector.getItems().addAll("1 minute", "5 minutes", "30 minutes", "1 hour");
        timeRangeSelector.setValue("1 minute");
        timeRangeSelector.setOnAction(event -> loadHistoricalData());

        MetricService metricService = new MetricService();

        databaseConfig   = new DatabaseConfig();
        metricRepository = new MetricRepository(databaseConfig.getEntityManagerFactory());

        // Initialize ML Prediction & Recommendation service
        predictionService = new PredictionService(metricRepository);

        // Initialize local LLM recommendation service (Ollama, falls back to rule-engine)
        llmService = new LocalLlmRecommendationService(LlmConfig.defaults());
        llmService.setRecommendationListener(rec ->
                Platform.runLater(() -> updateLlmRecommendationUI(rec)));
        llmService.start();

        scheduler = new MetricScheduler(metricService, new SchedulerConfig(1000), this::handleSnapshot);
        scheduler.start();

        // Trigger initial forecast check asynchronously
        triggerPredictionAsync();
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (llmService != null) {
            llmService.shutdown();
        }
        if (predictionService != null) {
            predictionService.shutdown();
        }
        if (databaseConfig != null) {
            databaseConfig.close();
        }
    }

    // ── Chart setup ───────────────────────────────────────────────

    private void setupCharts() {
        cpuSeries     = new XYChart.Series<>();
        cpuSeries.setName("Actual");
        cpuPredSeries = new XYChart.Series<>();
        cpuPredSeries.setName("Predicted");

        memorySeries     = new XYChart.Series<>();
        memorySeries.setName("Actual");
        memoryPredSeries = new XYChart.Series<>();
        memoryPredSeries.setName("Predicted");

        swapSeries     = new XYChart.Series<>();
        swapSeries.setName("Actual");
        swapPredSeries = new XYChart.Series<>();
        swapPredSeries.setName("Predicted");

        gpuSeries     = new XYChart.Series<>();
        gpuSeries.setName("Actual");
        gpuPredSeries = new XYChart.Series<>();
        gpuPredSeries.setName("Predicted");

        cpuChart.getData().addAll(cpuSeries, cpuPredSeries);
        memoryChart.getData().addAll(memorySeries, memoryPredSeries);
        swapChart.getData().addAll(swapSeries, swapPredSeries);
        gpuChart.getData().addAll(gpuSeries, gpuPredSeries);

        configureChart(cpuChart);
        configureChart(memoryChart);
        configureChart(swapChart);
        configureChart(gpuChart);
    }

    private void configureChart(LineChart<Number, Number> chart) {
        NumberAxis yAxis = (NumberAxis) chart.getYAxis();

        yAxis.setAutoRanging(false);
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(100);
        yAxis.setTickUnit(20);

        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
    }

    // ── Snapshot handling ─────────────────────────────────────────

    private void handleSnapshot(SystemSnapshot snapshot) {
        metricRepository.save(snapshot);

        // Derive display values
        double cpuUsage    = snapshot.getCpu().getUsage();
        double memoryUsage = snapshot.getMemory().getUsage();
        double swapUsage   = snapshot.getMemory().getSwapUsage();
        double gpuUsage    = snapshot.getGpu().getUsage();

        double freqGHz   = snapshot.getCpu().getCurrentFrequency() / 1_000_000_000.0;
        int logicalCores = snapshot.getCpu().getLogicalProcessorCount();

        double usedMemGB  = snapshot.getMemory().getUsedBytes()  / 1_073_741_824.0;
        double totalMemGB = snapshot.getMemory().getTotalBytes() / 1_073_741_824.0;

        double totalSwapGB = snapshot.getMemory().getTotalSwapBytes() / 1_073_741_824.0;
        double usedSwapGB  = snapshot.getMemory().getUsedSwapBytes()  / 1_073_741_824.0;
        String swapSub = (totalSwapGB == 0.0)
                ? "No swap configured"
                : String.format("%.1f / %.1f GB", usedSwapGB, totalSwapGB);

        boolean gpuAvailable = snapshot.getGpu().isAvailable();
        long    vramUsedMB   = snapshot.getGpu().getVramUsedMB();
        long    vramTotalMB  = snapshot.getGpu().getVramTotalMB();
        String gpuSub = gpuAvailable
                ? String.format("%.1f / %.1f GB VRAM", vramUsedMB / 1024.0, vramTotalMB / 1024.0)
                : "N/A";

        Platform.runLater(() -> {
            // Stat card values
            cpuValue.setText(String.format("%.1f%%", cpuUsage));
            cpuSubValue.setText(String.format("%.2f GHz  ·  %d cores", freqGHz, logicalCores));

            memoryValue.setText(String.format("%.1f%%", memoryUsage));
            memorySubValue.setText(String.format("%.1f / %.1f GB", usedMemGB, totalMemGB));

            swapValue.setText(String.format("%.1f%%", swapUsage));
            swapSubValue.setText(swapSub);

            gpuValue.setText(gpuAvailable ? String.format("%.1f%%", gpuUsage) : "N/A");
            gpuSubValue.setText(gpuSub);

            // Update live accuracy comparison against past prediction targeting current time
            updateLiveAccuracy(snapshot);

            // Update charts via historical DB query
            addToLiveHistory(snapshot);
            updateLiveCharts();

            // Refresh the recommendation age indicator smoothly on every tick
            refreshRecommendationTimestamp();
        });

        // Trigger ML inference on every 1-second tick (asynchronous, guarded by AtomicBoolean)
        triggerPredictionAsync();

        // Submit snapshot to LLM service (non-blocking — queues for next refresh window)
        if (llmService != null) {
            llmService.submitForRecommendation(snapshot, latestPredictionResult);
        }
    }

    // ── Machine Learning Inference & Recommendations ─────────────

    private void triggerPredictionAsync() {
        if (predictionService != null) {
            predictionService.runInferenceAsync().thenAccept(result -> {
                if (result != null) {
                    Platform.runLater(() -> updatePredictionUI(result));
                }
            });
        }
    }

    private void updatePredictionUI(PredictionResult result) {
        // Cache so llmService can access it on every 1-second tick
        latestPredictionResult = result;

        if (result.available()) {
            predictionModelBadge.setText(result.modelName() + " (Active)");
            predictionHorizonLabel.setText(String.format("Horizon: ~%ds ahead", result.predictionHorizonSeconds()));

            predictedCpuValue.setText(String.format("%.1f%%", result.predictedCpu()));
            currentCpuCompareLabel.setText(String.format("(cur: %.1f%%)", result.currentCpu()));

            predictedMemoryValue.setText(String.format("%.1f%%", result.predictedMemory()));
            currentMemoryCompareLabel.setText(String.format("(cur: %.1f%%)", result.currentMemory()));

            predictedSwapValue.setText(String.format("%.1f%%", result.predictedSwap()));
            currentSwapCompareLabel.setText(String.format("(cur: %.1f%%)", result.currentSwap()));

            if (predictedGpuValue != null) {
                predictedGpuValue.setText(String.format("%.1f%%", result.predictedGpu()));
            }
            if (currentGpuCompareLabel != null) {
                currentGpuCompareLabel.setText(String.format("(cur: %.1f%%)", result.currentGpu()));
            }

            // Record in prediction history for chart alignment and accuracy verification
            predictionHistory.addLast(PredictionRecord.from(result));
            while (predictionHistory.size() > MAX_PREDICTION_HISTORY) {
                predictionHistory.removeFirst();
            }
        } else {
            predictionModelBadge.setText("STANDBY");
            predictedCpuValue.setText("--");
            currentCpuCompareLabel.setText("");
            predictedMemoryValue.setText("--");
            currentMemoryCompareLabel.setText("");
            predictedSwapValue.setText("--");
            currentSwapCompareLabel.setText("");
            if (predictedGpuValue != null) {
                predictedGpuValue.setText("--");
            }
            if (currentGpuCompareLabel != null) {
                currentGpuCompareLabel.setText("");
            }

            // Only update recommendation text if LLM hasn't taken over yet
            if (llmService == null) {
                recommendationSummary.setText("AI Prediction Standby");
                recommendationDetail.setText(result.statusMessage());
            }
        }
    }

    /**
     * Updates the recommendation card with the latest LLM (or fallback) recommendation.
     * Must be called on the JavaFX Application Thread.
     */
    private void updateLlmRecommendationUI(LlmRecommendation rec) {
        RecommendationEngine.Recommendation engineRec = rec.toEngineRecommendation();
        if (recommendationSummary != null && !engineRec.summary().equals(recommendationSummary.getText())) {
            recommendationSummary.setText(engineRec.summary());
        }
        if (recommendationDetail != null && !engineRec.detail().equals(recommendationDetail.getText())) {
            recommendationDetail.setText(engineRec.detail());
        }

        if (llmStatusLabel != null) {
            String statusText = switch (rec.getSource()) {
                case LLM      -> "🤖 LLM Active (" + rec.getSeverity().name() + ")";
                case FALLBACK -> "📋 Rules Fallback";
                case PENDING  -> "⏳ LLM: Initializing...";
            };
            if (!statusText.equals(llmStatusLabel.getText())) {
                llmStatusLabel.setText(statusText);
            }
        }

        refreshRecommendationTimestamp();
    }

    private void refreshRecommendationTimestamp() {
        if (llmLastUpdatedLabel != null && llmService != null) {
            LlmRecommendation rec = llmService.getLatestRecommendation();
            if (rec != null && rec.getSource() != LlmRecommendation.Source.PENDING) {
                long secondsAgo = Duration.between(rec.getGeneratedAt(), Instant.now()).toSeconds();
                String ago = secondsAgo <= 5 ? "just now"
                        : secondsAgo < 60    ? secondsAgo + "s ago"
                        : (secondsAgo / 60)  + "m ago";
                String text = "Updated " + ago;
                if (!text.equals(llmLastUpdatedLabel.getText())) {
                    llmLastUpdatedLabel.setText(text);
                }
            }
        }
    }

    private void updateLiveAccuracy(SystemSnapshot currentSnapshot) {
        if (predictionAccuracyLabel == null || predictionHistory.isEmpty()) {
            return;
        }
        Instant now = currentSnapshot.getTimestamp();
        PredictionRecord matched = null;
        long minDiffMillis = Long.MAX_VALUE;

        for (PredictionRecord record : predictionHistory) {
            if (record.targetTimestamp() != null) {
                long diff = Math.abs(Duration.between(record.targetTimestamp(), now).toMillis());
                if (diff < minDiffMillis && diff <= 2000) { // within 2 seconds
                    minDiffMillis = diff;
                    matched = record;
                }
            }
        }

        if (matched != null) {
            double cpuErr = Math.abs(currentSnapshot.getCpu().getUsage() - matched.predictedCpu());
            double memErr = Math.abs(currentSnapshot.getMemory().getUsage() - matched.predictedMemory());
            double gpuErr = Math.abs(currentSnapshot.getGpu().getUsage() - matched.predictedGpu());

            predictionAccuracyLabel.setText(String.format(
                    "Live Tracking Error (5s Horizon): CPU ±%.1f%% | RAM ±%.1f%% | GPU ±%.1f%%",
                    cpuErr, memErr, gpuErr
            ));
        }
    }

    private void addToLiveHistory(SystemSnapshot snapshot) {
        if (liveHistory.size() >= LIVE_HISTORY_SIZE) {
            liveHistory.removeFirst();
        }
        liveHistory.addLast(snapshot);
    }

    private void updateLiveCharts() {
        loadHistoricalData();
    }

    // ── Historical data loading ───────────────────────────────────

    private void loadHistoricalData() {
        Duration duration = getSelectedDuration();

        Instant end   = Instant.now();
        Instant start = end.minus(duration);

        List<SystemSnapshot> snapshots = metricRepository.findBetween(start, end);

        cpuSeries.getData().clear();
        cpuPredSeries.getData().clear();
        memorySeries.getData().clear();
        memoryPredSeries.getData().clear();
        swapSeries.getData().clear();
        swapPredSeries.getData().clear();
        gpuSeries.getData().clear();
        gpuPredSeries.getData().clear();

        configureHistoricalXAxis(cpuChart,    duration.getSeconds());
        configureHistoricalXAxis(memoryChart, duration.getSeconds());
        configureHistoricalXAxis(swapChart,   duration.getSeconds());
        configureHistoricalXAxis(gpuChart,    duration.getSeconds());

        for (SystemSnapshot snapshot : snapshots) {
            double secondsAgo = Duration.between(snapshot.getTimestamp(), end).toMillis() / -1000.0;

            cpuSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getCpu().getUsage()));
            memorySeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getUsage()));
            swapSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getSwapUsage()));

            if (snapshot.getGpu().isAvailable()) {
                gpuSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getGpu().getUsage()));
            }
        }

        // Plot past predictions aligned to their target timestamp
        double windowSeconds = duration.getSeconds();
        for (PredictionRecord pred : predictionHistory) {
            if (pred.targetTimestamp() != null) {
                double predSecondsAgo = Duration.between(pred.targetTimestamp(), end).toMillis() / -1000.0;
                if (predSecondsAgo >= -windowSeconds && predSecondsAgo <= 0.0) {
                    cpuPredSeries.getData().add(new XYChart.Data<>(predSecondsAgo, pred.predictedCpu()));
                    memoryPredSeries.getData().add(new XYChart.Data<>(predSecondsAgo, pred.predictedMemory()));
                    swapPredSeries.getData().add(new XYChart.Data<>(predSecondsAgo, pred.predictedSwap()));
                    gpuPredSeries.getData().add(new XYChart.Data<>(predSecondsAgo, pred.predictedGpu()));
                }
            }
        }
    }

    private Duration getSelectedDuration() {
        return switch (timeRangeSelector.getValue()) {
            case "5 minutes"  -> Duration.ofMinutes(5);
            case "30 minutes" -> Duration.ofMinutes(30);
            case "1 hour"     -> Duration.ofHours(1);
            default           -> Duration.ofMinutes(1);
        };
    }

    private void configureHistoricalXAxis(LineChart<Number, Number> chart, double durationSeconds) {
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(-durationSeconds);
        xAxis.setUpperBound(0);
        xAxis.setTickUnit(getTickUnit(durationSeconds));
    }

    private double getTickUnit(double durationSeconds) {
        if (durationSeconds <= 60)   return 10;
        if (durationSeconds <= 300)  return 60;
        if (durationSeconds <= 1800) return 300;
        return 600;
    }

    private void setChartsAnimated(boolean animated) {
        cpuChart.setAnimated(animated);
        memoryChart.setAnimated(animated);
        swapChart.setAnimated(animated);
        gpuChart.setAnimated(animated);
    }

    // ── Dataset export ───────────────────────────────────────────

    public void exportDataset() {
        DatasetExporter exporter = new DatasetExporter(metricRepository);
        try {
            exporter.exportAll(Paths.get("ml/data/system_metrics.csv"));
        } catch (IOException e) {
            System.err.println("Failed to export dataset: " + e.getMessage());
        }
    }
}
