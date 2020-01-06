package com.micatechnologies.minecraft.forgelauncher

import java.util.concurrent.CountDownLatch

import com.micatechnologies.jadapt.NSWindow
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils
import com.sun.glass.ui.Window
import javafx.application.{Application, Platform}
import javafx.event.EventHandler
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.{Stage, WindowEvent}

import scala.jdk.CollectionConverters._
import org.apache.commons.lang3.SystemUtils
import org.rococoa.{ID, Rococoa}

trait MFLWindow extends Application {
  // Internal JavaFX Variables
  protected var jfxStage: Stage = _

  // Internal Threading Control Variables
  protected var windowReady: CountDownLatch = new CountDownLatch(1)
  protected var windowClosed: CountDownLatch = new CountDownLatch(1)

  // Getter for JavaFX Stage
  def getJFXStage: Stage = jfxStage

  // Abstract/Derivation Methods
  def getSize: Array[Int]

  def getFXMLLoader: FXMLLoader

  def onSetup(): Unit

  // Methods
  private def open(): Unit = {
    // Attempt to open GUI using JavaFX Platform runLater, and fallback to startup on fail
    try {
      Platform.runLater(() => {
        try {
          start(new Stage())
        }
        catch {
          case _: Throwable => {
            MFLLogger.logError("An error occurred while preparing a user interface component.", 100, null)
            Platform.setImplicitExit(true)
          }
        }
      })
    }
    catch {
      case _: Throwable => {
        Platform.startup(() => {
          try {
            start(new Stage())
          }
          catch {
            case _: Throwable => {
              MFLLogger.logError("An error occurred while preparing a user interface component.", 101, null)
              Platform.setImplicitExit(true)
            }
          }
        })
      }
    }
  }

  def show(): Unit = {
    // Verify that stage has been setup, setup if not, return if still not setup
    if (jfxStage == null) open()
    if (jfxStage == null) throw new IllegalStateException("Application initialization yielded unexpected or incomplete setup!")

    // Configure stage
    jfxStage.show()
    jfxStage.setResizable(MCFLApp.getLauncherConfig.getResizableguis)
    setNativeMacWindowStyle
    jfxStage.setOnShown((_: WindowEvent) => {
      jfxStage.toFront()
      jfxStage.requestFocus()
    })
  }

  def hide(): Unit = {
    // Cleanup fullscreen and hide stage
    Platform.runLater(() => {
      // Verify that stage has been setup
      if (jfxStage == null) throw new IllegalStateException("User interface state cannot be modified while stage is not initialized!")

      // Close fullscreen if applicable
      jfxStage.setFullScreen(false)
      // TODO: Mac Specific Fullscreen Code

      // Hide stage
      jfxStage.hide()
    })
  }

  def close(): Unit = {
    // Cleanup fullscreen and hide stage
    Platform.runLater(() => {
      // Verify that stage has been setup
      if (jfxStage == null) throw new IllegalStateException("User interface state cannot be modified while stage is not initialized!")

      // Close fullscreen if applicable
      jfxStage.setFullScreen(false)
      // TODO: Mac Specific Fullscreen Code

      // Hide stage
      jfxStage.close()
      windowClosed.countDown()
      jfxStage = null
    })
  }

  def getNativeMacWindow: NSWindow = {
    // Verify that stage has been setup
    if (jfxStage == null) throw new IllegalStateException("User interface cannot be wrapped while stage is not initialized!")

    // Return null if not on macOS
    if (!SystemUtils.IS_OS_MAC) return null

    // Try to get native window handle and wrapper
    try {
      // Find this window in list of all windows, if can't attempt fallback on index 0
      var glassWindow: Window = Window.getWindows.get(0)
      for (loopGlassWindow <- Window.getWindows.asScala) {
        if (loopGlassWindow.getX == jfxStage.getX && loopGlassWindow.getY == jfxStage.getY) glassWindow = loopGlassWindow
      }

      // Build and return wrapped native window
      Rococoa.wrap(ID.fromLong(glassWindow.getNativeWindow), classOf[NSWindow])
    }
    catch {
      case _: Throwable => {
        // Show error and return null
        MFLLogger.logError("An error occurred while accessing the native macOS window handle.", 102, jfxStage)
        null
      }
    }
  }

  def setNativeMacWindowStyle: Unit = {
    // TODO: Configure macOS unified window style
  }

  def applyLightMode(): Unit = {
    // Add light mode CSS and remove dark mode CSS
    Platform.runLater(() => {
      // Verify that stage has been setup
      if (jfxStage == null) throw new IllegalStateException("User interface display (light/dark) mode cannot be applied while stage is not initialized!")

      jfxStage.getScene.getStylesheets.add(getClass.getClassLoader.getResource("LauncherLight.css").toExternalForm)
      jfxStage.getScene.getStylesheets.remove(getClass.getClassLoader.getResource("LauncherDark.css").toExternalForm)
    })
  }

  def applyDarkMode(): Unit = {
    // Remove light mode CSS and add dark mode CSS
    Platform.runLater(() => {
      // Verify that stage has been setup
      if (jfxStage == null) throw new IllegalStateException("User interface display (light/dark) mode cannot be applied while stage is not initialized!")

      jfxStage.getScene.getStylesheets.remove(getClass.getClassLoader.getResource("LauncherLight.css").toExternalForm)
      jfxStage.getScene.getStylesheets.add(getClass.getClassLoader.getResource("LauncherDark.css").toExternalForm)
    })
  }

  override def start(stage: Stage): Unit = {
    // Configure stage title (none on macOS)
    if (MCModpackOSUtils.isMac) stage.setTitle("")
    else stage.setTitle(MFLConstants.LAUNCHER_APPLICATION_NAME)

    // Set scene and configure
    stage.setScene(new Scene(getFXMLLoader.load(), getSize(0), getSize(1)))
    stage.setOnShown((_: WindowEvent) => stage.requestFocus())
    stage.setMinWidth(getSize(0))
    stage.setMinHeight(getSize(1))
    stage.setWidth(getSize(0))
    stage.setHeight(getSize(1))

    // Store stage internally
    jfxStage = stage

    // Register window as ready
    windowReady.countDown()

    // Run derivation class setup code
    onSetup()

    // Show window
    show()
  }
}
