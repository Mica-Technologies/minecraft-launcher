import fs from 'fs';

export default class FileManager {
  private static readonly APP_ROOT_DIR_NAME = '.MicaMC';
  private static readonly APP_CACHE_DIR_NAME = 'cache';
  private static readonly USER_HOME_PATH = process.env.HOME
    ? process.env.HOME
    : process.env.USERPROFILE;
  private static readonly APP_ROOT_DIR_PATH = `${FileManager.USER_HOME_PATH}/${FileManager.APP_ROOT_DIR_NAME}`;
  private static readonly APP_CACHE_DIR_PATH = `${FileManager.APP_ROOT_DIR_PATH}/${FileManager.APP_CACHE_DIR_NAME}`;

  public static getAppRootDirPath(): string {
    return FileManager.APP_ROOT_DIR_PATH;
  }

  public static ensureAppRootDirExists(): void {
    if (!fs.existsSync(FileManager.APP_ROOT_DIR_PATH)) {
      fs.mkdirSync(FileManager.APP_ROOT_DIR_PATH);
    }
  }

  public static getAppCacheDirPath(optionalContext?: string): string {
    // Get cache directory path, optionally within a subdirectory for context (e.g., 'modpacks', 'versions')
    const appCacheDirPath = optionalContext
      ? `${FileManager.APP_CACHE_DIR_PATH}/${optionalContext}`
      : FileManager.APP_CACHE_DIR_PATH;

    // Ensure cache directory (and context subdirectory if provided) exists
    if (!fs.existsSync(appCacheDirPath)) {
      fs.mkdirSync(appCacheDirPath, { recursive: true });
    }
    return appCacheDirPath;
  }
}
