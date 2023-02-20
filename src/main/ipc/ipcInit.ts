import { BrowserWindow, ipcMain, shell } from 'electron';
import { AccountsStorage } from 'minecraft-auth/dist/AccountStorage';
import { MicrosoftAccount } from 'minecraft-auth/dist/MicrosoftAuth/MicrosoftAccount';
import { MicrosoftAuth } from 'minecraft-auth';
import ipcMessages from './ipcMessageNames';

type ipcInitProps = {
  mainWindow: BrowserWindow;
  accountsStorage: AccountsStorage | null;
};

export default function ipcInit({ mainWindow, accountsStorage }: ipcInitProps) {
  ipcMain.on(ipcMessages.LOAD_SETTINGS, () => {});

  ipcMain.on(ipcMessages.SAVE_SETTINGS, () => {});

  ipcMain.on(ipcMessages.WINDOW_MINIMIZE, () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      } else {
        mainWindow.minimize();
      }
    }
  });

  ipcMain.on(ipcMessages.WINDOW_MAXIMIZE, () => {
    if (mainWindow) {
      if (mainWindow.isMaximized()) {
        mainWindow.unmaximize();
      } else {
        mainWindow.maximize();
      }
    }
  });

  ipcMain.on(ipcMessages.WINDOW_CLOSE, () => {
    if (mainWindow) {
      mainWindow.close();
    }
  });

  // Event handlers
  ipcMain.on(ipcMessages.LOGIN_MS, async () => {
    const account = new MicrosoftAccount();

    MicrosoftAuth.setup({ appID: 'e947042e-fc02-42c5-8159-950cde78ea75' });

    await shell.openExternal(MicrosoftAuth.createUrl());

    const code = await MicrosoftAuth.listenForCode({
      redirectAfterAuth:
        'https://minecraft.micatechnologies.com/launcher-logged-in.html',
    });
    if (code !== undefined) {
      await account.authFlow(code);
    }
    await account.getProfile();
    await account.checkOwnership();

    if (accountsStorage) {
      // Add account if it doesn't exist
      if (!accountsStorage.accountList.some((a) => a.uuid === account.uuid)) {
        accountsStorage.addAccount(account);
      } else {
        console.log('Account already exists, updating...');
        // Remove old account and add new one
        accountsStorage.accountList = accountsStorage.accountList.filter(
          (a) => a.uuid !== account.uuid
        );
        accountsStorage.addAccount(account);
      }
      mainWindow.webContents.send(
        ipcMessages.GIVE_STORED_ACCOUNTS,
        accountsStorage.accountList
      );
    } else {
      mainWindow.webContents.send(ipcMessages.GIVE_STORED_ACCOUNTS, [account]);
    }
  });

  ipcMain.on(ipcMessages.LOGOUT, async (event, args) => {
    if (accountsStorage) {
      const accountByUUID = accountsStorage.getAccountByUUID(args[0]);
      if (accountByUUID) {
        accountsStorage.deleteAccount(accountByUUID);
      }
      mainWindow.webContents.send(
        ipcMessages.GIVE_STORED_ACCOUNTS,
        accountsStorage.accountList
      );
    } else {
      mainWindow.webContents.send(ipcMessages.GIVE_STORED_ACCOUNTS, []);
    }
  });

  ipcMain.on(ipcMessages.GET_STORED_ACCOUNTS, async () => {
    if (accountsStorage) {
      mainWindow.webContents.send(
        ipcMessages.GIVE_STORED_ACCOUNTS,
        accountsStorage.accountList
      );
    } else {
      mainWindow.webContents.send(ipcMessages.GIVE_STORED_ACCOUNTS, []);
    }
  });
}
