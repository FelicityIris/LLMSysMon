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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class DashboardController {
    private static final int LIVE_HISTORY_SIZE = 60;

    @FXML
    private Label cpuValue;

    @FXML
    private Label memoryValue;

    @FXML
    private Label swapValue;

    @FXML
    private LineChart<Number, Number> cpuChart;

    @FXML
    private LineChart<Number, Number> memoryChart;

    @FXML
    private LineChart<Number, Number> swapChart;

    @FXML
    private ComboBox<String> timeRangeSelector;

    private final Deque<SystemSnapshot> liveHistory = new ArrayDeque<>();

    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> swapSeries;

    private MetricScheduler scheduler;
    private DatabaseConfig databaseConfig;
    private MetricRepository metricRepository;

    public void initialize() {
        setupCharts();

        timeRangeSelector.getItems().addAll("1 minute", "5 minutes", "30 minutes", "1 hour");
        timeRangeSelector.setValue("1 minute");
        timeRangeSelector.setOnAction(event -> loadHistoricalData());

        MetricService metricService = new MetricService();

        databaseConfig = new DatabaseConfig();
        metricRepository = new MetricRepository(databaseConfig.getEntityManagerFactory());

        scheduler = new MetricScheduler(metricService, new SchedulerConfig(1000), this::handleSnapshot);

        scheduler.start();
    }

    private void setupCharts() {
        cpuSeries = new XYChart.Series<>();
        memorySeries = new XYChart.Series<>();
        swapSeries = new XYChart.Series<>();

        cpuSeries.setName("CPU");
        memorySeries.setName("RAM");
        swapSeries.setName("Swap");

        cpuChart.getData().add(cpuSeries);
        memoryChart.getData().add(memorySeries);
        swapChart.getData().add(swapSeries);

        configureChart(cpuChart);
        configureChart(memoryChart);
        configureChart(swapChart);
    }

    private void configureChart(LineChart<Number, Number> chart) {
        NumberAxis yAxis = (NumberAxis) chart.getYAxis();

        yAxis.setAutoRanging(false);
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(100);
        yAxis.setTickUnit(20);

        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
    }

    private void handleSnapshot(SystemSnapshot snapshot) {
        metricRepository.save(snapshot);

        Platform.runLater(() -> {
            cpuValue.setText(String.format("%.2f%%", snapshot.getCpu().getUsage()));
            memoryValue.setText(String.format("%.2f%%", snapshot.getMemory().getUsage()));
            swapValue.setText(String.format("%.2f%%", snapshot.getMemory().getSwapUsage()));

            addToLiveHistory(snapshot);
            updateLiveCharts();
        });
    }

    private void addToLiveHistory(SystemSnapshot snapshot) {
        if (liveHistory.size() >= LIVE_HISTORY_SIZE) {
            liveHistory.removeFirst();
        }

        liveHistory.addLast(snapshot);
    }

    private void updateLiveCharts() {
        Duration duration = getSelectedDuration();

        loadHistoricalData();
        /*if (duration.toSeconds() <= 90) updateLiveChartsFromMemory();
        else loadHistoricalData();*/
    }

    private void updateLiveChartsFromMemory() {
        Instant end = Instant.now();

        cpuSeries.getData().clear();
        memorySeries.getData().clear();
        swapSeries.getData().clear();

        for (SystemSnapshot snapshot : liveHistory) {
            double secondsAgo = Duration.between(snapshot.getTimestamp(), end).toMillis() / -1000.0;

            cpuSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getCpu().getUsage()));
            memorySeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getUsage()));
            swapSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getSwapUsage()));
        }

        configureLiveXAxis(cpuChart);
        configureLiveXAxis(memoryChart);
        configureLiveXAxis(swapChart);
    }

    private void loadHistoricalData() {
        Duration duration = getSelectedDuration();

        Instant end = Instant.now();
        Instant start = end.minus(duration);

        List<SystemSnapshot> snapshots = metricRepository.findBetween(start, end);

        setChartsAnimated(false);

        cpuSeries.getData().clear();
        memorySeries.getData().clear();
        swapSeries.getData().clear();

        configureHistoricalXAxis(cpuChart, duration.getSeconds());
        configureHistoricalXAxis(memoryChart, duration.getSeconds());
        configureHistoricalXAxis(swapChart, duration.getSeconds());

        for (SystemSnapshot snapshot : snapshots) {
            double secondsAgo = Duration.between(snapshot.getTimestamp(), end).toMillis() / -1000.0;

            cpuSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getCpu().getUsage()));
            memorySeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getUsage()));
            swapSeries.getData().add(new XYChart.Data<>(secondsAgo, snapshot.getMemory().getSwapUsage()));
        }

        setChartsAnimated(true);
    }

    private Duration getSelectedDuration() {
        return switch (timeRangeSelector.getValue()) {
            case "5 minutes" -> Duration.ofMinutes(5);
            case "30 minutes" -> Duration.ofMinutes(30);
            case  "1 hour" -> Duration.ofHours(1);
            default -> Duration.ofMinutes(1);
        };
    }

    private void configureLiveXAxis(LineChart<Number, Number> chart) {
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(-(LIVE_HISTORY_SIZE - 1));
        xAxis.setUpperBound(0);
        xAxis.setTickUnit(10);
    }

    private void configureHistoricalXAxis(LineChart<Number, Number> chart, double durationSeconds) {
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(-durationSeconds);
        xAxis.setUpperBound(0);
        xAxis.setTickUnit(getTickUnit(durationSeconds));
    }

    private double getTickUnit(double durationSeconds) {
        if (durationSeconds <= 60) return 10;
        if (durationSeconds <= 300) return 60;
        if (durationSeconds <= 1800) return 300;

        return 600;
    }

    private void setChartsAnimated(boolean animated) {
        cpuChart.setAnimated(animated);
        memoryChart.setAnimated(animated);
        swapChart.setAnimated(animated);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }

        if (databaseConfig != null) {
            databaseConfig.close();
        }
    }
}
