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

import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientModeOnly;

/**
 * Exception wrapper class allowing for a customizable explanation or explanation and backtrace for exceptions or errors
 * that occur within the authentication package.
 *
 * @author Mica Technologies
 * @editors hawka97
 * @creator hawka97
 * @version 1.1
 * @since 1.1
 */
@ClientModeOnly
public class AuthException extends Exception
{
    /**
     * Exception wrapper constructor for specifying a custom exception with customized explanation.
     *
     * @param reason exception explanation
     */
    @ClientModeOnly
    public AuthException( String reason ) {
        super( reason );
    }

    /**
     * Exception wrapper constructor for specifying a custom exception with customized explanation and specified
     * backtrace.
     *
     * @param reason    exception explanation
     * @param backtrace exception backtrace
     */
    @ClientModeOnly
    public AuthException( String reason, Throwable backtrace ) {
        super( reason, backtrace );
    }
}
