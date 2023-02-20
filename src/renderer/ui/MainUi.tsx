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
import { Account } from '../../main/api/auth/authLib';
import ipcMessages from '../../main/ipc/ipcMessageNames';

export default function MainUi() {
  const [storedAccounts, setStoredAccounts] = React.useState<Account[]>([]);
  const [selectedAccount, setSelectedAccount] = React.useState<Account | null>(
    null
  );
  const [isInternetAvailable, setIsInternetAvailable] = React.useState(
    navigator.onLine
  );
  const [windowSelection, setWindowSelection] = React.useState(
    WindowSelection.modPacks
  );

  // Configure listeners
  React.useEffect(() => {
    // Configure internet availability listeners
    window.addEventListener('online', () =>
      setIsInternetAvailable(navigator.onLine)
    );
    window.addEventListener('offline', () =>
      setIsInternetAvailable(navigator.onLine)
    );

    // Configure account login listeners
    window.electron.ipcRenderer.onStoredAccountsChange(
      (newStoredAccounts: Account[]) => {
        setStoredAccounts(newStoredAccounts);
        if (newStoredAccounts.length > 0 && selectedAccount === null) {
          setSelectedAccount(newStoredAccounts[0]);
        } else if (newStoredAccounts.length === 0) {
          setSelectedAccount(null);
        } else if (
          selectedAccount !== null &&
          !newStoredAccounts.some(
            (account) => account.uuid === selectedAccount.uuid
          )
        ) {
          setSelectedAccount(newStoredAccounts[0]);
        }
      }
    );
  });

  // Configure account storage loading
  React.useEffect(() => {
    window.electron.ipcRenderer.sendMessage(
      ipcMessages.GET_STORED_ACCOUNTS,
      []
    );
  }, []);

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
          storedAccounts={storedAccounts}
          selectedAccount={selectedAccount}
          setSelectedAccount={setSelectedAccount}
        />
        <SettingsButton
          windowSelection={windowSelection}
          setWindowSelection={setWindowSelection}
        />
      </FooterBar>
    </RootStack>
  );
}
