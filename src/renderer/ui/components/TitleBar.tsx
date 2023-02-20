import { isMac, Stack } from '@fluentui/react';
import '../../css/components/TitleBar.css';
import React from 'react';
import TitleBarWindowControls from './TitleBarWindowControls';

export default function TitleBar() {
  const titleBarHideCustomControls = isMac();
  const titleBarStackHorizontalAlign = titleBarHideCustomControls
    ? 'center'
    : 'space-between';

  return (
    <Stack.Item
      className="titleBar"
      style={{ height: 'env(titlebar-area-height)', minHeight: '36px' }}
    >
      <Stack
        verticalFill
        horizontal
        horizontalAlign={titleBarStackHorizontalAlign}
        verticalAlign="center"
      >
        <Stack.Item>
          <p className="titleBarTitle">Mica Minecraft Launcher</p>
        </Stack.Item>
        <TitleBarWindowControls hidden={titleBarHideCustomControls} />
      </Stack>
    </Stack.Item>
  );
}
