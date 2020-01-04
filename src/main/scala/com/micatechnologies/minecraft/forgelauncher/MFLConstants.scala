package com.micatechnologies.minecraft.forgelauncher

import java.nio.file.Paths

import scala.reflect.io.File

object MFLConstants {
  // LAUNCHER CONSTANTS - Server and Client
  val LAUNCHER_APPLICATION_NAME: String = "Mica Forge Launcher"

  // LAUNCHER CONSTANTS - Client Only
  val LAUNCHER_CLIENT_INSTALLATION_DIRECTORY: String = System.getProperty("user.home") + File.separator + LAUNCHER_APPLICATION_NAME.replaceAll(" ", "")
  val LAUNCHER_CLIENT_SAVED_USER_FILE: String = LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "user.cache"
  val LAUNCHER_CLIENT_SAVED_TOKEN_FILE: String = LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "token.cache"

  // LAUNCHER CONSTANTS - Server Only
  val LAUNCHER_SERVER_INSTALLATION_DIRECTORY: String = Paths.get("").toAbsolutePath.toString

  // JRE DOWNLOAD URLS
  private val JRE_DLD_URL_HASH_SUFFIX: String = ".sha256.txt"
  val JRE_DLD_URL_WIN: String = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip"
  val JRE_DLD_URL_MAC: String = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz"
  val JRE_DLD_URL_UNX: String = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz"
  val JRE_DLD_URL_WIN_HASH_APPEND: String = JRE_DLD_URL_WIN + JRE_DLD_URL_HASH_SUFFIX
  val JRE_DLD_URL_MAC_HASH_APPEND: String = JRE_DLD_URL_MAC + JRE_DLD_URL_HASH_SUFFIX
  val JRE_DLD_URL_UNX_HASH_APPEND: String = JRE_DLD_URL_UNX + JRE_DLD_URL_HASH_SUFFIX
  val JRE_DLD_EXTRACTED_NAME: String = "jdk8u232-b09-jre"

  // IMAGE URLS
  val URL_TEMPLATE_MINECRAFT_USER_ICON = "http://minotar.net/armor/bust/user/100.png"
  val URL_LAUNCHER_NO_MODPACK_IMAGE = "https://cdn.pixabay.com/photo/2016/11/11/14/49/minecraft-1816996_960_720.png"
}
