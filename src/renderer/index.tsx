import { createRoot } from 'react-dom/client';
import { initializeIcons } from '@fluentui/react/lib/Icons';
import App from './App';

initializeIcons();
const container = document.getElementById('root')!;
const root = createRoot(container);
root.render(<App />);

// calling IPC exposed from preload script
window.electron.ipcRenderer.once('ipc-example', (arg) => {
  // eslint-disable-next-line no-console
  console.log(arg);
});
window.electron.ipcRenderer.sendMessage('ipc-example', ['ping']);