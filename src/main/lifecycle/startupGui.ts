import { app, shell, BrowserWindow } from 'electron';
import { join } from 'path';
import { optimizer, is } from '@electron-toolkit/utils';
import { preShutdown } from '../lifecycle/events';
import icon from '../../../resources/icon.png?asset';
import { setupIpc } from './ipc';
import electronWindowState from 'electron-window-state';

const MAIN_WINDOW_DEFAULT_WIDTH = 900;
const MAIN_WINDOW_DEFAULT_HEIGHT = 670;
const MAIN_WINDOW_MIN_WIDTH = 500;
const MAIN_WINDOW_MIN_HEIGHT = 500;

export function createWindow(): void {
  // Load the previous state with fallback to defaults
  const mainWindowState = electronWindowState({
    defaultWidth: MAIN_WINDOW_DEFAULT_WIDTH,
    defaultHeight: MAIN_WINDOW_DEFAULT_HEIGHT,
    file: 'main-window-state.json',
  });

  // Create the browser window.
  const mainWindow = new BrowserWindow({
    width: mainWindowState.width,
    height: mainWindowState.height,
    x: mainWindowState.x,
    y: mainWindowState.y,
    minWidth: MAIN_WINDOW_MIN_WIDTH,
    minHeight: MAIN_WINDOW_MIN_HEIGHT,
    show: true,
    autoHideMenuBar: true,
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: 'rgba(128, 128, 128, 0.0)',
    },
    icon: icon,
    ...(process.platform === 'linux' ? { icon } : {}),
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false,
    },
  });

  // Set the bounds
  mainWindowState.manage(mainWindow);
  mainWindow.setBounds(
    {
      width: mainWindowState.width || MAIN_WINDOW_DEFAULT_WIDTH,
      height: mainWindowState.height || MAIN_WINDOW_DEFAULT_HEIGHT,
    },
    false
  );

  mainWindow.on('ready-to-show', () => {
    mainWindow.show();
  });

  mainWindow.on('close', (e) => {
    const allowClose = true; // TODO: Replace with actual logic to determine if close is allowed
    if (!allowClose) {
      e.preventDefault();
    }
  });

  mainWindow.webContents.setWindowOpenHandler((details) => {
    shell.openExternal(details.url);
    return { action: 'deny' };
  });

  // HMR for renderer base on electron-vite cli.
  // Load the remote URL for development or the local html file for production.
  if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL']);
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'));
  }
}

export function startGUI(): void {
  app.whenReady().then(() => {
    // Default open or close DevTools by F12 in development
    // and ignore CommandOrControl + R in production.
    // see https://github.com/alex8088/electron-toolkit/tree/master/packages/utils
    app.on('browser-window-created', (_, window) => {
      optimizer.watchWindowShortcuts(window);
    });

    // Setup IPC for front-end<-->back-end communication
    setupIpc();

    // Create the main window
    createWindow();

    app.on('activate', function () {
      // On macOS it's common to re-create a window in the app when the
      // dock icon is clicked and there are no other windows open.
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  // Quit when all windows are closed, except on macOS. There, it's common
  // for applications and their menu bar to stay active until the user quits
  // explicitly with Cmd + Q.
  // Also, call the launcher core onShutdown function (unless on macOS)
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      preShutdown();
      app.quit();
    }
  });

  // On macOS, call the launcher core onShutdown function when the app is quit
  app.on('before-quit', () => {
    if (process.platform === 'darwin') {
      preShutdown();
    }
  });
}
