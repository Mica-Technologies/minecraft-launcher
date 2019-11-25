package com.micatechnologies.minecraft.forgelauncher;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

import com.jfoenix.controls.JFXChip;
import com.jfoenix.controls.JFXChipView;
import com.jfoenix.controls.JFXComboBox;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class LauncherModpackGUI extends Application implements Initializable {

    @FXML
    public JFXComboBox<String> packList;

    @FXML
    public Label userMsg;

    @FXML
    public Button playBtn;

    @FXML
    public Button exitBtn;

    @FXML
    public Button settingsBtn;

    @FXML
    public ImageView userIcon;

    @FXML
    public Button logoutBtn;

    @FXML
    private Stage currStage = null;

    public CountDownLatch readyLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        // Get FXML File
        FXMLLoader fxmlLoader = new FXMLLoader();
        fxmlLoader.setLocation(
                getClass().getClassLoader().getResource("LauncherModpackGUI.fxml"));
        fxmlLoader.setController(this);
        AnchorPane pane = fxmlLoader.load();

        // Configure Window
        primaryStage.setTitle(LauncherConstants.LAUNCHER_SHORT_NAME);
        primaryStage.setScene(new Scene(pane, 645, 424));
        primaryStage.initStyle(StageStyle.UNIFIED);

        // Show Window
        currStage = primaryStage;
        primaryStage.show();
        readyLatch.countDown();
    }

    public Stage getCurrStage() {
        return currStage;
    }

    @Override
    public void initialize(final URL url, final ResourceBundle resourceBundle) {

    }

    public void showSettingsWindow() throws Exception {
        LauncherSettingsGUI settingsGUI = new LauncherSettingsGUI();
        Stage settingsStage = new Stage();
        settingsStage.initModality(Modality.WINDOW_MODAL);
        settingsStage.initOwner(getCurrStage());
        settingsGUI.init();
        settingsGUI.start(settingsStage);


    }
}
