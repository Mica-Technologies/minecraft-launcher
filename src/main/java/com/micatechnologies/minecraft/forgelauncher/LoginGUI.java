package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.*;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

/**
 * GUI for launcher login. Allows launcher login.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class LoginGUI extends MCFLGenericGUI {

    @FXML
    public JFXTextField emailField;

    @FXML
    public JFXPasswordField passwordField;

    @FXML
    public JFXCheckBox rememberCheckBox;

    @FXML
    public JFXButton loginButton;

    @FXML
    public JFXButton exitButton;

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     */
    @Override
    void create() {
        // TODO: Configure Email Field Mark Login Dirty
        // TODO: Configure Password Field Mark Login Dirty
        // TODO: Configure Remember Me Check Box
        // TODO: Configure Login Button
        // TODO: Configure Exit Button
        // TODO: Create Exit Button on GUI (null now)
    }

    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation(getClass().getClassLoader().getResource("LauncherLoginGUI.fxml"));
        fxmll.setController(this);
        return fxmll;
    }

    /**
     * Get the width and height of the JavaFX stage
     *
     * @return [width, height] of JavaFX stage
     */
    @Override
    int[] getSize() {
        return new int[]{500, 500};
    }

    public static void main(String[] args) {
        LoginGUI mpg = new LoginGUI();
        mpg.open();
    }
}
