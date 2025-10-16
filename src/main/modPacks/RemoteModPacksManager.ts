// RemoteModPacksManager.ts
import fs from 'fs';
import path from 'path';
import axios from 'axios';
import ModPackInterface from '@common/types/ModPackManifest';
import FileManager from '@main/files/FileManager';
import log from 'electron-log/main';

class RemoteModPacksManager {
  // Static configuration
  private static MOD_PACKS_URL =
    'https://github.com/Mica-Technologies/minecraft-launcher-modpacks/raw/refs/heads/main/installable.json'; // URL to get mod pack URLs
  private static CACHE_CONTEXT = 'modpacks'; // Context for caching

  // Holds cached mod pack data
  private modPackData: { [key: string]: ModPackInterface } = {};

  // Fetches available mod pack URLs from remote server
  public async fetchAvailableModPackUrls(): Promise<string[]> {
    try {
      const response = await axios.get(RemoteModPacksManager.MOD_PACKS_URL);
      log.debug(response.data.available);
      const urls: string[] = response.data.available;
      return urls;
    } catch (error) {
      console.error('Failed to fetch available mod pack URLs:', error);
      return [];
    }
  }

  // Fetches mod pack JSON data from each URL and caches it
  public async fetchAndCacheModPackData(urls: string[]): Promise<void> {
    for (const url of urls) {
      try {
        const response = await axios.get(url);
        const modPackName = path.basename(url, path.extname(url));
        const cacheDir = FileManager.getAppCacheDirPath(RemoteModPacksManager.CACHE_CONTEXT);
        const cacheFilePath = path.join(cacheDir, `${modPackName}.json`);
        fs.writeFileSync(cacheFilePath, JSON.stringify(response.data, null, 2));
        this.modPackData[modPackName] = response.data;
      } catch (error) {
        console.error(`Failed to fetch mod pack data from ${url}:`, error);
      }
    }
  }

  // Loads cached mod pack data
  public loadCachedModPacks(): void {
    const cacheDir = FileManager.getAppCacheDirPath(RemoteModPacksManager.CACHE_CONTEXT);
    const files = fs.readdirSync(cacheDir);
    for (const file of files) {
      const filePath = path.join(cacheDir, file);
      const modPackName = path.basename(file, path.extname(file));
      const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
      this.modPackData[modPackName] = data;
    }
  }

  // Gets mod pack data
  public getModPackData(): { [key: string]: ModPackInterface } {
    return this.modPackData;
  }

  // Refreshes mod pack data (fetch from server and cache)
  public async refreshModPackData(): Promise<void> {
    const urls = await this.fetchAvailableModPackUrls();
    await this.fetchAndCacheModPackData(urls);
  }
}

// Export instance to be used across the app
const remoteModPacksManager = new RemoteModPacksManager();
export default remoteModPacksManager;
