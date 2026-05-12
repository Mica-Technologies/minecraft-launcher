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

import com.google.gson.JsonObject;
import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.ActivityType;
import com.jagrosh.discordipc.entities.Packet;
import com.jagrosh.discordipc.entities.PartyPrivacy;
import com.jagrosh.discordipc.entities.RichPresence;
import com.jagrosh.discordipc.entities.User;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

public class DiscordRpcUtility
{
    /**
     * Discord application client ID. The production launcher and the dev launcher currently
     * share the same Discord app — a separate dev app would let dev sessions show up as their
     * own identity (and keep dev join-secret traffic out of the prod app's analytics) but
     * isn't required for RPC to work. If a dev app is set up later, gate this on
     * {@link LauncherConstants#LAUNCHER_IS_DEV}.
     */
    private static final long CLIENT_ID = 841860482029846528L;

    /**
     * Discord enforces a 128-byte limit on the joinSecret field. A typical modpack manifest
     * URL plus the {@code mmcl://join?url=...} envelope fits comfortably; pathologically long
     * URLs (e.g. with deep query strings) would silently truncate or be rejected — checked
     * here so we skip the party fields rather than send a broken presence.
     */
    private static final int DISCORD_SECRET_MAX_BYTES = 128;

    private static IPCClient discordRpcClient = null;

    /**
     * Stable per-launch party ID. Discord groups RPC users by partyId for the "X is in a
     * party of 1/4" UI; one ID per launcher process makes the user's status look consistent
     * across screen transitions instead of churning every time the presence updates.
     */
    private static final String PARTY_ID = UUID.randomUUID().toString();

    /**
     * Most-recent join-enabled presence info, captured so we can re-send the presence with
     * party + join secret intact on every {@link #setRichPresence} call. The wrapping pieces
     * (state, details, images, timestamp) change frequently from screen-to-screen, but join
     * data is sticky for the duration of a play session.
     */
    private static volatile String currentJoinSecret = null;
    private static volatile int    currentPartySize  = 0;
    private static volatile int    currentPartyMax   = 0;

