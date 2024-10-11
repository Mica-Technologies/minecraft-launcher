import '@renderer/assets/css/components/RootUiStack.css';
import {
  Avatar,
  Badge,
  BadgeProps,
  Divider,
  IconButton,
  ListItemIcon,
  Menu,
  MenuItem,
  styled,
  Tooltip,
  Typography,
} from '@mui/material';
import { grey, orange, red } from '@mui/material/colors';
import { Sync } from '@mui/icons-material';
import React from 'react';
import Stack from '@mui/material/Stack';

/**
 * Props for UserBadge component
 * @param {string} username - The username of the user
 * @param {boolean} offline - The offline status of the user
 * @param {boolean} disconnected - The disconnected status of the user
 */
type UserBadgeProps = {
  username: string;
  friendlyName: string;
  offline: boolean;
  disconnected: boolean;
};

/**
 * Props for StyledBadge component
 * @param {boolean} offline - The offline status of the user
 * @param {boolean} disconnected - The disconnected status of the user
 */
interface StyledBadgeProps extends BadgeProps {
  offline: number;
  disconnected: number;
}

/**
 * StyledBadge component to handle online/offline/disconnected status
 * @param {StyledBadgeProps} props - The props for the component
 * @returns {JSX.Element} The styled badge component
 * @see https://mui.com/components/badges/
 */
const StyledBadge = styled(Badge)<StyledBadgeProps>(({ theme, offline, disconnected }) => ({
  '& .MuiBadge-badge': {
    backgroundColor: offline ? grey[500] : disconnected ? red[500] : '#44b700',
    color: offline ? grey[500] : disconnected ? red[500] : '#44b700',
    boxShadow: offline
      ? 'none'
      : disconnected
        ? 'none'
        : `0 0 0 2px ${theme.palette.background.paper}`,
    '&::after': {
      position: 'absolute',
      top: 0,
      left: 0,
      width: '100%',
      height: '100%',
      borderRadius: '50%',
      animation: offline || disconnected ? 'none' : 'ripple 1.2s infinite ease-in-out',
      border: '1px solid currentColor',
      content: '""',
    },
  },
  '@keyframes ripple': {
    '0%': {
      transform: 'scale(.8)',
      opacity: 1,
    },
    '100%': {
      transform: 'scale(2.4)',
      opacity: 0,
    },
  },
}));

export default function UserBadge({
  username,
  friendlyName,
  offline,
  disconnected,
}: UserBadgeProps): JSX.Element {
  const [anchorElUser, setAnchorElUser] = React.useState<null | HTMLElement>(null);

  const handleOpenUserMenu = (event: React.MouseEvent<HTMLElement>): void => {
    setAnchorElUser(event.currentTarget);
  };

  const handleCloseUserMenu = (): void => {
    setAnchorElUser(null);
  };

  // Determine avatar tooltip based on connection status
  const avatarTooltip = offline
    ? friendlyName + ' (Offline)'
    : disconnected
      ? friendlyName + ' (Disconnected)'
      : friendlyName + ' (Online)';

  return (
    <>
      <Tooltip title={avatarTooltip}>
        <IconButton onClick={handleOpenUserMenu} sx={{ p: 0 }}>
          <Stack direction="row" spacing={1}>
            <StyledBadge
              overlap="circular"
              anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
              variant="dot"
              offline={offline ? 1 : 0} // Pass the custom prop
              disconnected={disconnected ? 1 : 0} // Pass the custom prop
            >
              <Avatar alt={friendlyName} sx={{ bgcolor: orange[500], width: 50, height: 50 }}>
                <Sync />
              </Avatar>
            </StyledBadge>

            <Stack direction="column" spacing={1}>
              <Typography variant={'h6'}>{friendlyName}</Typography>
              <Typography variant={'body1'}>{username}</Typography>
            </Stack>
          </Stack>
        </IconButton>
      </Tooltip>
      <Menu
        sx={{ mt: '45px' }}
        id="menu-appbar"
        anchorEl={anchorElUser}
        anchorOrigin={{
          vertical: 'top',
          horizontal: 'right',
        }}
        keepMounted
        transformOrigin={{
          vertical: 'top',
          horizontal: 'right',
        }}
        open={Boolean(anchorElUser)}
        onClose={handleCloseUserMenu}
      >
        <MenuItem>
          <ListItemIcon>
            <Sync />
          </ListItemIcon>
          <Typography textAlign="center">Example 1</Typography>
        </MenuItem>

        <MenuItem>
          <ListItemIcon>
            <Sync />
          </ListItemIcon>
          <Typography textAlign="center">Example 2</Typography>
        </MenuItem>

        <Divider />

        <MenuItem>
          <ListItemIcon>
            <Sync />
          </ListItemIcon>
          <Typography textAlign="center">{username}</Typography>
        </MenuItem>
      </Menu>
    </>
  );
}
