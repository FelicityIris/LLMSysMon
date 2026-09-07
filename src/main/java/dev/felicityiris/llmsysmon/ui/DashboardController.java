package dev.felicityiris.llmsysmon.ui;

import dev.felicityiris.llmsysmon.database.DatabaseConfig;
import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.scheduler.MetricScheduler;
import dev.felicityiris.llmsysmon.scheduler.SchedulerConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class DashboardController {
    @FXML
    private Label cpuValue;

    @FXML
    private Label memoryValue;

    @FXML
    private Label swapValue;

    private MetricScheduler scheduler;
    private DatabaseConfig databaseConfig;

    public void initialize() {
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

    private void handleSnapshot(SystemSnapshot snapshot, MetricRepository metricRepository) {
        metricRepository.save(snapshot);

        Platform.runLater(() -> {
            cpuValue.setText(String.format("%.2f%%", snapshot.getCpu().getUsage()));
            memoryValue.setText(String.format("%.2f%%", snapshot.getMemory().getUsage()));
            swapValue.setText(String.format("%.2f%%", snapshot.getMemory().getSwapUsage()));
        });
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
