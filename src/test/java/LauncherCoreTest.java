import static org.junit.jupiter.api.Assertions.*;

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