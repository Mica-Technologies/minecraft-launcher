package com.micatechnologies.minecraft.forgelauncher;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXChipView;
import com.jfoenix.controls.JFXComboBox;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class LauncherSettingsGUI extends Application implements Initializable {

    @FXML
    public JFXButton returnBtn;

    @FXML
    public JFXButton saveBtn;

    @FXML
    public JFXComboBox<String> minRAM;

    @FXML
    public JFXComboBox<String> maxRAM;

    @FXML
    public JFXChipView<String> modpackList;

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
                getClass().getClassLoader().getResource("LauncherSettingsGUI.fxml"));
        fxmlLoader.setController(this);
        AnchorPane pane = fxmlLoader.load();

        // Configure Window
        primaryStage.setTitle("Settings - " + LauncherConstants.LAUNCHER_SHORT_NAME);
        primaryStage.setScene(new Scene(pane, 465, 388));
        primaryStage.initStyle(StageStyle.UNIFIED);

        // Create Min RAM User-Friendly List
        String[] userFriendlyMinRAMList = new String[LauncherConstants.MINIMUM_RAM_OPTIONS.length];
        for (int x = 0; x < userFriendlyMinRAMList.length; x++)
            userFriendlyMinRAMList[x] = LauncherConstants.MINIMUM_RAM_OPTIONS[x] + " GB";
        minRAM.getItems().addAll(userFriendlyMinRAMList);
        for (int x = 0; x < LauncherConstants.MINIMUM_RAM_OPTIONS.length; x++) {
            if (LauncherCore.getLauncherConfig().minRAM == LauncherConstants.MINIMUM_RAM_OPTIONS[x]) minRAM.getSelectionModel().select(x);
        }

        // Create Max RAM User-Friendly List
        String[] userFriendlyMaxRAMList = new String[LauncherConstants.MAXIMUM_RAM_OPTIONS.length];
        for (int x = 0; x < userFriendlyMaxRAMList.length; x++)
            userFriendlyMaxRAMList[x] = LauncherConstants.MAXIMUM_RAM_OPTIONS[x] + " GB";
        maxRAM.getItems().addAll(userFriendlyMaxRAMList);
        for (int x = 0; x < LauncherConstants.MAXIMUM_RAM_OPTIONS.length; x++) {
            if (LauncherCore.getLauncherConfig().maxRAM == LauncherConstants.MAXIMUM_RAM_OPTIONS[x]) maxRAM.getSelectionModel().select(x);
        }

        // Populate Modpack List
        modpackList.getChips().addAll(LauncherCore.getLauncherConfig().modpacks);

        // Program return button
        returnBtn.setOnAction(event -> Platform.runLater(() -> getCurrStage().close()));

        // Program save button
        saveBtn.setOnAction(event -> {
            Platform.runLater(() -> {
                LauncherCore.getLauncherConfig().minRAM = LauncherConstants.MINIMUM_RAM_OPTIONS[minRAM.getSelectionModel().getSelectedIndex()];
                LauncherCore.getLauncherConfig().maxRAM = LauncherConstants.MAXIMUM_RAM_OPTIONS[maxRAM.getSelectionModel().getSelectedIndex()];
                LauncherCore.getLauncherConfig().modpacks = modpackList.getChips();
                try {
                    LauncherCore.getLauncherConfig().save();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        });


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
}
