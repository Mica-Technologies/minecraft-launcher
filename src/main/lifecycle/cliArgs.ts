import commandLineArgs from 'command-line-args'
import commandLineUsage from 'command-line-usage'
import { LauncherMode } from '@common/types/LauncherMode'
import figlet from 'figlet'
import { getConsoleWidth } from '@main/utils/cliUtil'

// ASCII Art Logo
const logo = figlet.textSync('Mica Minecraft Launcher', {
  font: 'Colossal',
  horizontalLayout: 'default',
  verticalLayout: 'default',
  width: getConsoleWidth() - 1,
  whitespaceBreak: true
})

// Define command-line options
const optionDefinitions = [
  { name: 'headless', alias: 'h', type: Boolean, defaultValue: false },
  { name: 'launcherMode', alias: 'm', type: String, defaultValue: 'client' }
]

// Generate help text
export const cliUsageText = commandLineUsage([
  {
    header: `${logo}\nMica Minecraft Launcher`,
    content: 'An open-source, cross-platform, and easy-to-use Minecraft launcher.'
  },
  {
    header: 'Options',
    optionList: [
      {
        name: 'headless',
        alias: 'h',
        typeLabel: '{underline boolean}',
        description: 'Run the application in headless mode.'
      },
      {
        name: 'launcherMode',
        alias: 'm',
        typeLabel: '{underline string}',
        description: 'Set the launcher mode (client or server).'
      }
    ]
  }
])

// Function to parse and set CLI arguments
export async function handleCliArgs() {
  // Parse command-line options
  let options = commandLineArgs(optionDefinitions)

  // Validate and set the launcher mode
  const validLauncherModes = ['client', 'server']
  let launcherMode: LauncherMode = LauncherMode.CLIENT
  if (options.launcherMode && validLauncherModes.includes(options.launcherMode)) {
    launcherMode = options.launcherMode as LauncherMode
  } else if (options.launcherMode) {
    throw new Error('Invalid launcher mode: ' + options.launcherMode)
  }

  // Set the headless mode
  const headless = options.headless || false

  return { headless, launcherMode }
}
