import { app } from 'electron'
import { startGUI } from './lifecycle/startupGui'
import { startHeadless } from './lifecycle/startupHeadless'
import { handleCliArgs } from './lifecycle/cliArgs'
import { setLauncherHeadless, setLauncherMode } from './utils/launcherState'
import { postStartup, preStartup } from './lifecycle/events'

// This method will be called when Electron has finished
// initialization and is ready to create browser windows.
// Some APIs can only be used after this event occurs.
app.whenReady().then(async () => {
  try {
    // Parse CLI arguments
    const { headless: cliHeadless, launcherMode } = await handleCliArgs()

    // Check if monitor/videocard is supported
    // TODO: Implement this
    const monitorSupported = true
    const headless = cliHeadless || !monitorSupported

    // Set the launcher mode and headless states
    await setLauncherMode(launcherMode)
    await setLauncherHeadless(headless)
    console.log(`Launcher mode: ${launcherMode}`)
    console.log(`Headless mode: ${headless}`)

    // Perform pre-startup tasks
    preStartup()

    // Start the application in the appropriate mode
    if (headless) {
      startHeadless()
    } else {
      startGUI()
    }

    // Perform post-startup tasks
    postStartup()
  } catch (error) {
    console.error('An error occurred during startup:', error)
    app.quit()
  }
})

// Handle unhandled errors
process.on('unhandledRejection', (reason) => {
  console.error('Unhandled Promise Rejection:', reason);
  app.quit();
});