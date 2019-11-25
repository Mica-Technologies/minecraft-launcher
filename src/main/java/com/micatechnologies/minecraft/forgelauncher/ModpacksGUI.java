package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXComboBox;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;

/**
 * GUI for logged in users. Allows settings button, modpack selection, play button, etc.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class ModpacksGUI extends MCFLGenericGUI {

    @FXML
    JFXComboBox<String> packList;

    @FXML
    public Label userMsg;

    @FXML
    public Button playBtn;

    @FXML
    public Button exitBtn;

    @FXML
    public Button settingsBtn;

    @FXML
    public Button logoutBtn;

    @FXML
    public ImageView userIcon;

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     */
    @Override
    void create() {
        // Configure exit button
        exitBtn.setOnAction(event -> System.exit(0));

        // Configure settings button
        settingsBtn.setOnAction(event -> {
            new Thread(() -> {
                SettingsGUI settingsGUI = new SettingsGUI();
                settingsGUI.open();
                settingsGUI.getCurrentStage().initModality(Modality.APPLICATION_MODAL);
                settingsGUI.getCurrentStage().initOwner(this.getCurrentStage());
            }).start();
        });

        // TODO: Configure Log Out Button
        // TODO: Populate Modpacks Dropdown
        // TODO: Configure Play Button
    }

    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation(getClass().getClassLoader().getResource("LauncherModpackGUI.fxml"));
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
        ModpacksGUI mpg = new ModpacksGUI();
        mpg.open();
    }
}