    private static void init() {
        if ( ConfigManager.getDiscordRpcEnable() ) {
            try {
                discordRpcClient = new IPCClient( CLIENT_ID );
                discordRpcClient.setListener( new IPCListener()
                {
                    @Override
                    public void onPacketSent( IPCClient ipcClient, Packet packet ) {

                    }

                    @Override
                    public void onPacketReceived( IPCClient ipcClient, Packet packet ) {

                    }

                    @Override
                    public void onActivityJoin( IPCClient ipcClient, String joinSecret )
                    {
                        // Discord's "Join Game" button hands us the joinSecret the host
                        // set on their own presence. We use the launcher's mmcl:// scheme
                        // as the wire format, so this is just a deep-link delivered via
                        // the Discord IPC channel instead of via the OS scheme handler.
                        // Routes through the same dispatcher as cold-start argv URIs.
                        Logger.logStd( "Discord RPC: received join request, dispatching " + joinSecret );
                        try {
                            if ( LauncherUriHandler.isLauncherUri( joinSecret ) ) {
                                LauncherUriHandler.handle( joinSecret );
                            }
                            else {
                                Logger.logWarningSilent(
                                        "Discord RPC join secret was not a recognized mmcl:// URI: " + joinSecret );
                            }
                        }
                        catch ( Throwable t ) {
                            Logger.logWarningSilent( "Failed to handle Discord RPC join secret." );
                            Logger.logThrowable( t );
                        }
                    }

                    @Override
                    public void onActivitySpectate( IPCClient ipcClient, String s ) {
                    }

                    @Override
                    public void onActivityJoinRequest( IPCClient ipcClient, String s, User user ) {
                        // "Ask to Join" support: the host receives a request from `user` and
                        // can accept/reject. Not implemented — would need a notification +
                        // confirmation prompt UI. Direct join via setJoinSecret is enough for
                        // the common "send this friend my invite link" case.
                    }

                    @Override
                    public void onReady( IPCClient client )
                    {
                        // ActivityType is required since DiscordIPC 0.10+ — RichPresence.toJson()
                        // dereferences it unconditionally, so an unset value NPEs.
                        RichPresence.Builder builder = new RichPresence.Builder();
                        builder.setActivityType( ActivityType.Playing )
                               .setState( "In Menus" )
                               .setDetails( "Loading" )
                               .setStartTimestamp( OffsetDateTime.now().toEpochSecond() )
                               .setLargeImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" )
                               .setSmallImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" );
                        client.sendRichPresence( builder.build() );
                    }

                    @Override
                    public void onClose( IPCClient ipcClient, JsonObject jsonObject ) {

                    }

                    @Override
                    public void onDisconnect( IPCClient ipcClient, Throwable throwable ) {

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
                    // ActivityType is required since DiscordIPC 0.10+ (see onReady note).
                    RichPresence.Builder builder = new RichPresence.Builder();
                    builder.setActivityType( ActivityType.Playing )
                           .setState( state )
                           .setDetails( details )
                           .setStartTimestamp( startTimestamp.toEpochSecond() )
                           .setLargeImage( largeImageKey, largeImageText )
                           .setSmallImage( smallImageKey, smallImageText );

                    // Re-apply the most recent join party data on every presence update so
                    // the "Join Game" button stays available as the user moves between
                    // screens. Cleared by setMenuPresence so menu states don't show as
                    // joinable.
                    String joinSecret = currentJoinSecret;
                    if ( joinSecret != null ) {
                        builder.setParty( PARTY_ID, currentPartySize, currentPartyMax, PartyPrivacy.Public );
                        builder.setJoinSecret( joinSecret );
                    }

                    discordRpcClient.sendRichPresence( builder.build() );
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Unable to update Discord rich presence!" );
                    Logger.logThrowable( e );
                }
            }
        }
    }

    /**
     * Sets the rich presence to a standard "In Menus" state with the specified screen detail.
     * Also clears any in-flight join party data — friends shouldn't see a "Join Game" button
     * for a user who's just sitting in the launcher menus.
     *
     * @param screenName the screen name to display (e.g., "Selecting a Mod Pack", "Settings")
     *
     * @since 2.0
     */
    public static void setMenuPresence( String screenName )
    {
        clearJoinParty();
        setRichPresence( "In Menus", screenName, OffsetDateTime.now(),
                          "mica_minecraft_launcher", "Mica Minecraft Launcher",
                          "clipboard", screenName );
    }

    /**
     * Sets the rich presence to an "In Game" state for the specified modpack. If the modpack uses custom Discord RPC,
     * this method exits the launcher's RPC connection so the game's own RPC can take over.
     *
     * @param packName        the modpack name
     * @param customDiscordRpc true if the modpack provides its own Discord RPC
     *
     * @since 2.0
     */
    public static void setGamePresence( String packName, boolean customDiscordRpc )
    {
        setGamePresence( packName, customDiscordRpc, null );
    }

    /**
     * Sets the rich presence to an "In Game" state for the specified modpack, with an optional
     * join-invite payload that lets Discord friends click the "Join Game" button on the user's
     * presence to install + launch the same modpack on their own machine.
     *
     * <p>The join secret is delivered to the receiving launcher's Discord IPC client as-is —
     * we encode it as an {@code mmcl://join?url=...} URI so the receiver can route it through
     * {@link LauncherUriHandler} (the same dispatcher used by argv-delivered scheme links
     * from the website). If the modpack uses its own custom Discord RPC, this method exits
     * the launcher's RPC client so the game can take over (no party/invite data).</p>
     *
     * @param packName         the modpack name
     * @param customDiscordRpc true if the modpack provides its own Discord RPC
     * @param manifestUrl      the modpack's manifest URL, used to build the join invite — pass
     *                         {@code null} to disable the join button for this presence
     *
     * @since 3.4
     */
    public static void setGamePresence( String packName, boolean customDiscordRpc, String manifestUrl )
    {
        if ( customDiscordRpc ) {
            exit();
            return;
        }

        // Stage the join party data so setRichPresence picks it up. Built only when:
        //   - the user has Discord invites enabled in settings, AND
        //   - a manifest URL is available, AND
        //   - the resulting joinSecret fits Discord's 128-byte limit
        // Long URLs and disabled-invites states both degrade gracefully to a join-less
        // presence rather than getting truncated or fighting the user's preference.
        if ( ConfigManager.getDiscordInvitesEnable()
                && manifestUrl != null && !manifestUrl.isBlank() ) {
            String secret = buildJoinSecret( manifestUrl );
            if ( secret != null ) {
                currentJoinSecret = secret;
                currentPartySize = 1;
                // Discord requires partyMax > partySize for the "Join" button to render. Pick a
                // small but plausible cap — modpacks don't impose an actual ceiling on coplayers,
                // but Discord's UI needs a finite number to show "1/N" alongside the presence.
                currentPartyMax = 4;
            }
            else {
                Logger.logWarningSilent( "Discord join secret would exceed " + DISCORD_SECRET_MAX_BYTES
                                                 + " bytes — disabling Join button for this pack." );
                clearJoinParty();
            }
        }
        else {
            clearJoinParty();
        }

        setRichPresence( "In Game (Minecraft)", "Mod Pack: " + packName, OffsetDateTime.now(),
                          "mica_minecraft_launcher", "Mica Minecraft Launcher", "game", "In Game" );
    }

    /**
     * Convenience overload that pulls the manifest URL straight off a {@link GameModPack}.
     *
     * @since 3.4
     */
    public static void setGamePresence( GameModPack pack )
    {
        if ( pack == null ) return;
        setGamePresence( pack.getPackName(), pack.getCustomDiscordRpc(), pack.getManifestUrl() );
    }

    /** Clears in-flight join party data — friends won't see a "Join Game" button on the next
     *  presence update. */
    private static void clearJoinParty()
    {
        currentJoinSecret = null;
        currentPartySize = 0;
        currentPartyMax = 0;
    }

    /**
     * Builds the {@code mmcl://join?url=...} join secret for a modpack manifest URL, returning
     * {@code null} if the resulting secret would exceed Discord's 128-byte joinSecret limit.
     */
    private static String buildJoinSecret( String manifestUrl )
    {
        String secret = buildInviteLink( manifestUrl );
        if ( secret == null ) return null;
        if ( secret.getBytes( StandardCharsets.UTF_8 ).length > DISCORD_SECRET_MAX_BYTES ) {
            return null;
        }
        return secret;
    }

    /**
     * Builds the {@code mmcl://join?url=...} deep-link the user can hand to a friend (paste
     * in Discord chat, share elsewhere) to install + launch a modpack on their machine.
     * Same wire format as the Discord joinSecret but without the 128-byte length cap —
     * non-Discord channels don't have that restriction.
     *
     * @param manifestUrl the modpack's manifest URL
     *
     * @return the invite URL, or {@code null} if the manifest URL is null/blank
     *
     * @since 3.4
     */
    public static String buildInviteLink( String manifestUrl )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() ) return null;
        String encoded = URLEncoder.encode( manifestUrl, StandardCharsets.UTF_8 );
        return LauncherUriHandler.SCHEME + "://join?url=" + encoded;
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
