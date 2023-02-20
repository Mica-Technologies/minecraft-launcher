import '../../css/components/ModPacksSettingsPane.css';
import React from 'react';
import { Stack } from '@fluentui/react';
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Divider,
  Title3,
} from '@fluentui/react-components';

export default function ModPacksSettingsPane() {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      horizontalAlign="center"
      className="modPacksSettingsPane"
    >
      <Stack.Item className="modPackSettingsPaneArea">
        <Title3>Mod Pack Settings</Title3>
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
