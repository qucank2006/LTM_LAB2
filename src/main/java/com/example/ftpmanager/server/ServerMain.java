package com.example.ftpmanager.server;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.example.ftpmanager.server.core.ServerController;

public final class ServerMain extends Application {
    @Override public void start(Stage stage) throws Exception {
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/fxml/server.fxml"));
        Scene scene=new Scene(loader.load()); scene.getStylesheets().add(getClass().getResource("/css/mocha.css").toExternalForm());
        ServerController controller=loader.getController(); stage.setTitle("FTP Manager · Server"); stage.setScene(scene);
        stage.setWidth(1000); stage.setHeight(650);
        stage.setMinWidth(850); stage.setMinHeight(550);
        stage.setResizable(true); stage.setMaximized(false);
        stage.setOnCloseRequest(e->controller.shutdown()); stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
