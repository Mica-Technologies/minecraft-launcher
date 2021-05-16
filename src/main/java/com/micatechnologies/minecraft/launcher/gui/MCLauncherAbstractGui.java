/*
 * Copyright (c) 2021 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * Abstract class for JavaFX scenes in the Mica Minecraft Launcher
 *
 * @since 2020.1
 */
public abstract class MCLauncherAbstractGui
{
    /**
     * The JavaFX scene object which is created from the FXML file specified by {@link #getSceneFxmlPath()}.
     */
    final Scene scene;

    final Stage stage;

    /**
     * Root pane of the scene.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Pane rootPane;

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherAbstractGui( Stage stage ) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader();
        URL resource = getClass().getClassLoader().getResource( getSceneFxmlPath() );
        fxmlLoader.setLocation( resource );
        fxmlLoader.setController( this );
        if ( stage.getScene() != null ) {
            scene = new Scene( fxmlLoader.load(), stage.getScene().getWidth(), stage.getScene().getHeight() );
        }
        else {
            scene = new Scene( fxmlLoader.load() );
        }
        this.stage = stage;
    }

    public void hideStage() {
        GUIUtilities.JFXPlatformRun( stage::hide );
    }

    /**
     * Abstract method: This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    abstract String getSceneFxmlPath();

    /**
     * Abstract method: This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    abstract String getSceneName();

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @FXML
    abstract void setup();

    abstract void afterShow();
}

