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

import java.time.LocalTime;

public class TimeUtilities
{
    private static final LocalTime MORNING_TIME_RANGE   = LocalTime.of( 0, 0, 0 );
    private static final LocalTime AFTERNOON_TIME_RANGE = LocalTime.of( 12, 0, 0 );
    private static final LocalTime EVENING_TIME_RANGE   = LocalTime.of( 17, 0, 0 );
    private static final LocalTime NIGHT_TIME_RANGE     = LocalTime.of( 21, 0, 0 );

    public static String getFriendlyTimeBasedGreeting() {
        String returnString = "";
        if ( isTimeInRange( MORNING_TIME_RANGE, AFTERNOON_TIME_RANGE ) ) {
            returnString = "Good Morning";
        } else if ( isTimeInRange( AFTERNOON_TIME_RANGE, EVENING_TIME_RANGE ) ) {
            returnString = "Good Afternoon";
        } else if ( isTimeInRange( EVENING_TIME_RANGE, NIGHT_TIME_RANGE ) ) {
            returnString = "Good Evening";
        } else {
            returnString = "Good Night";
        }
        return returnString;
    }

    private static boolean isTimeInRange( LocalTime startRange, LocalTime endRange ) {
        LocalTime now = LocalTime.now();
        return ( !now.isBefore( startRange ) ) && now.isBefore( endRange );
    }
}
