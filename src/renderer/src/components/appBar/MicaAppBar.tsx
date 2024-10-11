import React, { ReactElement, useState } from 'react';
import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Container from '@mui/material/Container';
import Divider from '@mui/material/Divider';
import MenuItem from '@mui/material/MenuItem';
import Drawer from '@mui/material/Drawer';
import MenuIcon from '@mui/icons-material/Menu';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import SettingsIcon from '@mui/icons-material/Settings';
import ColorModeIconDropdown from '../shared-theme/ColorModeIconDropdown';
import { useNavigate } from 'react-router';
import { styled, alpha, useTheme } from '@mui/material/styles';
import './MicaAppBar.css';
import SwitchAccountIcon from '@mui/icons-material/SwitchAccount';
import Logo from '@renderer/components/logo/Logo';

// Type for navigation links in the app bar
export interface IMicaAppBarLink {
  title: string;
  to: string;
  showOnMobile?: boolean;
  showOnDesktop?: boolean;
  buttonProps?: Omit<React.ComponentProps<typeof Button>, 'component' | 'to' | 'children'>;
}

// Type for action buttons in the app bar (icon buttons)
export interface IMicaAppBarAction {
  title: string;
  to: string;
  icon: React.ReactNode; // <-- Add icon prop
  showOnMobile?: boolean;
  showOnDesktop?: boolean;
  buttonProps?: Omit<React.ComponentProps<typeof IconButton>, 'component' | 'to' | 'children'>;
}

// List of navigation links
export const appBarLinks: IMicaAppBarLink[] = [
  { title: 'Home', to: '/' },
  { title: 'Installs', to: '/installs' },
  { title: 'Discover', to: '/discover' },
];

// List of action buttons (icon buttons)
const appBarActions: IMicaAppBarAction[] = [
  {
    title: 'Account',
    to: '/account',
    icon: <SwitchAccountIcon />, // <-- Use gear icon
    buttonProps: { color: 'primary', size: 'small' },
  },
  {
    title: 'Configure',
    to: '/configure',
    icon: <SettingsIcon />, // <-- Use gear icon
    buttonProps: { color: 'primary', size: 'small' },
  },
];

// Custom styled toolbar for the app bar
const ThemedToolbar = styled(Toolbar)(({ theme }) => ({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  flexShrink: 0,
  borderRadius: `calc(${theme.shape.borderRadius}px + 8px)`,
  backdropFilter: 'blur(24px)',
  border: '1px solid',
  borderColor: theme.palette.divider,
  backgroundColor: alpha(theme.palette.background.default, 0.4),
  boxShadow: theme.shadows[1],
  padding: '8px 12px',
}));

// Elegant scroll-to-top utility for reuse
export const elegantScrollToTop = (): void => {
  if (typeof window !== 'undefined') {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } else {
    console.warn('Scroll to top is not supported in this environment.');
  }
};

export default function MicaAppBar(): ReactElement {
  // State for mobile drawer open/close
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const theme = useTheme();

  // Toggle mobile drawer
  const toggleDrawer = (newOpen: boolean) => (): void => setOpen(newOpen);

  // Helper to scroll to top and then navigate
  const handleNavigate = (to: string): void => {
    elegantScrollToTop();
    navigate(to);
  };

  return (
    <AppBar
      position="fixed"
      enableColorOnDark
      sx={{
        boxShadow: 0,
        bgcolor: 'transparent',
        backgroundImage: 'none',
        mt: 'calc(var(--template-frame-height, 0px) + 28px)',
        top: 'auto',
        bottom: 'calc(var(--template-frame-height, 0px) + 28px)',
      }}
      className="AppBarRoot"
    >
      <Container maxWidth="lg">
        <ThemedToolbar disableGutters className="AppBarToolbar">
          {/* Logo and navigation links */}
          <div className="AppBarLogoLinks">
            {/*<HeaderLogo />*/}
            <div style={{ width: 40, height: 40, marginRight: 8 }}>
              <Logo greenify={true} />
            </div>
            <div className="AppBarLinks">
              {appBarLinks.map((link) => (
                <Button
                  key={link.title}
                  color="info"
                  size="small"
                  className="AppBarLinkButton"
                  type="button"
                  onClick={() => handleNavigate(link.to)}
                  {...link.buttonProps}
                >
                  {link.title}
                </Button>
              ))}
            </div>
          </div>
          {/* Desktop action buttons (icon buttons) */}
          <div className="AppBarActions">
            {appBarActions.map((action) => (
              <IconButton
                key={action.title}
                className="AppBarActionButton"
                type="button"
                onClick={() => handleNavigate(action.to)}
                {...action.buttonProps}
                aria-label={action.title}
              >
                {action.icon}
              </IconButton>
            ))}
            <ColorModeIconDropdown />
          </div>
          {/* Mobile menu and color mode toggle */}
          <div className="AppBarMobileMenu">
            {appBarActions.map((action) => (
              <IconButton
                key={action.title}
                className="AppBarActionButton"
                type="button"
                onClick={() => handleNavigate(action.to)}
                {...action.buttonProps}
                aria-label={action.title}
                size="medium"
              >
                {action.icon}
              </IconButton>
            ))}
            <ColorModeIconDropdown size="medium" />
            <IconButton aria-label="Menu button" onClick={toggleDrawer(true)}>
              <MenuIcon />
            </IconButton>
            <Drawer
              anchor="bottom"
              open={open}
              onClose={toggleDrawer(false)}
              PaperProps={{
                className: 'AppBarDrawerPaper',
                sx: {
                  bottom: 'var(--template-frame-height, 0px)',
                },
              }}
            >
              <div
                className="AppBarDrawerContent"
                style={{
                  padding: 16,
                  backgroundColor: theme.palette.background.default,
                }}
              >
                <div className="AppBarDrawerClose">
                  <IconButton onClick={toggleDrawer(false)}>
                    <CloseRoundedIcon />
                  </IconButton>
                </div>
                {/* Drawer navigation links */}
                {appBarLinks.map((link) => (
                  <MenuItem
                    key={link.title}
                    onClick={() => {
                      setOpen(false);
                      elegantScrollToTop();
                      navigate(link.to);
                    }}
                    className="AppBarDrawerMenuItem"
                  >
                    {link.title}
                  </MenuItem>
                ))}
                <Divider
                  className="AppBarDrawerDivider"
                  sx={{
                    my: 3,
                    borderColor: theme.palette.divider,
                  }}
                />
                {/* Drawer action buttons (icon + label) */}
                {appBarActions.map((action) => (
                  <MenuItem
                    key={action.title}
                    onClick={() => {
                      setOpen(false);
                      elegantScrollToTop();
                      navigate(action.to);
                    }}
                    className="AppBarDrawerMenuItem"
                  >
                    <IconButton
                      edge="start"
                      size="small"
                      aria-label={action.title}
                      sx={{ mr: 1 }}
                      {...action.buttonProps}
                    >
                      {action.icon}
                    </IconButton>
                    {action.title}
                  </MenuItem>
                ))}
              </div>
            </Drawer>
          </div>
        </ThemedToolbar>
      </Container>
    </AppBar>
  );
}
