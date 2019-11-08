import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackConsts;
import java.io.File;

public class LauncherConstants {

    public static final String LAUNCHER_TITLE               = "Mica Forge Launcher";

    public static final String LAUNCHER_FULL_NAME           = "Mica Minecraft Forge Launcher";

    public static final int    LAUNCHER_CLIENT_MODE         = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;

    public static final int    LAUNCHER_SERVER_MODE         = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;

    public static final String LAUNCHER_CONFIG_NAME         = "launcher.config.json";

    public static final String LAUNCHER_CLIENT_INSTALL_PATH = System.getProperty( "user.home" )
        + File.separator + "." + LAUNCHER_TITLE.replace( " ", "" ) + File.separator;

    public static final String LAUNCHER_CONFIG_DEFAULT_FILE =
        "{  " + System.lineSeparator() + "   \"minRAM\":256," + System.lineSeparator()
            + "   \"maxRAM\":2048," + System.lineSeparator() + "   \"modpacks\":[  " + System
            .lineSeparator()
            + "      \"https://filebin.net/9r7nt2pxerdmti09/pack.manifest?t=rvw2e5c0\"" + System
            .lineSeparator() + "   ]" + System.lineSeparator() + "}";
}
