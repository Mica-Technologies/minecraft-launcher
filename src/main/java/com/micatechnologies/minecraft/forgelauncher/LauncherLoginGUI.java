package com.micatechnologies.minecraft.forgelauncher;

import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class LauncherLoginGUI extends Application implements Initializable {
    private static final String BAD_LOGIN_TEXT = "Try Again";
    private static final String REG_LOGIN_TEXT = "Login";

    @FXML
    public JFXTextField emailField;

    @FXML
    public JFXPasswordField passwordField;

    @FXML
    public JFXCheckBox rememberCheckBox;

    @FXML
    public JFXButton loginButton;

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
                getClass().getClassLoader().getResource("LauncherLoginGUI.fxml"));
        fxmlLoader.setController(this);
        AnchorPane pane = fxmlLoader.load();

        // Configure Window
        primaryStage.setTitle("Login - " + LauncherConstants.LAUNCHER_SHORT_NAME);
        primaryStage.setScene(new Scene(pane, 645, 424));
        primaryStage.initStyle(StageStyle.UNIFIED);

        // Configure enter button on text fields
        emailField.setOnAction(event -> loginButton.fire());
        passwordField.setOnAction(event -> loginButton.fire());

        // Show Window
        currStage = primaryStage;
        primaryStage.show();
        readyLatch.countDown();
    }

    public void handleIncorrectLogin() {
        // Clear password field and show try again text
        Platform.runLater(() -> {
            passwordField.clear();
            loginButton.setText(BAD_LOGIN_TEXT);
        });

        // Schedule login button text to return to normal after 5 minutes
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(() -> loginButton.setText(REG_LOGIN_TEXT));

        }).start();
    }

    public Stage getCurrStage() {
        return currStage;
    }

    @Override
    public void initialize(final URL url, final ResourceBundle resourceBundle) {
    }
}
