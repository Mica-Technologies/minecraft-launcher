import { Stack } from '@fluentui/react';
import '../../css/components/TitleBarWindowControls.css';
import React from 'react';
import { Button } from '@fluentui/react-components';
import {
  Dismiss12Regular,
  Square12Regular,
  Subtract12Regular,
} from '@fluentui/react-icons';
import ipcMessages from 'main/ipc/ipcMessageNames';

interface TitleBarWindowControlsParams {
  hidden: boolean;
}

export default function TitleBarWindowControls({
  hidden,
}: TitleBarWindowControlsParams) {
  return (
    <Stack.Item className="titleBarWindowControls" hidden={hidden}>
      <Stack verticalFill horizontal>
        <Button
          icon={<Subtract12Regular />}
          title="Minimize"
          aria-label="Minimize"
          className="titleBarWindowControl"
          appearance="subtle"
          shape="square"
          onClick={() => {
            window.electron.ipcRenderer.sendMessage(
              ipcMessages.WINDOW_MINIMIZE,
              []
            );
          }}
        />
        <Button
          icon={<Square12Regular />}
          title="Maximize"
          aria-label="Maximize"
          className="titleBarWindowControl"
          appearance="subtle"
          shape="square"
          onClick={() => {
            window.electron.ipcRenderer.sendMessage(
              ipcMessages.WINDOW_MAXIMIZE,
              []
            );
          }}
        />
        <Button
          icon={<Dismiss12Regular />}
          title="Close"
          aria-label="Close"
          className="titleBarWindowControl titleBarWindowControlClose"
          appearance="subtle"
          shape="square"
          onClick={() => {
            window.electron.ipcRenderer.sendMessage(
              ipcMessages.WINDOW_CLOSE,
              []
            );
          }}
        />
      </Stack>
    </Stack.Item>
  );
}
