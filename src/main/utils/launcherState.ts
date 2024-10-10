import { Mutex } from 'async-mutex';
import { LauncherMode } from '@common/types/LauncherMode';

let mode: LauncherMode = LauncherMode.CLIENT;
let headless: boolean = false;

const modeMutex = new Mutex();
const headlessMutex = new Mutex();

// Thread-safe get method for mode
export async function getLauncherMode(): Promise<LauncherMode> {
  return modeMutex.runExclusive(() => {
    return mode;
  });
}

// Thread-safe set method for mode
export async function setLauncherMode(newMode: LauncherMode): Promise<void> {
  return modeMutex.runExclusive(() => {
    mode = newMode;
  });
}

// Thread-safe get method for headless
export async function isLauncherHeadless(): Promise<boolean> {
  return headlessMutex.runExclusive(() => {
    return headless;
  });
}

// Thread-safe set method for headless
export async function setLauncherHeadless(newHeadless: boolean): Promise<void> {
  return headlessMutex.runExclusive(() => {
    headless = newHeadless;
  });
}
