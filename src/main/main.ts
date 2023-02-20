/* eslint global-require: off, no-console: off, promise/always-return: off */

/**
 * This module executes inside of electron's main process. You can start
 * electron renderer process from here and communicate with the other processes
 * through IPC.
 *
 * When running `npm run build` or `npm run build:main`, this file is compiled to
 * `./src/main.js` using webpack. This gives us some performance wins.
 */
import path from 'path';
import { app, BrowserWindow, shell } from 'electron';
import { autoUpdater } from 'electron-updater';
import log from 'electron-log';
import * as os from 'os';
import { AccountsStorage } from 'minecraft-auth/dist/AccountStorage';
import MenuBuilder from './menu';
import { resolveHtmlPath } from './util';
import ipcInit from './ipc/ipcInit';
import {
  fileExistsSync,
  readFileSync,
  writeFileSync,
} from './file/fileOperations';
import { launcherAuthCacheFile } from './file/filePathConstants';
import LauncherConfig from '../common/config/LauncherConfig';

class AppUpdater {
  constructor() {
    log.transports.file.level = 'info';
    autoUpdater.logger = log;
    autoUpdater.checkForUpdatesAndNotify();
  }
}

let mainWindow: BrowserWindow | null = null;
let accountsStorage: AccountsStorage;
let launcherConfig: LauncherConfig;

if (process.env.NODE_ENV === 'production') {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const sourceMapSupport = require('source-map-support');
  sourceMapSupport.install();
}

const isDebug =
  process.env.NODE_ENV === 'development' || process.env.DEBUG_PROD === 'true';

if (isDebug) {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  require('electron-debug')();
}

const installExtensions = async () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const installer = require('electron-devtools-installer');
  const forceDownload = !!process.env.UPGRADE_EXTENSIONS;
  const extensions = ['REACT_DEVELOPER_TOOLS'];

  return installer
    .default(
      extensions.map((name) => installer[name]),
      forceDownload
    )
    .catch(console.log);
};

const createWindow = async () => {
  if (isDebug) {
    await installExtensions();
  }

  const RESOURCES_PATH = app.isPackaged
    ? path.join(process.resourcesPath, 'assets')
    : path.join(__dirname, '../../assets');

  const getAssetPath = (...paths: string[]): string => {
    return path.join(RESOURCES_PATH, ...paths);
  };

  // Check for macOS to apply styling
  const isMacOs = os.platform() === 'darwin';
  const windowTitleBarStyle = isMacOs ? 'hiddenInset' : 'hidden';

  mainWindow = new BrowserWindow({
    show: false,
    width: 1024,
    height: 728,
    minWidth: 1024,
    minHeight: 728,
    icon: getAssetPath('icon.png'),
    titleBarStyle: windowTitleBarStyle,
    /* titleBarOverlay: {
      symbolColor: 'white',
      color: 'rgba(0, 0, 0, 0)',
    }, */
    webPreferences: {
      preload: app.isPackaged
        ? path.join(__dirname, 'preload.js')
        : path.join(__dirname, '../../.erb/dll/preload.js'),
    },
  });

  mainWindow.loadURL(resolveHtmlPath('index.html'));

  mainWindow.on('ready-to-show', () => {
    if (!mainWindow) {
      throw new Error('"mainWindow" is not defined');
    }
    if (process.env.START_MINIMIZED) {
      mainWindow.minimize();
    } else {
      mainWindow.show();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  const menuBuilder = new MenuBuilder(mainWindow);
  menuBuilder.buildMenu();

  // Open urls in the user's browser
  mainWindow.webContents.setWindowOpenHandler((edata) => {
    shell.openExternal(edata.url);
    return { action: 'deny' };
  });

  // Initialize MSAL
  // authProvider = new AuthProvider(authConfig);

  // Remove this if your app does not use auto updates
  // eslint-disable-next-line
  new AppUpdater();

  // Configure the ipcMain events
  ipcInit({ mainWindow, accountsStorage });
};

/**
 * Add event listeners...
 */

app.on('window-all-closed', () => {
  // Save the accounts to the storage
  if (accountsStorage) {
    writeFileSync(launcherAuthCacheFile, accountsStorage.serialize());
  }

  // Respect the OSX convention of having the application in memory even
  // after all windows have been closed
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app
  .whenReady()
  .then(() => {
    // Load the accounts from the storage
    if (fileExistsSync(launcherAuthCacheFile)) {
      try {
        const serialized = readFileSync(launcherAuthCacheFile);
        accountsStorage = AccountsStorage.deserialize(serialized);
        accountsStorage.accountList.forEach((account) => {
          account.getProfile();
          account
            .checkOwnership()
            .then(() => {
              console.log(
                `User ${account.profile?.name} logged in and ready to use!`
              );
            })
            .catch(console.log);
        });
      } catch (e) {
        console.log('Error while loading accounts from storage. Resetting...');
        console.log(e);
        accountsStorage = new AccountsStorage();
      }
    } else {
      accountsStorage = new AccountsStorage();
    }

    createWindow();
    app.on('activate', () => {
      // On macOS it's common to re-create a window in the app when the
      // dock icon is clicked and there are no other windows open.
      if (mainWindow === null) createWindow();
    });
  })
  .catch(console.log);
