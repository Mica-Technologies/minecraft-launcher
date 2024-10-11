import SvgIcon from '@mui/material/SvgIcon';
import Logo from './Logo';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import './HeaderLogo.css';
import { ReactElement } from 'react';

export default function HeaderLogo(): ReactElement {
  return (
    <Stack direction="row" spacing={2} className="headerLogoContainer">
      <SvgIcon className={'headerLogoImage'}>
        <Logo greenify={true} />
      </SvgIcon>
      <Typography component="h1" variant="h6" className={'headerLogoText'}>
        Mica Minecraft
      </Typography>
    </Stack>
  );
}
