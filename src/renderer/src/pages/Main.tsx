import Versions from '@renderer/components/Versions';
import electronLogo from '@renderer/assets/electron.svg';
import Stack from '@mui/material/Stack';
import Button from '@mui/material/Button';
import RootUiStack from '@renderer/components/containers/RootUiStack';

function Main(): JSX.Element {
  const ipcHandle = (): void => window.electron.ipcRenderer.send('ping');

  return (
    <RootUiStack>
      <img alt="logo" className="logo" src={electronLogo} />
      <div className="creator">Powered by electron-vite</div>
      <div className="text">
        Build an Electron app with <span className="react">React</span>
        &nbsp;and <span className="ts">TypeScript</span>
      </div>
      <p className="tip">
        Please try pressing <code>F12</code> to open the devTool
      </p>
      <div className="actions">
        <Stack spacing={2} direction="row">
          <a href="#about" rel="noreferrer">
            <Button variant="outlined">About</Button>
          </a>
          <a target="_blank" rel="noreferrer" onClick={ipcHandle}>
            <Button variant="outlined">Send IPC</Button>
          </a>
        </Stack>
      </div>
      <Versions></Versions>
    </RootUiStack>
  );
}

export default Main;
