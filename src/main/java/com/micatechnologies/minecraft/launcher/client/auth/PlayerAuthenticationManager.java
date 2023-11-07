package com.micatechnologies.minecraft.launcher.client.auth;

import net.hycrafthd.minecraft_authenticator.login.User;

/**
 * Player authentication manager for the client application of the Mica Minecraft Launcher.
 *
 * @author Mica Technologies
 * @version 2024.1
 * @since 2024.1
 */
public class PlayerAuthenticationManager {

  /**
   * The currently logged-in user. If no user is logged in, this will be {@code null}.
   *
   * @since 2024.1
   */
  private static User loggedIn = null;
}
