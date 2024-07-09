import fs from 'fs';
import path from 'path';
import { Mutex } from 'async-mutex';

let baseDirectory: string = process.cwd();
const baseDirectoryMutex = new Mutex();

export async function getBaseDirectory(): Promise<string> {
    return baseDirectoryMutex.runExclusive(() => {
        return baseDirectory;
    });
}

export async function setBaseDirectory(directory: string): Promise<void> {
    return baseDirectoryMutex.runExclusive(() => {
        baseDirectory = directory;
    });
}

export function readFile(filePath: string): Promise<string> {
    const absolutePath = path.join(baseDirectory, filePath);
    return new Promise((resolve, reject) => {
        fs.readFile(absolutePath, 'utf8', (err, data) => {
            if (err) {
                reject(err);
            } else {
                resolve(data);
            }
        });
    });
}

export function writeFile(filePath: string, content: string): Promise<void> {
    const absolutePath = path.join(baseDirectory, filePath);
    return new Promise((resolve, reject) => {
        fs.writeFile(absolutePath, content, 'utf8', (err) => {
            if (err) {
                reject(err);
            } else {
                resolve();
            }
        });
    });
}

export function overwriteFile(filePath: string, content: string): Promise<void> {
    // Overwrite is essentially the same as writeFile in this context
    return writeFile(filePath, content);
}

export function deleteFile(filePath: string): Promise<void> {
    const absolutePath = path.join(baseDirectory, filePath);
    return new Promise((resolve, reject) => {
        fs.unlink(absolutePath, (err) => {
            if (err) {
                reject(err);
            } else {
                resolve();
            }
        });
    });
}

// JSON-specific functions

export async function readJsonFile<T>(filePath: string): Promise<T> {
    const data = await readFile(filePath);
    try {
        return JSON.parse(data);
    } catch (error: unknown) {
        if (error instanceof Error) {
            throw new Error(`Invalid JSON in file ${filePath}: ${error.message}`);
        } else {
            throw new Error(`Invalid JSON in file ${filePath}`);
        }
    }
}

export async function writeJsonFile<T>(filePath: string, jsonObject: T, indentSize?: number): Promise<void> {
    const content = JSON.stringify(jsonObject, null, indentSize ?? 0); // Pretty-print JSON with specified indent size or minimized
    await writeFile(filePath, content);
}

export async function overwriteJsonFile<T>(filePath: string, jsonObject: T, indentSize?: number): Promise<void> {
    // Overwrite is essentially the same as writeJsonFile in this context
    await writeJsonFile(filePath, jsonObject, indentSize);
}