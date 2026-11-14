package com.example.ftpmanager.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.example.ftpmanager.client.controller.ClientController;

public final class ClientMain extends Application {
    @Override public void start(Stage stage) throws Exception {
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/fxml/client.fxml"));
        Scene scene=new Scene(loader.load()); scene.getStylesheets().add(getClass().getResource("/css/mocha.css").toExternalForm());
        ClientController controller=loader.getController(); stage.setTitle("FTP Manager · Client"); stage.setScene(scene);
        stage.setWidth(1100); stage.setHeight(700);
        stage.setMinWidth(950); stage.setMinHeight(600);
        stage.setResizable(true); stage.setMaximized(false);
        stage.setOnCloseRequest(e->controller.shutdown()); stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
