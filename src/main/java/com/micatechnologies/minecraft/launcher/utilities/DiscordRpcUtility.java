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

package com.micatechnologies.minecraft.launcher.utilities;

import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.RichPresence;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;

import java.time.OffsetDateTime;

public class DiscordRpcUtility
{
    private static final long      CLIENT_ID        = 841860482029846528L;
    private static       IPCClient discordRpcClient = null;

    private static void init() {
        if ( ConfigManager.getDiscordRpcEnable() ) {
            try {
                discordRpcClient = new IPCClient( CLIENT_ID );
                discordRpcClient.setListener( new IPCListener()
                {
                    @Override
                    public void onReady( IPCClient client )
                    {
                        RichPresence.Builder builder = new RichPresence.Builder();
                        builder.setState( "In Menus" )
                               .setDetails( "Loading" )
                               .setStartTimestamp( OffsetDateTime.now() )
                               .setLargeImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" )
                               .setSmallImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" );
                        client.sendRichPresence( builder.build() );
                    }
                } );
                discordRpcClient.connect();
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to setup Discord rich presence!" );
                Logger.logThrowable( e );
            }
        }
    }

    public static void setRichPresence( String state,
                                        String details,
                                        OffsetDateTime startTimestamp,
                                        String largeImageKey,
                                        String largeImageText,
                                        String smallImageKey,
                                        String smallImageText )
    {
        if ( ConfigManager.getDiscordRpcEnable() ) {
            // Init if required
            if ( discordRpcClient == null ) {
                init();
            }

            // Set rich presence if possible
            if ( discordRpcClient != null ) {
                try {
                    RichPresence.Builder builder = new RichPresence.Builder();
                    builder.setState( state )
                           .setDetails( details )
                           .setStartTimestamp( startTimestamp )
                           .setLargeImage( largeImageKey, largeImageText )
                           .setSmallImage( smallImageKey, smallImageText );
                    discordRpcClient.sendRichPresence( builder.build() );
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Unable to update Discord rich presence!" );
                    Logger.logThrowable( e );
                }
            }
        }
    }

    public static void exit() {
        if ( discordRpcClient != null ) {
            try {
                discordRpcClient.close();
                discordRpcClient = null;
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "An exception occurred while exiting the Discord rich presence client!" );
                Logger.logThrowable( e );
            }
        }
    }
}
