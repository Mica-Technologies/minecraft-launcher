import '@renderer/assets/css/components/TitleBar.css';
import Stack from '@mui/material/Stack';
import { CSSProperties, useEffect, useState } from 'react';
import { Typography } from '@mui/material';
import { appConstants } from '@common/constants/appConstants';
import { appBarActions, appBarLinks } from '@renderer/components/appBar/MicaAppBar';
import { useLocation } from 'react-router-dom';

type TitleBarProps = {
  titleBarHeight?: string;
  alignment?: 'left' | 'center' | 'right';
};

export default function TitleBar({
  titleBarHeight,
  alignment = 'center',
}: TitleBarProps): JSX.Element {
  const controlPosition = window.electron.process.platform === 'darwin' ? 'left' : 'right'; // macOS has left controls, others on right

  const contentStyle: CSSProperties = {
    height: titleBarHeight || 'env(titlebar-area-height, var(--fallback-title-bar-height))',
    backdropFilter: 'blur(10px)',
    justifyContent:
      alignment === 'center' ? 'center' : alignment === controlPosition ? 'flex-end' : 'flex-start',
  };

  const [pageTitle, setPageTitle] = useState('');
  const currentPath = useLocation();
  useEffect(() => {
    const newTitleDefault = 'Unknown';
    let newTitle = newTitleDefault;

    // Check in links for matching path to set title
    appBarLinks.forEach((link) => {
      if (link.to === currentPath.pathname) {
        newTitle = link.title;
      }
    });

    // If not found in links, check in actions
    if (newTitle === newTitleDefault) {
      appBarActions.forEach((action) => {
        if (action.to === currentPath.pathname) {
          newTitle = action.title;
        }
      });
    }

    setPageTitle(newTitle);
  }, [currentPath]);

  return (
    <Stack
      direction="row"
      spacing={2}
      alignItems="center"
      id="titleBarContainer"
      className="draggable"
      style={contentStyle}
    >
      <Typography variant="subtitle2" component="div">
        {appConstants.APP_NAME} | {pageTitle}
      </Typography>
      <div
        id="titleBar"
        className="TitleBar"
        style={{ backgroundColor: 'red', alignSelf: 'right' }}
      ></div>
    </Stack>
  );
}
