import path from 'path';
import os from 'os';

export const LAUNCHER_ROOT_SERVER: string = process.cwd();
export const LAUNCHER_ROOT_CLIENT: string = path.join(os.homedir(), '.MicaMinecraftLauncher');
