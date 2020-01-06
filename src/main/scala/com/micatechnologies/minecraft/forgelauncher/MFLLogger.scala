package com.micatechnologies.minecraft.forgelauncher

import javafx.stage.Stage
import javax.annotation.Nullable

object MFLLogger {

  def logDebug(message: String): Unit = System.out.println("[" + MFLConstants.LAUNCHER_APPLICATION_NAME + "/DEBUG] " + message)

  def logStd(message: String): Unit = System.out.println("[" + MFLConstants.LAUNCHER_APPLICATION_NAME + "/LOG] " + message)

  def logError(message: String, errorID: Int, @Nullable owner: Stage): Unit = {
    // Generate a full error code with given error ID
    // TODO: Generate full error code
    val logErrorCode: String = "0x0000" + errorID

    // Output to system IO
    System.err.println("[" + MFLConstants.LAUNCHER_APPLICATION_NAME + "/ERROR] " + message)

    // Output to GUI if stage not null
    if (owner != null) return// TODO: Call GUI Controller to show error message
  }
}
