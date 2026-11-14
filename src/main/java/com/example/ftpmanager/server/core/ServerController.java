package com.example.ftpmanager.server.core;

import com.example.ftpmanager.common.config.AppConfig;
import com.example.ftpmanager.common.util.Ui;
import com.example.ftpmanager.server.database.Database;
import com.example.ftpmanager.server.handler.FtpSession;
import com.example.ftpmanager.server.model.FtpUser;
import javafx.fxml.FXML;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.control.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import java.util.concurrent.*;
import java.util.function.Function;

public final class ServerController {
    @FXML private TextField bind,port,passiveMin,passiveMax,account,home;
    @FXML private PasswordField password;
    @FXML private Label status,dashboard,addresses;
    @FXML private TextArea logs;
    @FXML private TableView<FtpSession> clients;
    @FXML private ListView<FtpUser> users;
    @FXML private Button start,stop;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FtpServer server;
    private Database db;
    private Timeline timer;
    @FXML private void initialize() {
        try {
            AppConfig c=new AppConfig(); db=new Database(c); server=new FtpServer(c,db,this::log);
            bind.setText(c.get("server.bind")); port.setText(c.get("server.port")); passiveMin.setText(c.get("server.passive.min")); passiveMax.setText(c.get("server.passive.max"));
            column("Username",s->s.username); column("IP",s->s.ip); column("Connected",s->s.connected.toString()); column("Operation",s->s.operation);
            timer=new Timeline(new KeyFrame(Duration.seconds(1),e->refreshDashboard())); timer.setCycleCount(Timeline.INDEFINITE); timer.play();
            worker.submit(()-> { try { var list=java.net.NetworkInterface.networkInterfaces().flatMap(n->n.inetAddresses()).filter(a->!a.isLoopbackAddress()).map(java.net.InetAddress::getHostAddress).toList(); Ui.later(()->addresses.setText("Server IP: "+String.join(", ",list))); } catch(Exception e) { log(e.toString()); } });
            refreshUsers();
        } catch(Exception e) { Ui.error(e); start.setDisable(true); }
    }
    private void column(String name, Function<FtpSession,String> value) { TableColumn<FtpSession,String> c=new TableColumn<>(name); c.setCellValueFactory(x->new SimpleStringProperty(value.apply(x.getValue()))); c.setPrefWidth(240); clients.getColumns().add(c); }
    private void refreshDashboard() {
        if(server==null) return;
        clients.setItems(FXCollections.observableArrayList(server.sessions.values()));
        dashboard.setText("Clients: "+server.sessions.size()+"    Active transfers: "+server.transfers.get()+"    Uploaded: "+server.uploaded.get()+" B    Downloaded: "+server.downloaded.get()+" B");
        status.setText(server.running()?"● RUNNING":"● STOPPED"); start.setDisable(server.running()); stop.setDisable(!server.running());
    }
    private void log(String line) { Ui.later(()-> { logs.appendText(line+"\n"); if(logs.getLength()>120000) logs.deleteText(0,40000); }); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void background(Work work) { worker.submit(()-> { try { work.run(); } catch(Exception e) { log("ERROR "+e.getMessage()); Ui.error(e); } }); }
    @FXML private void startServer() {
        String b=bind.getText(),p=port.getText(),min=passiveMin.getText(),max=passiveMax.getText(); start.setDisable(true);
        background(()-> { try { server.start(b,Integer.parseInt(p),Integer.parseInt(min),Integer.parseInt(max)); } finally { Ui.later(this::refreshDashboard); } });
    }
    @FXML private void stopServer() { background(()->server.close()); }
    @FXML private void disconnectClient() { FtpSession s=clients.getSelectionModel().getSelectedItem(); if(s!=null) background(s::close); }
    @FXML private void refreshUsers() { if(db!=null) background(()-> { var list=db.users(); Ui.later(()->users.setItems(FXCollections.observableArrayList(list))); }); }
    @FXML private void createUser() {
        String n=account.getText(),p=password.getText(),h=home.getText(); password.clear();
        background(()-> { db.createUser(n,p,h); log("Created user "+n); refreshUsers(); });
    }
    @FXML private void disableUser() { FtpUser u=users.getSelectionModel().getSelectedItem(); if(u!=null) background(()-> { db.enabled(u.id(),!u.enabled()); server.disconnectUser(u.id()); refreshUsers(); }); }
    @FXML private void changePassword() { FtpUser u=users.getSelectionModel().getSelectedItem(); String p=password.getText(); password.clear(); if(u!=null) background(()-> { db.changePassword(u.id(),p); server.disconnectUser(u.id()); log("Changed password for "+u.username()); }); }
    @FXML private void assignHome() { FtpUser u=users.getSelectionModel().getSelectedItem(); String h=home.getText(); if(u!=null) background(()-> { db.home(u.id(),h); server.disconnectUser(u.id()); refreshUsers(); }); }
    public void shutdown() { if(timer!=null) timer.stop(); worker.submit(()-> { if(server!=null) server.close(); }); worker.shutdown(); }
}
