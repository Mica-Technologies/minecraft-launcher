import { contextBridge, ipcRenderer } from 'electron';
import { electronAPI } from '@electron-toolkit/preload';
import ModPackInterface from '@common/types/ModPackManifest';
import { ipcConstants } from '@common/constants/ipcConstants';

// Custom APIs for renderer
const api = {
  shutdownApp: async (): Promise<void> => {
    return await ipcRenderer.invoke(ipcConstants.F2B_APP_OP_SHUTDOWN);
  },
  restartApp: async (): Promise<void> => {
    return await ipcRenderer.invoke(ipcConstants.F2B_APP_OP_RESTART);
  },
  getModPackUrls: async (): Promise<string[]> => {
    return await ipcRenderer.invoke('get-mod-pack-urls');
  },
  getModPackData: async (modPackName: string): Promise<ModPackInterface> => {
    return await ipcRenderer.invoke('get-mod-pack-data', modPackName);
  },
  refreshModPackData: async (): Promise<string[]> => {
    return await ipcRenderer.invoke('refresh-mod-pack-data');
  },
};

// Use `contextBridge` APIs to expose Electron APIs to
// renderer only if context isolation is enabled, otherwise
// just add to the DOM global.
if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI);
    contextBridge.exposeInMainWorld('api', api);
  } catch (error) {
    console.error(error);
  }
} else {
  // @ts-ignore (define in dts)
  window.electron = electronAPI;
  // @ts-ignore (define in dts)
  window.api = api;
}
