import '@renderer/assets/css/components/FootBar.css';
import Stack from '@mui/material/Stack';
import UserBadge from '../controls/UserBadge';
import { IconButton } from '@mui/material';
import { SettingsInputComposite } from '@mui/icons-material';
import SettingsIcon from '@mui/icons-material/Settings';

export default function FootBar(): JSX.Element {
  return (
    <Stack
      direction={'row'}
      spacing={2}
      justifyContent="center"
      alignItems="center"
      className="FootBar"
    >
      <IconButton color="secondary" aria-label="add an alarm">
        <SettingsInputComposite />
      </IconButton>
      <UserBadge
        offline={false}
        disconnected={false}
        friendlyName={'Friendly'}
        username={'username'}
      />
      <IconButton color="secondary" aria-label="Settings">
        <SettingsIcon />
      </IconButton>
    </Stack>
  );
}
