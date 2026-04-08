# Login

The Mica Minecraft Launcher uses **official Microsoft authentication** to verify your Minecraft account. This is the same sign-in flow used by the official Minecraft launcher -- the launcher never sees or stores your password.

## How It Works

1. **Click Sign In** on the login screen. A browser window opens to the Microsoft sign-in page (`login.microsoftonline.com`).
2. **Enter your Microsoft account credentials** and approve the sign-in. This is handled entirely by Microsoft's authentication service.
3. **The browser window closes** once authentication is complete, and the launcher proceeds to the [Main Screen](main-screen.md).

Behind the scenes, the launcher exchanges the Microsoft authentication code for a Minecraft access token through the official Xbox Live and Minecraft services API chain. At no point does the launcher have access to your Microsoft password.

## Token Caching

After a successful login, your authentication token is cached locally using **AES-256-GCM encryption** with a machine-derived key. This provides several benefits:

- **No repeated sign-ins** -- you will not need to sign in again for approximately **4 hours** after a successful authentication.
- **Machine-bound security** -- the cached token is encrypted with a key derived from your specific machine's hardware identifiers. The token file cannot be transferred to or used on another machine.
- **Automatic expiration** -- after the cache expires (approximately 4 hours), the launcher will prompt you to sign in again. This is normal behavior and ensures your account remains secure.

The cached token is stored in the launcher's data directory as an encrypted file. It contains only the Minecraft access token -- never your Microsoft credentials.

## Troubleshooting Login Issues

> **Warning:** An active internet connection is required for the initial login. The launcher cannot authenticate in fully offline mode.

If you are having trouble signing in:

### Browser does not open

- Check that a default browser is configured on your system.
- On Linux, ensure `xdg-open` is available and configured.
- Try opening a URL manually in your browser to verify it is working.

### Sign-in times out

- Verify your internet connection is active and stable.
- Check that `login.microsoftonline.com` is not blocked by your network, firewall, or antivirus.
- If you are on a corporate or school network, you may need to configure a proxy in [Settings](settings.md).
- Close the browser window and try again.

### Account not recognized

- Make sure you are using the Microsoft account that owns a Minecraft: Java Edition license.
- If you have multiple Microsoft accounts, verify you are signing in with the correct one.
- Check your Minecraft license status at [minecraft.net](https://www.minecraft.net/).

### Repeated sign-in prompts

- Token expiration after approximately 4 hours is normal -- this is not a bug.
- If you are prompted to sign in every time you launch (even within the 4-hour window), the cached token file may be corrupted. Try using **Reset Launcher** in [Settings](settings.md) to clear all cached data, then sign in again.

### Sign-in succeeds but launcher shows an error

- Your Microsoft account may not have an active Minecraft: Java Edition license.
- Xbox Live services may be experiencing an outage. Check [Xbox Live Status](https://support.xbox.com/en-US/xbox-live-status).

For persistent issues not covered here, see [Troubleshooting](troubleshooting.md).
