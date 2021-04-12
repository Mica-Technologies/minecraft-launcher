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

package com.micatechnologies.minecraft.launcher.utilities.objects;

/**
 * A generic object pair class that allows for the pairing of two objects of any type.
 *
 * @param <T1> object 1 type
 * @param <T2> object 2 type
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class Pair< T1, T2 >
{
    /**
     * First object in the pair. Is of type {@link T1}.
     */
    public final T1 _1;

    /**
     * Second object in the pair. Is of type {@link T2}.
     */
    public final T2 _2;

    /**
     * Constructor for object pair.
     *
     * @param _1 first object
     * @param _2 second object
     */
    public Pair( T1 _1, T2 _2 ) {
        this._1 = _1;
        this._2 = _2;
    }
}
