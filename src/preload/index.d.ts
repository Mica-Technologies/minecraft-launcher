import { ElectronAPI } from '@electron-toolkit/preload';
import ModPackInterface from '@common/types/ModPackManifest';

declare global {
  interface Window {
    electron: ElectronAPI;
    api: {
      shutdownApp: () => Promise<void>;
      restartApp: () => Promise<void>;
      getModPackUrls: () => Promise<string[]>;
      getModPackData: (modPackName: string) => Promise<ModPackInterface>;
      refreshModPackData: () => Promise<string[]>;
    };
  }
}
