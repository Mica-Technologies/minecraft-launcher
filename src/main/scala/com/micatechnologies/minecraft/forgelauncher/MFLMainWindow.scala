package com.micatechnologies.minecraft.forgelauncher

import java.awt.Desktop
import java.io.{IOException, InputStream}
import java.net.{URI, URL, URLConnection}

import com.jfoenix.controls.{JFXButton, JFXComboBox}
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.fxml.{FXML, FXMLLoader}
import javafx.scene.control.Label
import javafx.scene.image.ImageView
import javafx.scene.layout.GridPane
import javafx.stage.WindowEvent

class MFLMainWindow() extends MFLWindow {
  // FXML window components
  @FXML var packList: JFXComboBox[String] = _
  @FXML var userMsg: Label = _
  @FXML var playBtn: JFXButton = _
  @FXML var settingsButton: JFXButton = _
  @FXML var logoutBtn: JFXButton = _
  @FXML var userIcon: ImageView = _
  @FXML var updateImgView: ImageView = _
  @FXML var packLogo: ImageView = _
  @FXML var centerPane: GridPane = _
  @FXML var topPane: GridPane = _
  @FXML var bottomPane: GridPane = _

  // Overridden methods
  override def getSize: Array[Int] = Array(600, 400)

  override def getFXMLLoader: FXMLLoader = {
    var fxmlLoader: FXMLLoader = new FXMLLoader()
    fxmlLoader.setLocation(getClass.getClassLoader.getResource("MFLMainUI.fxml"))
    fxmlLoader.setController(this)
    fxmlLoader
  }

  override def onSetup(): Unit = {
    // Verify that stage has been setup
    if (jfxStage == null) throw new IllegalStateException("Expected non-null stage during setup is null!")

    // Set stage close behavior
    jfxStage.setOnCloseRequest((_: WindowEvent) => {
      new Thread(() => {
        Platform.setImplicitExit(true)
        System.exit(0)
      }).start()
    })


  }

  // Methods
  def compareVersion(version1: String, version2: String): Int = {
    val arr1 = version1.split("\\.")
    val arr2 = version2.split("\\.")

    // Compare versions
    var i = 0
    while (i < arr1.length || i < arr2.length) {
      if (i < arr1.length && i < arr2.length) {
        if (arr1(i).toInt < arr2(i).toInt) return -1
        else if (arr1(i).toInt > arr2(i).toInt) return 1
      }
      else if (i < arr1.length) {
        if (arr1(i).toInt != 0) return 1
      }
      else {
        if (arr2(i).toInt != 0) return -1
      }
      i += 1
    }

    // Zero otherwise, same
    0
  }
}
