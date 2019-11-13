package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.LauncherCore;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackException;
import java.io.IOException;

class LauncherCoreTest {

    @org.junit.jupiter.api.Test
    void runClientLauncher()
        throws IOException, InterruptedException, IllegalAccessException, MCForgeModpackException, InstantiationException {
        LauncherCore.runWithMode( LauncherConstants.LAUNCHER_CLIENT_MODE, 0 );
    }

    @org.junit.jupiter.api.Test
    void runServerLauncher()
        throws IOException, InterruptedException, IllegalAccessException, MCForgeModpackException, InstantiationException {
        LauncherCore.runWithMode( LauncherConstants.LAUNCHER_CLIENT_MODE, 0 );
    }
}