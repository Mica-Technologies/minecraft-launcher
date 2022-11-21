/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.social

import com.google.gson.JsonObject
import com.jagrosh.discordipc.entities.{Packet, RichPresence, User}
import com.jagrosh.discordipc.{IPCClient, IPCListener}
import com.micatechnologies.minecraft.launcher.config.ConfigManager
import com.micatechnologies.minecraft.launcher.files.Logger

import java.time.OffsetDateTime

/**
 * Social integration management class for Discord Rich Presence.
 *
 * @since 2023.1
 * @version 2.0.0
 * @author Mica Technologies
 */
object DiscordRichPresence {

  /**
   * The client ID of the Discord application used for Discord Rich Presence integration.
   *
   * @since 1.0.0
   */
  private val CLIENT_ID = 841860482029846528L

  /**
   * The Discord IPC client used for Discord Rich Presence integration.
   *
   * @since 1.0.0
   */
  private var discordRichPresenceClient: IPCClient = _

  /**
   * Initializes the Discord Rich Presence integration and starts the Discord IPC client.
   *
   * @since 1.0.0
   */
  private def init(): Unit = {
    if (ConfigManager.getDiscordRpcEnable) {
      try {
        discordRichPresenceClient = new IPCClient(CLIENT_ID)
        discordRichPresenceClient.setListener(new IPCListener() {
          override def onPacketSent(ipcClient: IPCClient, packet: Packet): Unit = {
          }

          override def onPacketReceived(ipcClient: IPCClient, packet: Packet): Unit = {
          }

          override def onActivityJoin(ipcClient: IPCClient, s: String): Unit = {
          }

          override def onActivitySpectate(ipcClient: IPCClient, s: String): Unit = {
          }

          override def onActivityJoinRequest(ipcClient: IPCClient, s: String, user: User): Unit = {
          }

          override def onReady(client: IPCClient): Unit = {
            val builder = new RichPresence.Builder
            builder.setState("In Menus").setDetails("Loading").setStartTimestamp(OffsetDateTime.now.toEpochSecond).setLargeImage("mica_minecraft_launcher", "Mica Minecraft Launcher").setSmallImage("mica_minecraft_launcher", "Mica Minecraft Launcher")
            client.sendRichPresence(builder.build)
          }

          override def onClose(ipcClient: IPCClient, jsonObject: JsonObject): Unit = {
          }

          override def onDisconnect(ipcClient: IPCClient, throwable: Throwable): Unit = {
          }
        })
        discordRichPresenceClient.connect()
      } catch {
        case e: Exception =>
          Logger.logWarningSilent("Unable to setup Discord rich presence!")
          Logger.logThrowable(e)
      }
    }
  }

  /**
   * Updates the Discord RPC/IPC client with the current state of the launcher.
   *
   * @param state          the current state of the launcher
   * @param details        the details of the current state of the launcher
   * @param startTimestamp the start timestamp of the current state of the launcher
   * @param largeImageKey  the large image key of the current state of the launcher
   * @param largeImageText the large image text of the current state of the launcher
   * @param smallImageKey  the small image key of the current state of the launcher
   * @param smallImageText the small image text of the current state of the launcher
   * @since 1.0.1
   */
  def setRichPresence(state: String, details: String, startTimestamp: OffsetDateTime, largeImageKey: String, largeImageText: String, smallImageKey: String, smallImageText: String): Unit = {
    if (ConfigManager.getDiscordRpcEnable) {
      // Init if required
      if (discordRichPresenceClient == null) init()

      // Set rich presence if possible
      if (discordRichPresenceClient != null) try {
        val builder = new RichPresence.Builder
        builder.setState(state).setDetails(details).setStartTimestamp(startTimestamp.toEpochSecond).setLargeImage(largeImageKey, largeImageText).setSmallImage(smallImageKey, smallImageText)
        discordRichPresenceClient.sendRichPresence(builder.build)
      } catch {
        case e: Exception =>
          Logger.logWarningSilent("Unable to update Discord rich presence!")
          Logger.logThrowable(e)
      }
    }
  }

  /**
   * Shuts down and exists the Discord RPC/IPC client.
   *
   * @since 1.0.1
   */
  def exit(): Unit = {
    if (discordRichPresenceClient != null) try {
      discordRichPresenceClient.close()
      discordRichPresenceClient = null
    } catch {
      case e: Exception =>
        Logger.logWarningSilent("An exception occurred while exiting the Discord rich presence client!")
        Logger.logThrowable(e)
    }
  }
}
