import '../css/MainUi.css';
import '../css/Ui.css';
import React from 'react';
import TitleBar from './components/TitleBar';
import RootStack from './components/RootStack';
import FooterBar from './components/FooterBar';
import CenterContentPane from './components/CenterContentPane';
import PlayerDisplay from './components/PlayerDisplay';
import SettingsButton from './components/SettingsButton';
import SettingsPane from './components/SettingsPane';
import ModPacksSettingsPane from './components/ModPacksSettingsPane';
import ModPacksPane from './components/ModPacksPane';
import { WindowSelection } from './utils/WindowSelection';

export default function MainUi() {
  const [isLoggedIn, setIsLoggedIn] = React.useState(false);
  const [isInternetAvailable, setIsInternetAvailable] = React.useState(
    navigator.onLine
  );
  const [isOfflineMode, setIsOfflineMode] = React.useState(false);
  const [windowSelection, setWindowSelection] = React.useState(
    WindowSelection.modPacks
  );

  // Configure internet availability listeners
  window.addEventListener('online', () =>
    setIsInternetAvailable(navigator.onLine)
  );
  window.addEventListener('offline', () =>
    setIsInternetAvailable(navigator.onLine)
  );

  let centerPaneContent: React.ReactNode;
  switch (windowSelection) {
    case WindowSelection.launcherSettings:
      centerPaneContent = <SettingsPane />;
      break;
    case WindowSelection.modPacksSettings:
      centerPaneContent = <ModPacksSettingsPane />;
      break;
    case WindowSelection.modPacks:
    default:
      centerPaneContent = <ModPacksPane />;
      break;
  }

  return (
    <RootStack>
      <TitleBar />
      <CenterContentPane>{centerPaneContent}</CenterContentPane>
      <FooterBar>
        <PlayerDisplay
          isInternetAvailable={isInternetAvailable}
          isOfflineMode={isOfflineMode}
          isLoggedIn={isLoggedIn}
          setIsOfflineMode={setIsOfflineMode}
        />
        <SettingsButton
          windowSelection={windowSelection}
          setWindowSelection={setWindowSelection}
        />
      </FooterBar>
    </RootStack>
  );
}
