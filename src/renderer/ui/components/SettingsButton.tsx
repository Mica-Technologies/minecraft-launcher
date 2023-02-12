import '../../css/components/SettingsButton.css';
import * as React from 'react';
import {
  ArrowCircleLeft48Regular,
  PuzzlePieceRegular,
  Settings48Regular,
  SettingsRegular,
} from '@fluentui/react-icons';
import {
  Button,
  CompoundButton,
  Popover,
  PopoverProps,
  PopoverSurface,
  PopoverTrigger,
  Title3,
} from '@fluentui/react-components';
import { Stack } from '@fluentui/react';
import { WindowSelection } from '../utils/WindowSelection';

type SettingsButtonProps = {
  windowSelection: WindowSelection;
  // eslint-disable-next-line no-unused-vars
  setWindowSelection: (windowSelection: WindowSelection) => void;
};

export default function SettingsButton({
  windowSelection,
  setWindowSelection,
}: SettingsButtonProps) {
  const [open, setOpen] = React.useState(false);
  const handleOpenChange: PopoverProps['onOpenChange'] = (e, data) =>
    setOpen(data.open || false);

  if (
    windowSelection === WindowSelection.launcherSettings ||
    windowSelection === WindowSelection.modPacksSettings
  ) {
    return (
      <Button
        title="Return"
        aria-label="Return"
        className="settingsPopoverTrigger"
        icon={<ArrowCircleLeft48Regular />}
        appearance="transparent"
        onClick={() => setWindowSelection(WindowSelection.modPacks)}
      />
    );
  }
  return (
    <Popover
      withArrow
      positioning="above-start"
      open={open}
      onOpenChange={handleOpenChange}
    >
      <PopoverTrigger disableButtonEnhancement>
        <Button
          title="Settings"
          aria-label="Settings"
          className="settingsPopoverTrigger"
          icon={<Settings48Regular />}
          appearance="transparent"
        />
      </PopoverTrigger>
      <PopoverSurface>
        <Stack
          className="settingsMenuStack"
          verticalAlign="center"
          horizontalAlign="center"
          tokens={{ childrenGap: 10 }}
        >
          <Title3 className="settingsMenuStackHeader">Settings Menu</Title3>
          <CompoundButton
            secondaryContent="Optional Mods, Resource Packs, and more"
            className="settingsMenuButton"
            icon={<PuzzlePieceRegular />}
            onClick={() => {
              setWindowSelection(WindowSelection.modPacksSettings);
              setOpen(false);
            }}
          >
            Mod Pack Settings
          </CompoundButton>
          <CompoundButton
            secondaryContent="RAM, Debugging, and more"
            className="settingsMenuButton"
            icon={<SettingsRegular />}
            onClick={() => {
              setWindowSelection(WindowSelection.launcherSettings);
              setOpen(false);
            }}
          >
            Launcher Settings
          </CompoundButton>
        </Stack>
      </PopoverSurface>
    </Popover>
  );
}
