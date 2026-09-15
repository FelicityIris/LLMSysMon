package dev.felicityiris.llmsysmon.ui;

import dev.felicityiris.llmsysmon.database.DatabaseConfig;
import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.scheduler.MetricScheduler;
import dev.felicityiris.llmsysmon.scheduler.SchedulerConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;

import java.util.ArrayDeque;
import java.util.Deque;

public class DashboardController {
    private static final int HISTORY_SIZE = 60;

    // ── Stat card labels ──────────────────────────────────────────
    @FXML private Label cpuValue;
    @FXML private Label cpuSubValue;       // "3.60 GHz  ·  16 cores"

    @FXML private Label memoryValue;
    @FXML private Label memorySubValue;    // "11.2 / 31.8 GB"

    @FXML private Label swapValue;
    @FXML private Label swapSubValue;      // "0.5 / 4.0 GB" | "No swap configured"

    @FXML private Label gpuValue;
    @FXML private Label gpuSubValue;       // "0.5 / 4.0 GB VRAM" | "N/A"

    // ── Charts ───────────────────────────────────────────────────
    @FXML private LineChart<Number, Number> cpuChart;
    @FXML private LineChart<Number, Number> memoryChart;
    @FXML private LineChart<Number, Number> swapChart;
    @FXML private LineChart<Number, Number> gpuChart;

    // ── Rolling history (one entry per second, max 60) ───────────
    private final Deque<Double> cpuHistory    = new ArrayDeque<>();
    private final Deque<Double> memoryHistory = new ArrayDeque<>();
    private final Deque<Double> swapHistory   = new ArrayDeque<>();
    private final Deque<Double> gpuHistory    = new ArrayDeque<>();

    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> swapSeries;
    private XYChart.Series<Number, Number> gpuSeries;

    private MetricScheduler scheduler;
    private DatabaseConfig   databaseConfig;

    public void initialize() {
        setupCharts();

        MetricService    metricService    = new MetricService();
        databaseConfig                    = new DatabaseConfig();
        MetricRepository metricRepository = new MetricRepository(databaseConfig.getEntityManagerFactory());

        scheduler = new MetricScheduler(
                metricService,
                new SchedulerConfig(1000),
                snapshot -> handleSnapshot(snapshot, metricRepository)
        );

        scheduler.start();
    }

    // ── Chart setup ───────────────────────────────────────────────

    private void setupCharts() {
        cpuSeries    = new XYChart.Series<>();
        memorySeries = new XYChart.Series<>();
        swapSeries   = new XYChart.Series<>();
        gpuSeries    = new XYChart.Series<>();

        cpuChart.getData().add(cpuSeries);
        memoryChart.getData().add(memorySeries);
        swapChart.getData().add(swapSeries);
        gpuChart.getData().add(gpuSeries);

        configureChart(cpuChart);
        configureChart(memoryChart);
        configureChart(swapChart);
        configureChart(gpuChart);
    }

    private void configureChart(LineChart<Number, Number> chart) {
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();
        NumberAxis yAxis = (NumberAxis) chart.getYAxis();

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(HISTORY_SIZE - 1);
        xAxis.setTickUnit(10);

        yAxis.setAutoRanging(false);
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(100);
        yAxis.setTickUnit(20);

        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
    }

    // ── Snapshot handling ─────────────────────────────────────────

    private void handleSnapshot(SystemSnapshot snapshot, MetricRepository metricRepository) {
        metricRepository.save(snapshot);

        // Usage percentages
        double cpuUsage    = snapshot.getCpu().getUsage();
        double memoryUsage = snapshot.getMemory().getUsage();
        double swapUsage   = snapshot.getMemory().getSwapUsage();
        double gpuUsage    = snapshot.getGpu().getUsage();

        addToHistory(cpuHistory,    cpuUsage);
        addToHistory(memoryHistory, memoryUsage);
        addToHistory(swapHistory,   swapUsage);
        addToHistory(gpuHistory,    gpuUsage);

        // CPU sub-stat: frequency + core count
        double freqGHz   = snapshot.getCpu().getCurrentFrequency() / 1_000_000_000.0;
        int logicalCores = snapshot.getCpu().getLogicalProcessorCount();

        // Memory sub-stat: used / total GB
        double usedMemGB  = snapshot.getMemory().getUsedBytes()  / 1_073_741_824.0;
        double totalMemGB = snapshot.getMemory().getTotalBytes() / 1_073_741_824.0;

        // Swap sub-stat
        double totalSwapGB = snapshot.getMemory().getTotalSwapBytes() / 1_073_741_824.0;
        double usedSwapGB  = snapshot.getMemory().getUsedSwapBytes()  / 1_073_741_824.0;
        String swapSub = (totalSwapGB == 0.0)
                ? "No swap configured"
                : String.format("%.1f / %.1f GB", usedSwapGB, totalSwapGB);

        // GPU sub-stat: VRAM used / total
        boolean gpuAvailable   = snapshot.getGpu().isAvailable();
        long    vramUsedMB     = snapshot.getGpu().getVramUsedMB();
        long    vramTotalMB    = snapshot.getGpu().getVramTotalMB();
        String gpuSub = gpuAvailable
                ? String.format("%.1f / %.1f GB VRAM",
                        vramUsedMB / 1024.0, vramTotalMB / 1024.0)
                : "N/A";

        Platform.runLater(() -> {
            cpuValue.setText(String.format("%.1f%%", cpuUsage));
            cpuSubValue.setText(String.format("%.2f GHz  ·  %d cores", freqGHz, logicalCores));

            memoryValue.setText(String.format("%.1f%%", memoryUsage));
            memorySubValue.setText(String.format("%.1f / %.1f GB", usedMemGB, totalMemGB));

            swapValue.setText(String.format("%.1f%%", swapUsage));
            swapSubValue.setText(swapSub);

            gpuValue.setText(gpuAvailable ? String.format("%.1f%%", gpuUsage) : "N/A");
            gpuSubValue.setText(gpuSub);

            updateChart(cpuSeries,    cpuHistory);
            updateChart(memorySeries, memoryHistory);
            updateChart(swapSeries,   swapHistory);
            updateChart(gpuSeries,    gpuHistory);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void addToHistory(Deque<Double> history, double value) {
        if (history.size() >= HISTORY_SIZE) {
            history.removeFirst();
        }
        history.addLast(value);
    }

    private void updateChart(XYChart.Series<Number, Number> series, Deque<Double> history) {
        series.getData().clear();
        int index = 0;
        for (double value : history) {
            series.getData().add(new XYChart.Data<>(index++, value));
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    public void shutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (databaseConfig != null) {
            databaseConfig.close();
        }
    }
}
