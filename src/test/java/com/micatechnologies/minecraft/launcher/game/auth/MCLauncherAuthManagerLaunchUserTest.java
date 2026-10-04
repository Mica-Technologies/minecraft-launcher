/*
 * Copyright (c) 2026 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.FAKE_CIPHER;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.authFile;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.user;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.uuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Which token {@link MCLauncherAuthManager#userForLaunch} launches with when the account's
 * refresh is due and fails. A transient failure keeps the cached token, which is usually
 * still good; a refresh Microsoft refused means the cached token is dead too, so the launch
 * is blocked with "needs sign-in" instead of handing Minecraft a token it will reject.
 */
class MCLauncherAuthManagerLaunchUserTest
{
    private static final long INTERVAL = 4L * 60 * 60 * 1000;
    private static final long T0       = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong( T0 );
    private AccountManager.Renewer renewer;
    private AccountManager         manager;

    @BeforeEach
    void setUp()
    {
        AccountStore store = new AccountStore( tempDir.resolve( AccountStore.PROFILES_DIR ), FAKE_CIPHER );
        String[] defaultUuid = { "" };
        AccountManager.DefaultAccountSetting defaults = new AccountManager.DefaultAccountSetting()
        {
            @Override
            public String get()
            {
                return defaultUuid[ 0 ];
            }

            @Override
            public void set( String uuid )
            {
                defaultUuid[ 0 ] = uuid;
            }
        };
        manager = new AccountManager( store, tempDir, FAKE_CIPHER, defaults, in -> renewer.renew( in ), now::get,
                                      Runnable::run, INTERVAL );
        manager.addSignedIn( user( 'a', "cached" ), authFile( "a" ), true );
        // Past the refresh interval, so the launch has to refresh first.
        now.addAndGet( INTERVAL + 1 );
    }

    @Test
    void successfulRefreshLaunchesWithTheNewToken() throws Exception
    {
        renewer = in -> new AccountManager.Renewal( user( 'a', "fresh" ), in );

        assertEquals( "fresh", MCLauncherAuthManager.userForLaunch( null, manager, 5 ).accessToken() );
    }

    @Test
    void transientFailureLaunchesWithTheCachedToken() throws Exception
    {
        renewer = in -> { throw new AccountManager.RenewalFailedException( "timed out", false ); };

        assertEquals( "cached", MCLauncherAuthManager.userForLaunch( null, manager, 5 ).accessToken() );
    }

    @Test
    void rejectedCredentialsBlockTheLaunchWithNeedsSignIn()
    {
        renewer = in -> { throw new AccountManager.RenewalFailedException( "invalid credentials", true ); };

        LaunchAccountResolver.BlockedException blocked = assertThrows(
                LaunchAccountResolver.BlockedException.class,
                () -> MCLauncherAuthManager.userForLaunch( null, manager, 5 ) );
        assertEquals( LaunchAccountResolver.Problem.NEEDS_SIGN_IN, blocked.resolution().problem() );
        assertEquals( AccountManager.Status.NEEDS_SIGN_IN, manager.account( uuid( 'a' ) ).status() );
    }
}
