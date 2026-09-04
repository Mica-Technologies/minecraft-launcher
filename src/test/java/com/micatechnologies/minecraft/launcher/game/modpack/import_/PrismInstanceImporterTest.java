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

package com.micatechnologies.minecraft.launcher.game.modpack.import_;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security/robustness-boundary tests for {@link PrismInstanceImporter},
 * which reads an arbitrary user-selected Prism Launcher / MultiMC
 * instance folder — including two untrusted files it parses directly,
 * {@code instance.cfg} and {@code mmc-pack.json}. A user importing a
 * shared or downloaded instance folder is trusting the launcher not to
 * choke ungracefully (or worse) on a hostile or simply corrupt pair of
 * metadata files.
 *
 * <p>Every test here targets {@link PrismInstanceImporter#importInstance}
 * itself, but only ever with inputs that make validation fail before
 * the method's first {@code LocalPathManager}-resolved write (writing
 * the translated manifest into {@code imported-manifests/}) or its call
 * into {@code GameModPackManager.installModPackByURL}. Tracing the
 * method's control flow: the instance-folder / file-existence checks,
 * {@code instance.cfg} parsing, and {@code mmc-pack.json} parsing all
 * happen strictly before any {@code LocalPathManager} path is touched —
 * so every failure mode reachable from those stages is safe to exercise
 * directly against a {@code @TempDir} instance folder. The success path
 * (a fully valid instance) is deliberately NOT exercised here, since it
 * would write into the real per-user launcher config/install
 * directories and call through to {@code GameModPackManager}.</p>
 *
 * @since 2026.5
 */
class PrismInstanceImporterTest
{
    @TempDir
    Path tempDir;

    private static final String VALID_INSTANCE_CFG = "[General]\nname=Test Instance\n";

    @Test
    void nullInstanceDirIsRejected()
    {
        PrismInstanceImporter.ImportException ex = assertThrows( PrismInstanceImporter.ImportException.class,
                                                                   () -> PrismInstanceImporter.importInstance( null ) );
        assertTrue( ex.getMessage().contains( "Prism Launcher" ) );
    }

    @Test
    void fileInsteadOfDirectoryIsRejected() throws IOException
    {
        File notADir = tempDir.resolve( "not-a-dir" ).toFile();
        assertTrue( notADir.createNewFile() );
        PrismInstanceImporter.ImportException ex = assertThrows( PrismInstanceImporter.ImportException.class,
                                                                   () -> PrismInstanceImporter.importInstance( notADir ) );
        assertTrue( ex.getMessage().contains( "Prism Launcher" ) );
    }

    @Test
    void missingInstanceCfgIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "missing-cfg" ) );
        writeFile( dir.resolve( "mmc-pack.json" ), "{}" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "instance.cfg" ) );
    }

    @Test
    void missingMmcPackJsonIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "missing-mmcpack" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "mmc-pack.json" ) );
    }

    @Test
    void missingMinecraftSubfolderIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "missing-mc-folder" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        writeFile( dir.resolve( "mmc-pack.json" ), "{\"components\":[]}" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( ".minecraft" ) );
    }

    @Test
    void oversizedInstanceCfgIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "huge-cfg" ) );
        Files.createDirectory( dir.resolve( ".minecraft" ) );
        writeFile( dir.resolve( "mmc-pack.json" ), "{\"components\":[]}" );
        // Just over the importer's 5 MB soft cap on metadata files.
        writeLargeFile( dir.resolve( "instance.cfg" ), 5L * 1024 * 1024 + 1024 );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "instance.cfg" ) );
        assertTrue( ex.getMessage().contains( "large" ) );
    }

    @Test
    void oversizedMmcPackJsonIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "huge-mmcpack" ) );
        Files.createDirectory( dir.resolve( ".minecraft" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        writeLargeFile( dir.resolve( "mmc-pack.json" ), 5L * 1024 * 1024 + 1024 );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "mmc-pack.json" ) );
        assertTrue( ex.getMessage().contains( "large" ) );
    }

    @Test
    void malformedMmcPackJsonIsRejectedRatherThanThrowingRawException() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "bad-json" ) );
        Files.createDirectory( dir.resolve( ".minecraft" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        writeFile( dir.resolve( "mmc-pack.json" ), "{ this is not valid json at all" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "isn't valid JSON" ) );
    }

    @Test
    void mmcPackJsonMissingComponentsArrayIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "no-components" ) );
        Files.createDirectory( dir.resolve( ".minecraft" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        writeFile( dir.resolve( "mmc-pack.json" ), "{\"formatVersion\":1}" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "components array" ) );
    }

    @Test
    void mmcPackJsonMissingMinecraftVersionIsRejected() throws IOException
    {
        Path dir = Files.createDirectory( tempDir.resolve( "no-mc-version" ) );
        Files.createDirectory( dir.resolve( ".minecraft" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        // A components array with entries, but none of them net.minecraft.
        writeFile( dir.resolve( "mmc-pack.json" ),
                   "{\"components\":[{\"uid\":\"net.minecraftforge\",\"version\":\"47.2.0\"}]}" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        assertTrue( ex.getMessage().contains( "Minecraft version" ) );
    }

    @Test
    void legacyMinecraftSubfolderNameIsAlsoAccepted() throws IOException
    {
        // MultiMC-legacy instances use "minecraft/" instead of Prism's
        // ".minecraft/". Confirm the importer gets far enough to reach
        // mmc-pack.json parsing (and fails there, safely) rather than
        // rejecting the folder for missing .minecraft/.
        Path dir = Files.createDirectory( tempDir.resolve( "legacy-mc-folder" ) );
        Files.createDirectory( dir.resolve( "minecraft" ) );
        writeFile( dir.resolve( "instance.cfg" ), VALID_INSTANCE_CFG );
        writeFile( dir.resolve( "mmc-pack.json" ), "{ not valid json" );
        PrismInstanceImporter.ImportException ex = assertThrows(
                PrismInstanceImporter.ImportException.class,
                () -> PrismInstanceImporter.importInstance( dir.toFile() ) );
        // If the folder check had failed we'd see the ".minecraft" message
        // instead — getting the JSON-parse failure proves the legacy
        // "minecraft/" folder name was accepted.
        assertTrue( ex.getMessage().contains( "isn't valid JSON" ) );
    }

    // ===================================================================
    //  test helpers
    // ===================================================================

    private static void writeFile( Path path, String content ) throws IOException
    {
        Files.writeString( path, content, StandardCharsets.UTF_8 );
    }

    private static void writeLargeFile( Path path, long sizeBytes ) throws IOException
    {
        byte[] chunk = new byte[ 64 * 1024 ];
        try ( var out = Files.newOutputStream( path ) ) {
            long remaining = sizeBytes;
            while ( remaining > 0 ) {
                int n = (int) Math.min( chunk.length, remaining );
                out.write( chunk, 0, n );
                remaining -= n;
            }
        }
    }
}
