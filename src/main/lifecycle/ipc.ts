import { ipcMain } from 'electron';
import RemoteModPacksManager from '@main/modPacks/RemoteModPacksManager';
import { ipcConstants } from '@common/constants/ipcConstants';
import * as electron from 'electron';
import log from 'electron-log/main';

export function setupIpc(): void {
  ipcMain.handle(ipcConstants.F2B_APP_OP_SHUTDOWN, async () => {
    log.debug('Shutdown requested via IPC');
    electron.app.quit();
  });

  ipcMain.handle(ipcConstants.F2B_APP_OP_RESTART, async () => {
    log.debug('Restart requested via IPC');
    electron.app.relaunch();
  });

  ipcMain.handle(ipcConstants.B2F_PACKS_DISCOVER_ALL_DATA, async () => {
    return RemoteModPacksManager.getModPackData();
  });

  // TODO: Handler for b2f on data update (if needed)
}
