import fs from 'fs';
import { download } from 'electron-dl';
import { BrowserWindow } from 'electron';

/**
 * The file protocol prefix. This must be used when reading/writing files
 * from the file system.
 */
export const fileProtocol = '';

/**
 * The default encoding to use when reading/writing files.
 */
export const defaultEncoding = 'utf-8';

/**
 * Returns the given path with the file protocol prefix if it doesn't already
 * have it.
 * @param path The path to guard.
 * @returns The guarded path.
 */
export const getFileProtocolGuardedPath = (path: string): string => {
  return path.startsWith(fileProtocol) ? path : `${fileProtocol}${path}`;
};

/**
 * Reads the file at the given path synchronously.
 * @param path The path to the file to read.
 * @returns The contents of the file.
 */
export const readFileSync = (path: string): string => {
  return fs.readFileSync(getFileProtocolGuardedPath(path), defaultEncoding);
};

/**
 * Reads the file at the given path asynchronously.
 * @param path The path to the file to read.
 * @returns A promise that resolves with the contents of the file.
 */
export const readFileAsync = (path: string): Promise<string> => {
  return new Promise((resolve, reject) => {
    fs.readFile(
      getFileProtocolGuardedPath(path),
      defaultEncoding,
      (err, data) => {
        if (err) {
          reject(err);
        } else {
          resolve(data);
        }
      }
    );
  });
};

/**
 * Writes the given data to the file at the given path synchronously.
 * @param path The path to the file to write.
 * @param data The data to write to the file.
 */
export const writeFileSync = (path: string, data: string) => {
  // Create the directory if it doesn't exist
  const dir = path.substring(0, path.lastIndexOf('/'));
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.chmodSync(dir, '755');
  console.log(`Writing file: ${getFileProtocolGuardedPath(path)}`);
  return fs.writeFileSync(
    getFileProtocolGuardedPath(path),
    data,
    defaultEncoding
  );
};

/**
 * Writes the given data to the file at the given path asynchronously.
 * @param path The path to the file to write.
 * @param data The data to write to the file.
 */
export const writeFileAsync = (path: string, data: string): Promise<void> => {
  // Create the directory if it doesn't exist
  const dir = path.substring(0, path.lastIndexOf('/'));
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  return new Promise((resolve, reject) => {
    fs.writeFile(
      getFileProtocolGuardedPath(path),
      data,
      defaultEncoding,
      (err) => {
        if (err) {
          reject(err);
        } else {
          resolve();
        }
      }
    );
  });
};

/**
 * Checks if the file at the given path exists synchronously.
 * @param path The path to the file to check.
 */
export const fileExistsSync = (path: string): boolean => {
  return fs.existsSync(getFileProtocolGuardedPath(path));
};

export const fileExistsAsync = (path: string): Promise<boolean> => {
  return new Promise((resolve) => {
    fs.exists(getFileProtocolGuardedPath(path), (exists) => {
      resolve(exists);
    });
  });
};

/**
 * Deletes the file at the given path synchronously.
 * @param path The path to the file to delete.
 */
export const deleteFileSync = (path: string) => {
  if (fileExistsSync(path)) {
    fs.unlinkSync(getFileProtocolGuardedPath(path));
  }
};

/**
 * Deletes the file at the given path asynchronously.
 * @param path The path to the file to delete.
 */
export const deleteFileAsync = (path: string): Promise<void> => {
  if (fileExistsSync(path)) {
    return new Promise((resolve, reject) => {
      fs.unlink(getFileProtocolGuardedPath(path), (err) => {
        if (err) {
          reject(err);
        } else {
          resolve();
        }
      });
    });
  }
  return Promise.resolve();
};

export const downloadUrlToFileSync = (
  url: string,
  path: string,
  encoding: string = defaultEncoding
) => {
  const parentWindow =
    BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0];
  download(parentWindow, url, {});
};

export const downloadUrlToFileAsync = (
  url: string,
  path: string,
  encoding: string = defaultEncoding
): Promise<void> => {
  const parentWindow =
    BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0];
  return download(parentWindow, url, {});
};

export const createDirectoryAsync = (path: string): Promise<void> => {
  return new Promise((resolve, reject) => {
    fs.mkdir(getFileProtocolGuardedPath(path), (err) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
};

/**
 * Default export.
 */
export default {
  fileProtocol,
  defaultEncoding,
  getFileProtocolGuardedPath,
  readFileSync,
  readFileAsync,
  writeFileSync,
  writeFileAsync,
  fileExistsSync,
  fileExistsAsync,
};
