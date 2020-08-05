/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.exceptions;

/**
 * Exception wrapper class for handling and reporting errors and exceptions with user-friendly working/explanation if
 * they occur within the {@link com.micatechnologies.minecraft.forgelauncher.game.auth} library.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @see com.micatechnologies.minecraft.forgelauncher.exceptions.LauncherException
 * @since 1.0.1
 */
public class AuthException extends LauncherException
{
    /**
     * Create an {@link AuthException} with specified message.
     *
     * @param reason exception message
     */
    public AuthException( String reason ) {
        super( reason );
    }

    /**
     * Create an {@link AuthException} with specified message and backtrace.
     *
     * @param reason    exception message
     * @param backtrace exception backtrace
     */
    public AuthException( String reason, Throwable backtrace ) {
        super( reason, backtrace );
    }
}
