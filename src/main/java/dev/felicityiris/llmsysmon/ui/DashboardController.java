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

    private final Deque<Double> cpuHistory = new ArrayDeque<>();
    private final Deque<Double> memoryHistory = new ArrayDeque<>();
    private final Deque<Double> swapHistory = new ArrayDeque<>();

    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> swapSeries;

    private MetricScheduler scheduler;
    private DatabaseConfig databaseConfig;

    public void initialize() {
        setupCharts();

        MetricService metricService = new MetricService();

        databaseConfig = new DatabaseConfig();
        MetricRepository metricRepository = new MetricRepository(databaseConfig.getEntityManagerFactory());

        scheduler = new MetricScheduler(
                metricService,
                new SchedulerConfig(1000),
                snapshot -> handleSnapshot(snapshot, metricRepository)
        );

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

    private void handleSnapshot(SystemSnapshot snapshot, MetricRepository metricRepository) {
        metricRepository.save(snapshot);

        double cpuUsage = snapshot.getCpu().getUsage();
        double memoryUsage = snapshot.getMemory().getUsage();
        double swapUsage = snapshot.getMemory().getSwapUsage();

        addToHistory(cpuHistory, cpuUsage);
        addToHistory(memoryHistory, memoryUsage);
        addToHistory(swapHistory, swapUsage);

        Platform.runLater(() -> {
            cpuValue.setText(String.format("%.2f%%", cpuUsage));
            memoryValue.setText(String.format("%.2f%%", memoryUsage));
            swapValue.setText(String.format("%.2f%%", swapUsage));

            updateChart(cpuSeries, cpuHistory);
            updateChart(memorySeries, memoryHistory);
            updateChart(swapSeries, swapHistory);
        });
    }

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

    public void shutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }

        if (databaseConfig != null) {
            databaseConfig.close();
        }
    }
}
