import { Stack } from '@fluentui/react';
import '../../css/components/PlayerDisplay.css';
import * as React from 'react';
import {
  Button,
  CompoundButton,
  Persona,
  Popover,
  PopoverSurface,
  PopoverTrigger,
  PresenceBadgeStatus,
  Title3,
} from '@fluentui/react-components';
import {
  PersonKeyRegular,
  PlugConnectedCheckmarkRegular,
  PlugDisconnectedRegular,
  SignOutRegular,
} from '@fluentui/react-icons';

type PlayerDisplayProps = {
  isInternetAvailable: boolean;
  isLoggedIn: boolean;
  isOfflineMode: boolean;
  // eslint-disable-next-line no-unused-vars
  setIsOfflineMode: (isOfflineMode: boolean) => void;
};

export default function PlayerDisplay({
  isInternetAvailable,
  isLoggedIn,
  isOfflineMode,
  setIsOfflineMode,
}: PlayerDisplayProps) {
  // Generate persona information
  let personaStatus: PresenceBadgeStatus;
  let personaText: string;
  if (!isInternetAvailable) {
    personaStatus = 'offline';
    personaText = 'Disconnected';
  } else {
    personaStatus = isOfflineMode ? 'blocked' : 'available';
    personaText = isOfflineMode ? 'Offline Mode' : 'Connected';
  }

  // Generate sign in/out button secondary text
  let signInOutSecondaryText: string;
  let signInOutEnabled: boolean;
  if (!isLoggedIn && !isInternetAvailable) {
    signInOutSecondaryText = 'Unavailable When Disconnected';
    signInOutEnabled = false;
  } else if (!isLoggedIn && isOfflineMode) {
    signInOutSecondaryText = 'Unavailable When Offline';
    signInOutEnabled = false;
  } else {
    signInOutSecondaryText = isLoggedIn
      ? 'Remove Sign-In Information'
      : 'Using Your Microsoft Account';
    signInOutEnabled = true;
  }

  // Generate online/offline mode button secondary text
  let onlineOfflineModeSecondaryText: string;
  let onlineOfflineModeEnabled: boolean;
  if (!isInternetAvailable) {
    onlineOfflineModeSecondaryText = 'Unavailable When Disconnected';
    onlineOfflineModeEnabled = false;
  } else {
    onlineOfflineModeSecondaryText =
      isOfflineMode || !isInternetAvailable
        ? 'Play With Internet Connection'
        : 'Play Without Internet Connection';
    onlineOfflineModeEnabled = true;
  }

  return (
    <Popover withArrow positioning="above-end">
      <PopoverTrigger disableButtonEnhancement>
        <Button appearance="transparent">
          <Persona
            name="Logged Out"
            avatar={isLoggedIn ? 'https://i.imgur.com/4ZQ9Z0E.png' : ''}
            secondaryText={personaText}
            size="medium"
            presence={{ status: personaStatus }}
            className="playerDisplay"
            color={isOfflineMode ? 'black' : 'white'}
          />
        </Button>
      </PopoverTrigger>
      <PopoverSurface>
        <Stack
          className="playerMenuStack"
          verticalAlign="center"
          horizontalAlign="center"
          tokens={{ childrenGap: 10 }}
        >
          <Title3 className="playerMenuStackHeader">Player Menu</Title3>
          <CompoundButton
            secondaryContent={signInOutSecondaryText}
            className="playerMenuButton"
            icon={isLoggedIn ? <SignOutRegular /> : <PersonKeyRegular />}
            disabled={!signInOutEnabled}
          >
            {isLoggedIn ? 'Sign Out' : 'Sign In'}
          </CompoundButton>
          <CompoundButton
            secondaryContent={onlineOfflineModeSecondaryText}
            className="playerMenuButton"
            icon={
              isOfflineMode || !isInternetAvailable ? (
                <PlugDisconnectedRegular />
              ) : (
                <PlugConnectedCheckmarkRegular />
              )
            }
            onClick={() => setIsOfflineMode(!isOfflineMode)}
            disabled={!onlineOfflineModeEnabled}
          >
            {isOfflineMode || !isInternetAvailable
              ? 'Disable Offline Mode'
              : 'Enable Offline Mode'}
          </CompoundButton>
        </Stack>
      </PopoverSurface>
    </Popover>
  );
}
