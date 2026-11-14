package com.example.ftpmanager.common.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;

public final class Ui {
    private Ui() {}
    public static void later(Runnable r) { if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r); }
    public static void error(Throwable e) { later(() -> new Alert(Alert.AlertType.ERROR, e.getMessage() == null ? e.toString() : e.getMessage()).show()); }
}
