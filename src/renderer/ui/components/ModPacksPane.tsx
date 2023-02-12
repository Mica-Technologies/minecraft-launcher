import '../../css/components/ModPacksPane.css';
import React from 'react';
import { Stack } from '@fluentui/react';
import { Button } from '@fluentui/react-components';
import { BookOpen16Regular, Desktop16Regular } from '@fluentui/react-icons';
import icon from '../../../../assets/icon.svg';

export default function ModPacksPane() {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      horizontalAlign="center"
      className="modPacksPane"
    >
      <Stack.Item>
        <div className="Hello">
          <img width="200" alt="icon" src={icon} />
        </div>
        <h1>Mica Minecraft Launcher</h1>
        <div className="Hello">
          <a
            href="https://minecraft.micatechnologies.com/launcher.html"
            target="_blank"
            rel="noreferrer"
          >
            <Button icon={<BookOpen16Regular />}>Learn More</Button>
          </a>
          <a
            href="https://github.com/mica-technologies/minecraft-launcher"
            target="_blank"
            rel="noreferrer"
          >
            <Button icon={<Desktop16Regular />}>Source Code</Button>
          </a>
        </div>
      </Stack.Item>
    </Stack>
  );
}
