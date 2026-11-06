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

package com.micatechnologies.minecraft.launcher.update;

import java.nio.file.Path;

/**
 * A downloaded, checksum-verified launcher update waiting to be installed.
 *
 * @param version the release it installs, such as {@code 2026.11.20}
 * @param kind    the install kind it was downloaded for; installing it on another kind is refused
 * @param file    the downloaded release file
 * @param sha256  the file's lowercase hex SHA-256, as GitHub reported it and the download matched
 *
 * @since 2026.11
 */
public record StagedUpdate( String version, InstallKind kind, Path file, String sha256 )
{
}
