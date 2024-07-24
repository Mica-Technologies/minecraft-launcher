import { promises as fs } from 'fs'
import path from 'path'
import { Mutex } from 'async-mutex'

let baseDirectory: string = process.cwd()
const baseDirectoryMutex = new Mutex()

export async function getBaseDirectory(): Promise<string> {
  return baseDirectoryMutex.runExclusive(() => {
    return baseDirectory
  })
}

export async function setBaseDirectory(directory: string): Promise<void> {
  return baseDirectoryMutex.runExclusive(() => {
    baseDirectory = directory
  })
}

export async function readFileAsString(
  filePath: string,
  encoding: BufferEncoding = 'utf8'
): Promise<string> {
  const baseDirectory = await getBaseDirectory()
  const absolutePath = path.join(baseDirectory, filePath)
  return await fs.readFile(absolutePath, encoding)
}

export async function writeFileAsString(
  filePath: string,
  content: string,
  encoding: BufferEncoding = 'utf8'
): Promise<void> {
  const baseDirectory = await getBaseDirectory()
  const absolutePath = path.join(baseDirectory, filePath)
  await fs.writeFile(absolutePath, content, encoding)
}

export async function overwriteFileAsString(
  filePath: string,
  content: string,
  encoding: BufferEncoding = 'utf8'
): Promise<void> {
  // Overwrite is essentially the same as writeFile in this context
  return writeFileAsString(filePath, content, encoding)
}

export async function deleteFile(filePath: string): Promise<void> {
  const absolutePath = path.join(baseDirectory, filePath)
  await fs.unlink(absolutePath)
}

// JSON-specific functions

export async function readFileAsJson<T>(filePath: string): Promise<T> {
  const data = await readFileAsString(filePath)
  return JSON.parse(data)
}

export async function writeFileAsJson<T>(
  filePath: string,
  jsonObject: T,
  indentSize: number = 0
): Promise<void> {
  // Pretty-print JSON with specified indent size or minimized if indent size is 0
  const content = JSON.stringify(jsonObject, null, indentSize)
  await writeFileAsString(filePath, content)
}

export async function overwriteJsonFile<T>(
  filePath: string,
  jsonObject: T,
  indentSize: number = 0
): Promise<void> {
  // Overwrite is essentially the same as writeJsonFile in this context
  await writeFileAsJson(filePath, jsonObject, indentSize)
}
