package com.micatechnologies.minecraft.forgelauncher;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

import com.jfoenix.controls.JFXProgressBar;
import javafx.application.Application;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class LauncherProgressGUI extends Application implements Initializable {

    @FXML
    public Label upperText;

    @FXML
    public Label lowerText;

    @FXML
    public JFXProgressBar progressBar;

    @FXML
    public ImageView userIcon;

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
                getClass().getClassLoader().getResource("LauncherProgressGUI.fxml"));
        fxmlLoader.setController(this);
        AnchorPane pane = fxmlLoader.load();

        // Configure Window
        primaryStage.setTitle("Login - " + LauncherConstants.LAUNCHER_SHORT_NAME);
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
        // Set default GUI options
        upperText.setText("Loading, please wait...");
        lowerText.setText("--");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        userIcon.setImage(new Image("no_user.png"));
    }
}
