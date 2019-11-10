package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.LauncherCore;

class LauncherCoreTest {

    @org.junit.jupiter.api.Test
    void runClientLauncher() {
        LauncherCore.testMain( LauncherConstants.LAUNCHER_CLIENT_MODE );
    }

    @org.junit.jupiter.api.Test
    void runServerLauncher() {
        LauncherCore.testMain( LauncherConstants.LAUNCHER_SERVER_MODE );
    }
}