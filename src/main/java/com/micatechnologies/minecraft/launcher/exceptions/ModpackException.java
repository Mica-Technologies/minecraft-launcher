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

package com.micatechnologies.minecraft.launcher.exceptions;

/**
 * Exception wrapper class for handling and reporting errors and exceptions with user-friendly working/explanation if
 * they occur within the {@link com.micatechnologies.minecraft.launcher.game.modpack} library.
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @see com.micatechnologies.minecraft.launcher.exceptions.LauncherException
 * @since 1.0
 */
public class ModpackException extends LauncherException
{

    /**
     * Create a {@link ModpackException} with specified message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    public ModpackException( String exceptionMsg ) {
        super( exceptionMsg );
    }

    /**
     * Create a {@link ModpackException} with specified message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @since 1.0
     */
    public ModpackException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }
}
