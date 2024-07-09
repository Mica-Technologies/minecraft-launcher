import * as os from 'os';

/**
 * The path to the user's home folder.
 */
export const userHomeFolder = os.homedir();

/**
 * The path to the launcher root folder.
 */
export const launcherRootFolder = `${userHomeFolder}/.MicaMinecraftLauncher`;

/**
 * The path to the launcher configuration folder.
 */
export const launcherConfigurationFolder = `${launcherRootFolder}/configuration`;

/**
 * The path to the launcher settings file.
 */
export const launcherSettingsFile = `${launcherConfigurationFolder}/launcherSettings.json`;

/**
 * The path to the launcher cache folder.
 */
export const launcherCacheFolder = `${launcherRootFolder}/cache`;

/**
 * The path to the launcher state cache file.
 */
export const launcherStateCacheFile = `${launcherCacheFolder}/launcherState.json`;

/**
 * The path to the launcher auth cache file.
 */
export const launcherAuthCacheFile = `${launcherCacheFolder}/launcherAuth.json`;

/**
 * The path to the launcher mod packs folder.
 */
export const launcherModPacksFolder = `${launcherRootFolder}/modPacks`;

/**
 * Export default.
 */
export default {
  userHomeFolder,
  launcherRootFolder,
  launcherConfigurationFolder,
  launcherSettingsFile,
  launcherCacheFolder,
  launcherStateCacheFile,
  launcherAuthCacheFile,
  launcherModPacksFolder,
};