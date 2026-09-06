package dev.felicityiris.llmsysmon;

import dev.felicityiris.llmsysmon.database.DatabaseConfig;
import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.scheduler.MetricScheduler;
import dev.felicityiris.llmsysmon.scheduler.SchedulerConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.List;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("LLMSysMon");
        StackPane root = new StackPane(label);

        Scene scene = new Scene(root, 1600, 900);

        stage.setTitle("LLMSysMon");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        //launch();
        MetricService metricService = new MetricService();

        DatabaseConfig databaseConfig = new DatabaseConfig();
        MetricRepository metricRepository = new MetricRepository(databaseConfig.getEntityManagerFactory());

        SchedulerConfig schedulerConfig = new SchedulerConfig(1000);

        MetricScheduler scheduler = new MetricScheduler(metricService, schedulerConfig, metricRepository::save);

        scheduler.start();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.stop();
        }

        List<SystemSnapshot> snapshots = metricRepository.findRecent(5);

        for (SystemSnapshot snapshot : snapshots) {
            System.out.printf(
                    "Time:\t%s\t|\tCPU:\t%.2f%%\t|\tRAM:\t%.2f%%\t|\tSWAP:\t%.2f%%\n",
                    snapshot.getTimestamp(),
                    snapshot.getCpu().getUsage(),
                    snapshot.getMemory().getUsage(),
                    snapshot.getMemory().getSwapUsage()
            );
        }

        databaseConfig.close();
    }
}