package dev.felicityiris.llmsysmon;

import dev.felicityiris.llmsysmon.metrics.MetricService;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import dev.felicityiris.llmsysmon.scheduler.MetricScheduler;
import dev.felicityiris.llmsysmon.scheduler.SchedulerConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

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
        SchedulerConfig schedulerConfig = new SchedulerConfig(1000);

        MetricScheduler scheduler = new MetricScheduler(
                metricService,
                schedulerConfig,
                snapshot -> System.out.printf(
                        "CPU: U%% - %.2f%% CF - %d MF - %d nLP - %d nPP - %d | " +
                        "RAM: T - %.2f A - %.2f U - %.2f U%% - %.2f%% | " +
                        "SWAP: T - %.2f U - %.2f U%% - %.2f%%\n",
                        snapshot.getCpu().getUsage(),
                        snapshot.getCpu().getCurrentFrequency(),
                        snapshot.getCpu().getMaxFrequency(),
                        snapshot.getCpu().getLogicalProcessorCount(),
                        snapshot.getCpu().getPhysicalProcessorCount(),

                        (double) snapshot.getMemory().getTotalBytes() / (1024 * 1024 * 1024),
                        (double) snapshot.getMemory().getAvailableBytes() / (1024 * 1024 * 1024),
                        (double) snapshot.getMemory().getUsedBytes() / (1024 * 1024 * 1024),
                        snapshot.getMemory().getUsage(),

                        (double) snapshot.getMemory().getTotalSwapBytes() / (1024 * 1024 * 1024),
                        (double) snapshot.getMemory().getUsedSwapBytes() / (1024 * 1024 * 1024),
                        snapshot.getMemory().getSwapUsage()
                )
        );

        scheduler.start();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.stop();
        }

    }
}