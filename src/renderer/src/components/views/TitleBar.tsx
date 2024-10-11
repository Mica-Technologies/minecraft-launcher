import '@renderer/assets/css/components/TitleBar.css';
import Stack from '@mui/material/Stack';
import { ReactNode, CSSProperties } from 'react';

type TitleBarProps = {
  children: ReactNode | ReactNode[];
  titleBarHeight?: string;
  alignment?: 'left' | 'center' | 'right';
};

export default function TitleBar({
  children,
  titleBarHeight,
  alignment = 'center',
}: TitleBarProps): JSX.Element {
  const controlPosition = window.electron.process.platform === 'darwin' ? 'left' : 'right'; // macOS has left controls, others on right

  const contentStyle: CSSProperties = {
    height: titleBarHeight || 'env(titlebar-area-height, var(--fallback-title-bar-height))',
    backgroundColor: 'orange',
    justifyContent:
      alignment === 'center' ? 'center' : alignment === controlPosition ? 'flex-end' : 'flex-start',
  };

  return (
    <Stack
      direction="row"
      spacing={2}
      alignItems="center"
      id="titleBarContainer"
      className="draggable"
      style={contentStyle}
    >
      {children}
      <div
        id="titleBar"
        className="TitleBar"
        style={{ backgroundColor: 'red', alignSelf: 'right' }}
      ></div>
    </Stack>
  );
}
