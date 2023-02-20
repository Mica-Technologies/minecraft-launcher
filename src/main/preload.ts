// Disable no-unused-vars, broken for spread args
/* eslint no-unused-vars: off */
import {
  contextBridge,
  ipcMain,
  ipcRenderer,
  IpcRendererEvent,
  IpcMainEvent,
} from 'electron';
import { Account } from 'minecraft-auth/dist/Account';
import ipcMessagesChannels from './ipc/ipcChannels';
import ipcMessages from './ipc/ipcMessageNames';

const electronHandler = {
  ipcMain: {
    on(channel: ipcMessagesChannels, func: (...args: unknown[]) => void) {
      const subscription = (_event: IpcMainEvent, ...args: unknown[]) =>
        func(...args);
      ipcMain.on(channel, subscription);

      return () => {
        ipcMain.removeListener(channel, subscription);
      };
    },
    once(channel: ipcMessagesChannels, func: (...args: unknown[]) => void) {
      ipcMain.once(channel, (_event, ...args) => func(...args));
    },
    onGetStoredAccounts(func: (account: Account | null) => void) {
      const subscription = (_event: IpcMainEvent, account: Account | null) =>
        func(account);
      ipcMain.on(ipcMessages.GET_STORED_ACCOUNTS, subscription);

      return () => {
        ipcMain.removeListener(ipcMessages.GET_STORED_ACCOUNTS, subscription);
      };
    },
  },
  ipcRenderer: {
    sendMessage(channel: ipcMessagesChannels, args: unknown[]) {
      ipcRenderer.send(channel, args);
    },
    on(channel: ipcMessagesChannels, func: (...args: unknown[]) => void) {
      const subscription = (_event: IpcRendererEvent, ...args: unknown[]) =>
        func(...args);
      ipcRenderer.on(channel, subscription);

      return () => {
        ipcRenderer.removeListener(channel, subscription);
      };
    },
    once(channel: ipcMessagesChannels, func: (...args: unknown[]) => void) {
      ipcRenderer.once(channel, (_event, ...args) => func(...args));
    },
    onStoredAccountsChange(func: (newStoredAccounts: Account[]) => void) {
      const subscription = (
        _event: IpcRendererEvent,
        newStoredAccounts: Account[]
      ) => func(newStoredAccounts);
      ipcRenderer.on(ipcMessages.GIVE_STORED_ACCOUNTS, subscription);

      return () => {
        ipcRenderer.removeListener(
          ipcMessages.GIVE_STORED_ACCOUNTS,
          subscription
        );
      };
    },
  },
};

contextBridge.exposeInMainWorld('electron', electronHandler);

export type ElectronHandler = typeof electronHandler;
