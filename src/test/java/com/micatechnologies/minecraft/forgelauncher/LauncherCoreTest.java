package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.LauncherCore;
import java.io.IOException;

class LauncherCoreTest {

    @org.junit.jupiter.api.Test
    void runClientLauncher() throws IOException, InterruptedException {
        LauncherCore.testMain( LauncherConstants.LAUNCHER_CLIENT_MODE );
    }

    @org.junit.jupiter.api.Test
    void runServerLauncher() throws IOException, InterruptedException {
        LauncherCore.testMain( LauncherConstants.LAUNCHER_SERVER_MODE );
    }
}