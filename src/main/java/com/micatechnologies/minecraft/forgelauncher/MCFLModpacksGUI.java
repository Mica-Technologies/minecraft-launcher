package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLGUIUtils;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpack;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackConsts;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLLogUtil;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * GUI for logged in users. Allows settings button, modpack selection, play button, etc.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLModpacksGUI extends MCFLGenericGUI {

    /**
     * Root window pane
     */
    @FXML
    public AnchorPane rootPane;

    /**
     * Combo box for choosing modpack
     */
    @FXML
    JFXComboBox< String > packList;

    /**
     * User label/message text
     */
    @FXML
    public Label userMsg;

    /**
     * Play button
     */
    @FXML
    public JFXButton playBtn;

    /**
     * Settings button
     */
    @FXML
    public JFXButton settingsButton;

    /**
     * Logout button
     */
    @FXML
    public Button logoutBtn;

    /**
     * User icon image
     */
    @FXML
    public ImageView userIcon;

    /**
     * image for launcher update available
     */
    @FXML
    public ImageView updateImgView;

    /**
     * Current modpack logo
     */
    @FXML
    public ImageView packLogo;

    /**
     * Pane containing modpack selection dropdown
     */
    @FXML
    public GridPane modpackSelPane;

    /**
     * Pane in the center for displaying modpack
     */
    @FXML
    public GridPane centerPane;

    /**
     * Pane in the top
     */
    @FXML
    public GridPane topPane;

    /**
     * Pane in the bottom
     */
    @FXML
    public GridPane bottomPane;

    public int compareVersion( String version1, String version2 ) {
        String[] arr1 = version1.split( "\\." );
        String[] arr2 = version2.split( "\\." );

        int i = 0;
        while ( i < arr1.length || i < arr2.length ) {
            if ( i < arr1.length && i < arr2.length ) {
                if ( Integer.parseInt( arr1[ i ] ) < Integer.parseInt( arr2[ i ] ) ) {
                    return -1;
                }
                else if ( Integer.parseInt( arr1[ i ] ) > Integer.parseInt( arr2[ i ] ) ) {
                    return 1;
                }
            }
            else if ( i < arr1.length ) {
                if ( Integer.parseInt( arr1[ i ] ) != 0 ) {
                    return 1;
                }
            }
            else {
                if ( Integer.parseInt( arr2[ i ] ) != 0 ) {
                    return -1;
                }
            }

            i++;
        }

        return 0;
    }

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     *
     * @since 1.0
     */
    @Override
    void create( Stage stage ) {
        // Configure exit button
        stage.setOnCloseRequest( event -> {
            new Thread( () -> {
                Platform.setImplicitExit( true );
                System.exit( 0 );
            } ).start();
        } );

        // Check for launcher update and show image if there is one
        updateImgView.setVisible( false );
        new Thread( () -> {
            try {
                // Get current version
                Package p = getClass().getPackage();
                String version = p.getImplementationVersion();

                // Get latest version
                URLConnection con = new URL( MCFLConstants.UPDATE_CHECK_REDIRECT_URL ).openConnection();
                con.connect();
                InputStream is = con.getInputStream();
                String latestVersionURL = con.getURL().toExternalForm();
                String[] latestVersionURLParts = latestVersionURL.split( "/" );
                String latestVersion = latestVersionURLParts[ latestVersionURLParts.length - 1 ];
                is.close();

                // Check if current version is less than latest
                if ( compareVersion( version, latestVersion ) == -1 ) {
                    updateImgView.setVisible( true );
                    updateImgView.setOnMouseClicked( mouseEvent -> {
                        new Thread( () -> {
                            int response = FLGUIUtils.showQuestionMessage( "Update Available", "Update Ready to Download", "An update has been found and is ready to be downloaded and installed.", "Update Now", "Update Later", getCurrentStage() );
                            if ( response == 1 ) {
                                try {
                                    Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                                }
                                catch ( IOException e ) {
                                    FLLogUtil.error( "Unable to open your browser. Please visit " + latestVersionURL + " to download the latest launcher updates!", -80, stage );
                                }
                            }
                        } ).start();
                    } );
                }
            }
            catch ( Exception e ) {

            }
        } ).start();

        // Configure settings button
        settingsButton.setOnAction( event -> {
            new Thread( () -> {
                Platform.setImplicitExit( false );
                hide();

                // Open settings GUI and disable main window
                MCFLSettingsGUI MCFLSettingsGUI = new MCFLSettingsGUI();
                MCFLSettingsGUI.open();

                // Wait for settings to close, then enable main window again
                new Thread( () -> {
                    try {
                        MCFLSettingsGUI.closedLatch.await();
                    }
                    catch ( InterruptedException e ) {
                    }

                    // If loop login is true, launcher reset, need to go to login screen
                    if ( MCFLApp.getLoopLogin() ) {
                        new Thread( () -> {
                            Platform.setImplicitExit( true );
                            close();
                        } ).start();
                    }

                    MCFLApp.buildMemoryModpackList();
                    show();
                    populateModpacksDropdown();
                } ).start();

            } ).start();
        } );

        // Configure logout button
        logoutBtn.setOnAction( event -> new Thread( () -> {
            MCFLApp.logoutCurrentUser();
            Platform.setImplicitExit( true );
            close();
        } ).start() );

        // Populate modpacks dropdown
        populateModpacksDropdown();

        // Configure play button
        playBtn.setOnAction( event -> new Thread( () -> {
            Platform.setImplicitExit( false );
            hide();
            MCFLApp.play( packList.getSelectionModel().getSelectedIndex(), this );
            show();
        } ).start() );

        // Configure user image
        userIcon.setImage( new Image( MCFLConstants.URL_MINECRAFT_USER_ICONS.replace( "user", MCFLApp.getCurrentUser().getUserIdentifier() ) ) );

        // Configure user label
        userMsg.setText( MCFLApp.getCurrentUser().getFriendlyName() );

        // Configure ENTER key to press play
        rootPane.setOnKeyPressed( event -> {
            if ( event.getCode() == KeyCode.ENTER ) {
                event.consume();
                playBtn.fire();
            }
        } );
    }

    public static Color averageColor( Image bi, int x0, int y0, int w,
                                      int h ) {
        PixelReader pixelReader = bi.getPixelReader();
        int x1 = x0 + w;
        int y1 = y0 + h;
        double sumr = 0, sumg = 0, sumb = 0;
        double pixCount = 0;
        for ( int x = x0; x < x1; x += 5 ) {
            for ( int y = y0; y < y1; y += 5 ) {

                Color pixel = pixelReader.getColor( x, y );
                if ( pixel.getOpacity() == 1.0 ) {
                    sumr += pixel.getRed();
                    sumg += pixel.getGreen();
                    sumb += pixel.getBlue();
                    pixCount++;
                }
            }
        }
        return new Color( sumr / pixCount, sumg / pixCount, sumb / pixCount, 1.0 );
    }

    private ChangeListener< Number > packSelectChangeListener = new ChangeListener<>() {
        @Override
        public void changed( ObservableValue< ? extends Number > observable, Number oldValue, Number newValue ) {
            // Get pack logo and set in GUI
            Image packLogoImg = new Image( MCFLApp.getModpacks().get( packList.getSelectionModel().getSelectedIndex() == -1 ? 0 : packList.getSelectionModel().getSelectedIndex() ).getPackLogoURL() );
            packLogo.setImage( packLogoImg );

            // Get theme color from pack logo
            Color themeColor = averageColor( packLogoImg, 0, 0, ( int ) packLogoImg.getWidth(), ( int ) packLogoImg.getHeight() );

            // Set GUI light/dark depending on theme color
            if ( themeColor.getBrightness() > 0.87 ) doLightMode();
            else doDarkMode();

            // Set theme color to top and bottom panes
            topPane.setBackground( new Background( new BackgroundFill( themeColor, CornerRadii.EMPTY, Insets.EMPTY ) ) );
            bottomPane.setBackground( new Background( new BackgroundFill( themeColor, CornerRadii.EMPTY, Insets.EMPTY ) ) );

            // Set pack background image to center pane
            centerPane.setStyle( centerPane.getStyle() + "-fx-background-image: url('" + MCFLApp.getModpacks().get( packList.getSelectionModel().getSelectedIndex() == -1 ? 0 : packList.getSelectionModel().getSelectedIndex() ).getPackBackgroundURL() + "');" );
            centerPane.setStyle( centerPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
        }
    };

    private void populateModpacksDropdown() {
        // Create list of modpack names
        List< String > modpacksList = new ArrayList<>();
        for ( MCForgeModpack modpack : MCFLApp.getModpacks() ) {
            modpacksList.add( modpack.getPackName() );
        }

        // Reset modpack selector
        packList.getSelectionModel().selectedIndexProperty().removeListener( packSelectChangeListener );
        packList.getItems().clear();

        // If modpacks list not empty, add to modpack selector
        if ( modpacksList.size() > 0 ) {
            Platform.runLater( () -> {
                packList.setDisable( false );
                packList.getItems().addAll( modpacksList );
                packList.getSelectionModel().selectedIndexProperty().addListener( packSelectChangeListener );
                packList.getSelectionModel().selectFirst();
            } );
        }
        // If no modpacks, show message
        else {
            Platform.runLater( () -> {
                packList.getItems().add( "No configured modpacks!" );
                packList.getSelectionModel().selectFirst();
                packList.setDisable( true );
                packLogo.setImage( new Image( MCFLConstants.URL_MINECRAFT_NO_MODPACK_IMAGE ) );
                centerPane.setStyle( centerPane.getStyle() + "-fx-background-image: url('" + MCForgeModpackConsts.MODPACK_DEFAULT_BG_URL + "');" );
                centerPane.setStyle( centerPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
            } );
        }
    }


    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     *
     * @since 1.0
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation( getClass().getClassLoader().getResource( "MFLMainUI.fxml" ) );
        fxmll.setController( this );
        return fxmll;
    }

    /**
     * Get the width and height of the JavaFX stage
     *
     * @return [width, height] of JavaFX stage
     *
     * @since 1.0
     */
    @Override
    int[] getSize() {
        return new int[]{ 600, 400 };
    }

    @Override
    void enableLightMode() {
    }

    @Override
    void enableDarkMode() {
    }

    /**
     * Open the GUI and automatically select the modpack index supplied
     *
     * @param modpackIndex modpack index
     */
    public void open( int modpackIndex ) {
        // Open GUI
        super.open();

        // Wait for GUI to be ready
        if ( readyLatch.getCount() > 0 ) {
            try {
                readyLatch.await();
            }
            catch ( InterruptedException ignored ) {
            }
        }

        // Select first (as backup), then select supplied index
        packList.getSelectionModel().selectFirst();
        packList.getSelectionModel().select( modpackIndex );
    }

    @Override
    Pane getRootPane() {
        return rootPane;
    }

    @Override
    void onShow() {
        // Do not allow style thread
        styleThreadRun = false;

        // Refresh buttons
        playBtn.requestLayout();
        settingsButton.requestLayout();
    }
}
