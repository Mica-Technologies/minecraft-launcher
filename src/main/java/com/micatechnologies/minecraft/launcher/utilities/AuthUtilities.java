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

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;

public class AuthUtilities
{
    public static boolean checkAuthResponse( MCLauncherAuthResult authResult ) {
        boolean authSuccess = false;
        if ( authResult == MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD ) {
            Logger.logError( "Unable to login: incorrect username/password" );
        }
        else if ( authResult == MCLauncherAuthResult.ERROR_LOGIN_EXPIRED ) {
            Logger.logError( "Unable to login: login expired" );
        }
        else if ( authResult == MCLauncherAuthResult.ERROR_NO_VAL ) {
            Logger.logError( "Unable to login: no value present (Account may not be registered to Xbox Live)" );
        }
        else if ( authResult == MCLauncherAuthResult.ERROR_OTHER ) {
            Logger.logError( "Unable to login: unknown error" );
        }
        else if ( authResult == MCLauncherAuthResult.ERROR_NOT_OWNED ) {
            Logger.logError( "Unable to login: account does not own Minecraft" );
        }
        else {
            authSuccess = true;
        }
        return authSuccess;
    }
}
