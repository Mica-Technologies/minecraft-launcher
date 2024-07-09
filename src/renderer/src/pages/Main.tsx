import Versions from '@renderer/components/Versions'
import electronLogo from '@renderer/assets/electron.svg'
import backgroundImage from '@renderer/assets/background-devtemp.png'
import Stack from '@mui/material/Stack'
import Button from '@mui/material/Button'

function Main(): JSX.Element {
  const ipcHandle = (): void => window.electron.ipcRenderer.send('ping')

  return (
    <Stack
      spacing={2}
      justifyContent="center"
      alignItems="center"
      style={{
        backgroundImage: `url(${backgroundImage})`,
        backgroundSize: 'cover',
        width: '100vw',
        height: '100vh'
      }}
    >
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
          <a href="#about"rel="noreferrer">
            <Button variant="outlined">About</Button>
          </a>
          <a target="_blank" rel="noreferrer" onClick={ipcHandle}>
            <Button variant="outlined">Send IPC</Button>
          </a>
        </Stack>
      </div>
      <Versions></Versions>
    </Stack>
  )
}

export default Main
