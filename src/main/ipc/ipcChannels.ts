import ipcMessages from './ipcMessageNames';

type ipcMessagesChannels =
  | ipcMessages.WINDOW_MINIMIZE
  | ipcMessages.WINDOW_MAXIMIZE
  | ipcMessages.WINDOW_CLOSE
  | ipcMessages.LOGIN_MS
  | ipcMessages.GIVE_STORED_ACCOUNTS
  | ipcMessages.GET_STORED_ACCOUNTS
  | ipcMessages.LOGOUT
  | ipcMessages.LOAD_SETTINGS
  | ipcMessages.SAVE_SETTINGS;

export default ipcMessagesChannels;
