package com.example.ftpmanager.client.controller;

import com.example.ftpmanager.client.model.*;
import com.example.ftpmanager.client.network.FtpClient;
import com.example.ftpmanager.client.service.TransferQueue;
import com.example.ftpmanager.client.util.RemotePaths;
import com.example.ftpmanager.common.util.Ui;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.collections.FXCollections;
import javafx.beans.property.SimpleStringProperty;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class ClientController {
    @FXML private TextField host,port,username,localPath,remotePath;
    @FXML private PasswordField password;
    @FXML private ComboBox<String> mode,type;
    @FXML private TableView<LocalEntry> localFiles;
    @FXML private TableView<RemoteEntry> remoteFiles;
    @FXML private TableView<TransferItem> queue;
    @FXML private TreeView<Path> localTree;
    @FXML private TreeView<String> remoteTree;
    @FXML private TextArea logs;
    @FXML private Label status;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final FtpClient ftp=new FtpClient(this::log);
    private final TransferQueue transfers=new TransferQueue(ftp,this::conflict,this::notification);
    private Path localDirectory=Path.of(System.getProperty("user.home"));
    private String remoteDirectory="/";
    private volatile TransferItem active;
    private volatile Login login;
    private boolean closing;
    private record Login(String host,int port,String user,String password) {}
    @FunctionalInterface private interface Work { void run() throws Exception; }
    @FXML private void initialize() {
        mode.setItems(FXCollections.observableArrayList("Passive (EPSV/PASV)","Active (PORT/EPRT)")); mode.getSelectionModel().selectFirst();
        type.setItems(FXCollections.observableArrayList("Binary (TYPE I)","ASCII (TYPE A)")); type.getSelectionModel().selectFirst();
        column(localFiles,"Filename",LocalEntry::name,230); column(localFiles,"Size",e->e.directory()?"":Long.toString(e.size()),90); column(localFiles,"Type",LocalEntry::type,70); column(localFiles,"Modified",LocalEntry::modified,165);
        column(remoteFiles,"Filename",RemoteEntry::name,230); column(remoteFiles,"Size",e->e.directory()?"":Long.toString(e.size()),90); column(remoteFiles,"Type",RemoteEntry::type,70); column(remoteFiles,"Modified (UTC)",RemoteEntry::modified,150);
        column(queue,"Filename",TransferItem::filename,150); column(queue,"Direction",e->e.upload?"Upload →":"← Download",100); column(queue,"Source",TransferItem::source,180); column(queue,"Destination",TransferItem::destination,180);
        TableColumn<TransferItem,Double> progress=new TableColumn<>("Progress"); progress.setCellValueFactory(x->x.getValue().progress.asObject()); progress.setCellFactory(ProgressBarTableCell.forTableColumn()); progress.setPrefWidth(140); queue.getColumns().add(progress);
        TableColumn<TransferItem,String> speed=new TableColumn<>("Speed"); speed.setCellValueFactory(x->x.getValue().speed); speed.setPrefWidth(110); queue.getColumns().add(speed);
        TableColumn<TransferItem,String> bytes=new TableColumn<>("Transferred bytes"); bytes.setCellValueFactory(x->x.getValue().bytes); bytes.setPrefWidth(140); queue.getColumns().add(bytes);
        TableColumn<TransferItem,String> state=new TableColumn<>("Status"); state.setCellValueFactory(x->x.getValue().status); state.setPrefWidth(125); queue.getColumns().add(state);
        localFiles.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); remoteFiles.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        localFiles.setOnMouseClicked(e->{ if(e.getClickCount()==2) { var row=localFiles.getSelectionModel().getSelectedItem(); if(row!=null&&row.directory()) navigateLocal(row.path()); } });
        remoteFiles.setOnMouseClicked(e->{ if(e.getClickCount()==2) { var row=remoteFiles.getSelectionModel().getSelectedItem(); if(row!=null&&row.directory()) navigateRemote(RemotePaths.child(remoteDirectory,row.name())); } });
        localFiles.setContextMenu(menu(true)); remoteFiles.setContextMenu(menu(false));
        MenuItem cancel=new MenuItem("Cancel"); cancel.setOnAction(e->cancelTransfer()); MenuItem retry=new MenuItem("Retry / Resume"); retry.setOnAction(e->retryTransfer()); queue.setContextMenu(new ContextMenu(cancel,retry));
        localTree.setCellFactory(v->new TreeCell<>() { @Override protected void updateItem(Path p,boolean empty) { super.updateItem(p,empty); setText(empty||p==null?null:(p.getFileName()==null?p.toString():p.getFileName().toString())); } });
        localTree.setOnMouseClicked(e->{ if(e.getClickCount()==2) { var selected=localTree.getSelectionModel().getSelectedItem(); if(selected!=null) navigateLocal(selected.getValue()); } });
        remoteTree.setOnMouseClicked(e->{ if(e.getClickCount()==2) { var selected=remoteTree.getSelectionModel().getSelectedItem(); if(selected!=null) navigateRemote(selected.getValue()); } });
        TreeItem<Path> root=new TreeItem<>(localDirectory.toAbsolutePath().getRoot()); localTree.setRoot(root); loadTree(root); root.setExpanded(true);
        navigateLocal(localDirectory);
    }
    private <T> void column(TableView<T> table,String label,Function<T,String> f,int width) { TableColumn<T,String> c=new TableColumn<>(label); c.setCellValueFactory(x->new SimpleStringProperty(f.apply(x.getValue()))); c.setPrefWidth(width); table.getColumns().add(c); }
    private ContextMenu menu(boolean local) {
        ContextMenu m=new ContextMenu(); String[] labels={local?"Upload":"Download","Refresh","Create folder","Delete","Rename","View properties"};
        Runnable[] actions={local?this::upload:this::download,local?this::refreshLocal:this::refreshRemote,()->createFolder(local),()->delete(local),()->rename(local),()->properties(local)};
        for(int i=0;i<labels.length;i++) { MenuItem item=new MenuItem(labels[i]); Runnable r=actions[i]; item.setOnAction(e->r.run()); m.getItems().add(item); } return m;
    }
    private void work(Work w) { if(closing) return; io.submit(()-> { try { w.run(); } catch(Exception e) { log("ERROR: "+e.getMessage()); Ui.error(e); } }); }
    private void log(String line) { Ui.later(()-> { logs.appendText(LocalTime.now().withNano(0)+" "+line+"\n"); if(logs.getLength()>120000) logs.deleteText(0,40000); }); }
    private void notification(String text) { log(text); Ui.later(()->status.setText(text)); }
    @FXML private void connect() {
        try {
            Login info=new Login(host.getText().trim(),Integer.parseInt(port.getText()),username.getText(),password.getText());
            boolean passive=mode.getSelectionModel().getSelectedIndex()==0,ascii=type.getSelectionModel().getSelectedIndex()==1;
            login=info;
            work(()-> { if(login!=info) return; ftp.mode(passive,ascii); ftp.connect(info.host,info.port,info.user,info.password); if(login!=info) { ftp.close(); return; } refreshRemoteNow(); notification("Connected to "+info.host); });
        } catch(Exception e) { Ui.error(e); }
    }
    @FXML private void changeMode() {
        if(mode==null||type==null) return;
        boolean passive=mode.getSelectionModel().getSelectedIndex()!=1,ascii=type.getSelectionModel().getSelectedIndex()==1;
        work(()->ftp.mode(passive,ascii));
    }
    @FXML private void disconnect() {
        login=null; for(var item:queue.getItems()) { if(!item.status.get().equals("Completed")) { item.cancelled=true; item.status.set("Cancelled"); } }
        ftp.cancel(); work(()-> { ftp.disconnect(); notification("Disconnected"); });
    }
    private void ensureConnected() throws Exception { if(!ftp.connected()) { Login info=login; if(info==null) throw new IllegalStateException("Connect first"); ftp.connect(info.host,info.port,info.user,info.password); } }
    private void loadTree(TreeItem<Path> node) {
        work(()-> {
            List<Path> dirs; try(var s=Files.list(node.getValue())) { dirs=s.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList(); }
            Ui.later(()-> { node.getChildren().clear(); for(Path p:dirs) { var child=new TreeItem<Path>(p); child.getChildren().add(new TreeItem<>()); child.expandedProperty().addListener((o,a,b)-> { if(b) loadTree(child); }); node.getChildren().add(child); } });
        });
    }
    private void navigateLocal(Path p) {
        work(()-> {
            Path dir=p.toAbsolutePath().normalize(); if(!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory"); var rows=new ArrayList<LocalEntry>();
            try(var stream=Files.list(dir)) { for(Path f:stream.sorted().toList()) { try { var a=Files.readAttributes(f,BasicFileAttributes.class); rows.add(new LocalEntry(f,f.getFileName().toString(),a.isDirectory(),a.size(),a.lastModifiedTime().toString())); } catch(java.io.IOException e) { log("Cannot inspect "+f); } } }
            Ui.later(()-> { localDirectory=dir; localPath.setText(dir.toString()); localFiles.setItems(FXCollections.observableArrayList(rows)); });
        });
    }
    @FXML private void localGo() { try { navigateLocal(Path.of(localPath.getText())); } catch(Exception e) { Ui.error(e); } }
    @FXML private void localUp() { if(localDirectory.getParent()!=null) navigateLocal(localDirectory.getParent()); }
    @FXML private void refreshLocal() { navigateLocal(localDirectory); }
    @FXML private void remoteGo() { navigateRemote(remotePath.getText()); }
    @FXML private void remoteUp() { navigateRemote(RemotePaths.parent(remoteDirectory)); }
    private void navigateRemote(String path) { work(()-> { ensureConnected(); ftp.cwd(path); refreshRemoteNow(); }); }
    @FXML private void refreshRemote() { work(()-> { ensureConnected(); refreshRemoteNow(); }); }
    private void refreshRemoteNow() throws Exception {
        String pwd=ftp.pwd(); var rows=ftp.list();
        Ui.later(()-> {
            remoteDirectory=pwd; remotePath.setText(pwd); remoteFiles.setItems(FXCollections.observableArrayList(rows));
            var root=new TreeItem<String>(pwd); if(!pwd.equals("/")) root.getChildren().add(new TreeItem<>(RemotePaths.parent(pwd)));
            rows.stream().filter(RemoteEntry::directory).forEach(e->root.getChildren().add(new TreeItem<>(RemotePaths.child(pwd,e.name())))); root.setExpanded(true); remoteTree.setRoot(root);
        });
    }
    @FXML private void upload() { for(var e:List.copyOf(localFiles.getSelectionModel().getSelectedItems())) { if(e.directory()) { notification("Open folder and select files to upload"); continue; } enqueue(new TransferItem(true,e.path(),RemotePaths.child(remoteDirectory,e.name()),e.size())); } }
    @FXML private void download() {
        for(var e:List.copyOf(remoteFiles.getSelectionModel().getSelectedItems())) {
            if(e.directory()) { notification("Open folder and select files to download"); continue; }
            try { String safe=RemotePaths.name(e.name()); Path destination=localDirectory.resolve(safe).normalize(); if(!destination.getParent().equals(localDirectory)) throw new IllegalArgumentException("Unsafe remote filename"); enqueue(new TransferItem(false,destination,RemotePaths.child(remoteDirectory,safe),e.size())); } catch(Exception ex) { Ui.error(ex); }
        }
    }
    private void enqueue(TransferItem item) {
        queue.getItems().add(item); boolean passive=mode.getSelectionModel().getSelectedIndex()==0,ascii=type.getSelectionModel().getSelectedIndex()==1;
        work(()-> {
            if(item.cancelled) return; active=item;
            try { ensureConnected(); ftp.mode(passive,ascii); transfers.run(item,ascii); if(!item.cancelled) try { refreshRemoteNow(); } catch(Exception e) { log("Transfer finished; refresh failed: "+e.getMessage()); } }
            catch(Exception e) { Ui.later(()->item.status.set(item.cancelled?"Cancelled":"Failed")); notification((item.cancelled?"Cancelled: ":"Failed: ")+item.filename()+" — "+e.getMessage()); }
            finally { active=null; Ui.later(this::refreshLocal); }
        });
    }
    @FXML private void cancelTransfer() { var item=queue.getSelectionModel().getSelectedItem(); if(item==null) item=active; if(item!=null&&!item.status.get().equals("Completed")) { item.cancelled=true; item.status.set("Cancelled"); if(active==item) ftp.cancel(); } }
    @FXML private void retryTransfer() { var item=queue.getSelectionModel().getSelectedItem(); if(item!=null&&(item.status.get().equals("Failed")||item.status.get().equals("Cancelled"))) enqueue(new TransferItem(item.upload,item.local,item.remote,item.total)); }
    private TransferQueue.Decision conflict(String message,boolean resume) throws Exception {
        CompletableFuture<TransferQueue.Decision> answer=new CompletableFuture<>();
        Ui.later(()-> {
            ButtonType overwrite=new ButtonType("Overwrite"),continueButton=new ButtonType("Resume (same file only)");
            Alert a=new Alert(Alert.AlertType.CONFIRMATION,message,overwrite,ButtonType.CANCEL); if(resume) a.getButtonTypes().add(1,continueButton);
            var result=a.showAndWait().orElse(ButtonType.CANCEL); answer.complete(result==overwrite?TransferQueue.Decision.OVERWRITE:result==continueButton?TransferQueue.Decision.RESUME:TransferQueue.Decision.CANCEL);
        }); return answer.get();
    }
    private Optional<String> input(String title,String initial) { TextInputDialog d=new TextInputDialog(initial); d.setTitle(title); d.setHeaderText(title); return d.showAndWait(); }
    private boolean confirm(String message) { return new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL).showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK; }
    private void createFolder(boolean local) {
        Path dir=localDirectory; String remote=remoteDirectory;
        input("Create folder","").ifPresent(name-> { try { RemotePaths.name(name); work(()-> { if(local) Files.createDirectory(dir.resolve(name)); else { ensureConnected(); ftp.execute("MKD "+RemotePaths.child(remote,name),257); refreshRemoteNow(); } Ui.later(this::refreshLocal); }); } catch(Exception e) { Ui.error(e); } });
    }
    private void delete(boolean local) {
        var l=localFiles.getSelectionModel().getSelectedItem(); var r=remoteFiles.getSelectionModel().getSelectedItem(); String dir=remoteDirectory;
        if((local&&l==null)||(!local&&r==null)||!confirm("Delete selected item? Directories must be empty.")) return;
        work(()-> { if(local) Files.delete(l.path()); else { ensureConnected(); ftp.execute((r.directory()?"RMD ":"DELE ")+RemotePaths.child(dir,r.name()),250); refreshRemoteNow(); } Ui.later(this::refreshLocal); });
    }
    private void rename(boolean local) {
        var l=localFiles.getSelectionModel().getSelectedItem(); var r=remoteFiles.getSelectionModel().getSelectedItem(); String dir=remoteDirectory;
        if((local&&l==null)||(!local&&r==null)) return;
        input("Rename",local?l.name():r.name()).ifPresent(name-> { try { RemotePaths.name(name); work(()-> { if(local) Files.move(l.path(),l.path().resolveSibling(name)); else { ensureConnected(); ftp.rename(RemotePaths.child(dir,r.name()),RemotePaths.child(dir,name)); refreshRemoteNow(); } Ui.later(this::refreshLocal); }); } catch(Exception e) { Ui.error(e); } });
    }
    private void properties(boolean local) {
        var l=localFiles.getSelectionModel().getSelectedItem(); var r=remoteFiles.getSelectionModel().getSelectedItem();
        if(local&&l!=null) new Alert(Alert.AlertType.INFORMATION,l.path()+"\nType: "+l.type()+"\nSize: "+l.size()+" bytes\nModified: "+l.modified()).show();
        else if(!local&&r!=null) new Alert(Alert.AlertType.INFORMATION,RemotePaths.child(remoteDirectory,r.name())+"\nType: "+r.type()+"\nSize: "+r.size()+" bytes\nModified (UTC): "+r.modified()).show();
    }
    public void shutdown() { closing=true; login=null; ftp.cancel(); io.shutdownNow(); }
}
