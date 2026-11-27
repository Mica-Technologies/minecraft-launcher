## Which file do I download?

| Your computer | Download |
|---|---|
| Windows | `Mica.Minecraft.Launcher.Setup.exe`. Or an MSI: `-user.msi` installs for your account only, `-system.msi` for everyone on the PC. |
| Mac with Apple silicon (M1 or later) | the `-arm64.dmg` |
| Mac with an Intel processor | the `-intel.dmg` |
| Steam Deck, or a Linux system where you can't install packages | the `-x86_64.AppImage` |
| Debian, Ubuntu, Linux Mint | the `_amd64.deb` |
| Fedora, openSUSE | the `.x86_64.rpm` |

## Installing on a Steam Deck

The launcher installs and is added to Steam from Desktop Mode. After that you can play from Game Mode.

1. **Switch to Desktop Mode.** Hold the power button, then choose **Switch to Desktop**. A keyboard and mouse help, but the touchscreen and trackpads work too. Press **Steam + X** for the on-screen keyboard.
2. **Download the AppImage.** Open this page in a web browser (if there's none, install Firefox from the **Discover** store). Download the file ending in `-x86_64.AppImage`. It goes to your **Downloads** folder.
3. **Give it a home.** Open **Dolphin** (the file manager), create a folder named `Applications` in your home folder, and move the AppImage into it. Any folder in your home folder works. Keep the file where you put it: the launcher updates itself by replacing that file, and Steam starts it from there.
4. **Allow it to run.** Right-click the AppImage, choose **Properties**, then **Permissions**. Tick **Is executable** and click **OK**. Browsers drop this permission from downloads.
5. **Start it and sign in.** Double-click the AppImage. On a Steam Deck, the sign-in screen shows a code. Enter it at [microsoft.com/link](https://www.microsoft.com/link) on your phone or another computer.
6. **Add it to Steam.** In the launcher, open **Settings** and, under **Third-party integrations**, click **Add the launcher to Steam**. Steam closes and starts again while the launcher is added. To add a single modpack, right-click it, or open its details, and choose **Add to Steam**. A modpack added this way starts straight into the game and returns to Steam when you quit.
7. **Back to Game Mode.** Double-click **Return to Gaming Mode** on the desktop. The launcher and any modpacks you added are in your library under **Non-Steam**.

Adding to Steam only works from Desktop Mode, because Steam has to close while games are added.
