/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.utilities

import java.time.LocalTime

/**
 * A utility object for date and time related constants and functions.
 *
 * @since 2023.1
 * @version 2.0
 * @author Mica Technologies
 */
object DateTimeUtilities {

  /**
   * The morning time range in 24-hour format.
   *
   * @since 2.0
   */
  var MORNING_TIME_RANGE: (Int, Int) = (0, 12)

  /**
   * The afternoon time range in 24-hour format.
   *
   * @since 2.0
   */
  var AFTERNOON_TIME_RANGE: (Int, Int) = (MORNING_TIME_RANGE._1, 18)

  /**
   * The evening time range in 24-hour format.
   *
   * @since 2.0
   */
  var EVENING_TIME_RANGE: (Int, Int) = (AFTERNOON_TIME_RANGE._1, 24)

  /**
   * Gets the current time range as an pair of integers. If the time range could not be determined, the pair will be
   * (0, 0).
   *
   * @return current time range as an pair of integers
   * @since 2.0
   */
  def getCurrentTimeRange: (Int, Int) = {
    val currentTime = LocalTime.now()
    val currentHour = currentTime.getHour
    var timeRange = (0, 0)
    if (currentHour >= MORNING_TIME_RANGE._1 && currentHour < MORNING_TIME_RANGE._2) {
      timeRange = MORNING_TIME_RANGE
    }
    else if (currentHour >= AFTERNOON_TIME_RANGE._1 && currentHour < AFTERNOON_TIME_RANGE._2) {
      timeRange = AFTERNOON_TIME_RANGE
    }
    else if (currentHour >= EVENING_TIME_RANGE._2 || currentHour < EVENING_TIME_RANGE._1) {
      timeRange = EVENING_TIME_RANGE
    }
    timeRange
  }

  /**
   * Gets a boolean value indicating whether the current time is within the specified time range.
   *
   * @param timeRange time range to check
   * @return boolean value indicating whether the current time is within the specified time range
   * @since 2.0
   */
  def isLocalTimeInRange(timeRange: (Int, Int)): Boolean = {
    val currentTime = LocalTime.now()
    isTimeInRange(currentTime, timeRange)
  }

  /**
   * Gets a boolean value indicating whether the specified time is within the specified time range.
   *
   * @param time      time to check
   * @param timeRange time range to check
   * @return boolean value indicating whether the specified time is within the specified time range
   * @since 2.0
   */
  def isTimeInRange(time: LocalTime, timeRange: (Int, Int)): Boolean =
    time.getHour >= timeRange._1 && time.getHour < timeRange._2

  /**
   * Gets a friendly time-based greeting based on the current time.
   * <p>
   * If the current time is within the morning time range, the greeting will be "Good Morning".
   * If the current time is within the afternoon time range, the greeting will be "Good Afternoon".
   * If the current time is within the evening time range, the greeting will be "Good Evening".
   * If the current time is not within any of the configured time ranges, the greeting will be "Hello".
   *
   * @return friendly time-based greeting based on the current time
   * @since 1.0
   */
  def getFriendlyTimeBasedGreeting: String = {
    val timeRange = getCurrentTimeRange
    if (timeRange == MORNING_TIME_RANGE) {
      "Good Morning"
    }
    else if (timeRange == AFTERNOON_TIME_RANGE) {
      "Good Afternoon"
    }
    else if (timeRange == EVENING_TIME_RANGE) {
      "Good Evening"
    }
    else {
      "Hello"
    }
  }
}
