package com.micatechnologies.minecraft.forgelauncher.auth;

import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientModeOnly;

import java.io.Serializable;

/**
 * Minecraft account object that can be safely serialized for write to or read from file using the built-in class
 * functions.
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @creator hawka97
 * @editors hawka97
 * @see java.io.Serializable
 * @since 1.0
 */
@ClientModeOnly
public class AuthAccount implements Serializable
{

    /**
     * Class serialization identifier. DO NOT MODIFY. Modification will invalidate all existing serialized instances.
     *
     * @since 1.0
     */
    private static final long serialVersionUID = 0x6f40b3a5d4216db6L;

    /**
     * Account name. For legacy Minecraft accounts, this should be the Minecraft username. For Mojang accounts, this
     * should be the account holders email address.
     *
     * @since 1.0
     */
    private final String accountName;

    /**
     * Account friendly name. This should be the Minecraft username.
     *
     * @since 1.0
     */
    private String friendlyName = null;

    /**
     * Account identifier. Unique and assigned by Minecraft account service.
     *
     * @since 1.0
     */
    private String userIdentifier = null;

    /**
     * Account last access token. This needs to be renewed for each game launch.
     *
     * @since 1.0
     */
    private String lastAccessToken = null;

    /**
     * Create a new MinecraftAccount with specified account name. Token and account information are not populated.
     *
     * @param accountName authentication username
     *
     * @since 1.0
     */
    @ClientModeOnly
    public AuthAccount( final String accountName ) {
        this.accountName = accountName;
    }

    /**
     * Get the name of this account.
     *
     * @return account name
     *
     * @since 1.0
     */
    @ClientModeOnly
    public String getAccountName() {
        return this.accountName;
    }

    /**
     * Get the last used access token of this account. Note, this does not include tokens outside of this instance.
     *
     * @return last access token
     *
     * @since 1.0
     */
    @ClientModeOnly
    public String getLastAccessToken() {
        return this.lastAccessToken;
    }

    /**
     * Get the friendly name of this account.
     *
     * @return friendly name
     *
     * @since 1.0
     */
    @ClientModeOnly
    public String getFriendlyName() {
        return this.friendlyName;
    }

    /**
     * Get the user identifier of this account.
     *
     * @return user identifier
     *
     * @since 1.0
     */
    @ClientModeOnly
    public String getUserIdentifier() {
        return userIdentifier;
    }

    /**
     * Set the friendly name of this account. Non-external method.
     *
     * @param friendlyName new friendly name
     *
     * @since 1.0
     */
    @ClientModeOnly
    void setFriendlyName( final String friendlyName ) {
        this.friendlyName = friendlyName;
    }

    /**
     * Set the last used access token of this account. Non-external method.
     *
     * @param lastAccessToken last used access token
     *
     * @since 1.0
     */
    @ClientModeOnly
    void setLastAccessToken( final String lastAccessToken ) {
        this.lastAccessToken = lastAccessToken;
    }

    /**
     * Set the user identifier of this account. Non-external method.
     *
     * @param userIdentifier user identifier
     *
     * @since 1.0
     */
    @ClientModeOnly
    void setUserIdentifier( final String userIdentifier ) {
        this.userIdentifier = userIdentifier;
    }
}
