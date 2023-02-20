import '../../css/components/SettingsPane.css';
import React from 'react';
import { Stack } from '@fluentui/react';
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Title3,
} from '@fluentui/react-components';

export default function SettingsPane() {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      horizontalAlign="center"
      className="settingsPane"
    >
      <Stack.Item className="settingsPaneArea">
        <Title3>Launcher Settings</Title3>
        <Accordion multiple collapsible>
          <AccordionItem value="1">
            <AccordionHeader>Test</AccordionHeader>
            <AccordionPanel>
              <p>Test</p>
            </AccordionPanel>
          </AccordionItem>
          <AccordionItem value="2">
            <AccordionHeader>Test2</AccordionHeader>
            <AccordionPanel>
              <p>Test2</p>
            </AccordionPanel>
          </AccordionItem>
        </Accordion>
      </Stack.Item>
    </Stack>
  );
}
