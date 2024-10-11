import electronLogo from '@renderer/assets/electron.svg';
import Stack from '@mui/material/Stack';
import Button from '@mui/material/Button';
import { useNavigate } from 'react-router';

function Configure(): JSX.Element {
  const navigate = useNavigate();
  return (
    <>
      <img alt="logo" className="logo" src={electronLogo} />
      <div className="creator">SETTINGS PAGE</div>
      <div className="text">
        Build a <span className="react">Minecraft</span> Launcher with&nbsp;
        <span className="ts">React and TypeScript</span>
      </div>
      <div className="actions">
        <Stack spacing={2} direction="row">
          <a
            target="_blank"
            rel="noreferrer"
            onClick={(): void => navigate('/page-does-not-exist')}
          >
            <Button variant="outlined">Test Error Page</Button>
          </a>
        </Stack>
      </div>
    </>
  );
}

export default Configure;
