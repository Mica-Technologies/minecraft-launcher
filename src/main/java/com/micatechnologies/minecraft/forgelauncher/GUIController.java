package com.micatechnologies.minecraft.forgelauncher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;

import static com.micatechnologies.minecraft.forgelauncher.LauncherCore.getClientToken;

public class GUIController {
    static void showErrorMessage(String title, String headerText, String contentText, int errorID) {
        // Create an error code
        // 0x100234
        // 1 = Error ID
        // 2 = "D" for default Java path, "C" for changed Java path
        // 3 = "N" for no client token, "V" for valid client token
        // 4 = "N" for no loaded user, "V" for valid loaded user
        //TODO: Generate error with information method calls fix
        String generatedErrorCode = "000";//"0x" + errorID + "00" + (getJavaPath().equals("java") ? "D" : "C") + (getClientToken().equals("") ? "N" : "V") + (getCurrentUser() == null ? "N" : "V");

        // Create an error with the specified and created information/messages
        CountDownLatch waitForError = new CountDownLatch(1);
        Platform.runLater(() -> {
            Alert errorAlert = new Alert(Alert.AlertType.ERROR);
            errorAlert.setTitle(title);
            errorAlert.setHeaderText(headerText);
            errorAlert.setContentText(contentText + "\nError Code: " + generatedErrorCode + "\n" + "Client Token: " + getClientToken());

            // Show the created error
            errorAlert.showAndWait();

            // Release code from waiting
            waitForError.countDown();
        });

        // Wait for error to be acknowledged
        try {
            waitForError.await();
        } catch (InterruptedException e) {
            // Show error for unable to wait for error acknowledge
            Platform.runLater(() -> {
                Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                errorAlert.setTitle("Something's Wrong");
                errorAlert.setHeaderText("Application Error");
                errorAlert.setContentText("An error message latch was interrupted before handling completed." + "\n" + "Client Token: " + getClientToken());

                // Show the created error
                errorAlert.showAndWait();
            });
        }
    }
}
