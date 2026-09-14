package dev.felicityiris.llmsysmon;

import dev.felicityiris.llmsysmon.ui.DashboardController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;

import javafx.stage.Stage;

import java.util.List;

public class Main extends Application {
    private DashboardController controller;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/fxml/dashboard.fxml"));

        Scene scene = new Scene(loader.load());
        scene.getStylesheets().add(Main.class.getResource("/css/dashboard.css").toExternalForm());

        controller = loader.getController();

        stage.setTitle("LLMSysMon");
        stage.setScene(scene);
        stage.setWidth(1600);
        stage.setHeight(900);
        stage.show();

        controller.exportDataset();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}