import { ipcMain } from 'electron'

export function setupIpc(): void {
    ipcMain.on('ping', () => console.log('pong'))
}