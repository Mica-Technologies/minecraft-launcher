import '../../css/components/SettingsPane.css';
import React from 'react';
import { Stack } from '@fluentui/react';

export default function SettingsPane() {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      horizontalAlign="center"
      className="settingsPane"
    >
      <Stack.Item>
        <p>SettingsPane</p>
      </Stack.Item>
    </Stack>
  );
}
