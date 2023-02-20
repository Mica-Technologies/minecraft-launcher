import { Stack } from '@fluentui/react';
import '../../css/components/PlayerDisplay.css';
import * as React from 'react';
import {
  Button,
  CompoundButton,
  Dropdown,
  Label,
  OptionGroup,
  Option,
  Persona,
  Popover,
  PopoverSurface,
  PopoverTrigger,
  PresenceBadgeStatus,
  Title3,
  useId,
  Tooltip,
  Divider,
  Text,
} from '@fluentui/react-components';
import {
  PersonKeyRegular,
  PlugConnectedCheckmarkRegular,
  PlugDisconnectedRegular,
  SignOutRegular,
} from '@fluentui/react-icons';
import ipcMessages from '../../../main/ipc/ipcMessageNames';
import { Account, MojangAPI } from '../../../main/api/auth/authLib';

type PlayerDisplayProps = {
  isInternetAvailable: boolean;
  storedAccounts: Account[];
  selectedAccount: Account | null;
  // eslint-disable-next-line no-unused-vars
  setSelectedAccount: (setSelectedAccount: Account | null) => void;
};

export default function PlayerDisplay({
  isInternetAvailable,
  storedAccounts,
  selectedAccount,
  setSelectedAccount,
}: PlayerDisplayProps) {
  const dropdownId = useId('dropdown-grouped');

  // Generate persona information
  let personaStatus: PresenceBadgeStatus;
  let personaText: string;
  if (!isInternetAvailable) {
    personaStatus = 'offline';
    personaText = 'Disconnected';
  } else {
    personaStatus = selectedAccount === null ? 'blocked' : 'available';
    personaText = selectedAccount === null ? 'Disconnected' : 'Connected';
  }

  // Generate sign in/out button secondary text
  let signInOutSecondaryText: string;
  let signInOutEnabled: boolean;
  if (selectedAccount === null && !isInternetAvailable) {
    signInOutSecondaryText = 'Unavailable When Disconnected';
    signInOutEnabled = false;
  } else {
    signInOutSecondaryText =
      selectedAccount === null
        ? 'Using Your Microsoft Account'
        : 'Remove Sign-In Information';
    signInOutEnabled = true;
  }

  return (
    <Popover withArrow positioning="above-end">
      <PopoverTrigger disableButtonEnhancement>
        <Button appearance="transparent">
          <Persona
            name={
              selectedAccount !== null ? selectedAccount.username : 'Logged Out'
            }
            avatar={{
              image: {
                src:
                  selectedAccount !== null
                    ? `https://minotar.net/armor/bust/${selectedAccount.username}/100.png`
                    : 'https://minotar.net/armor/bust/MHF_Steve/100.png',
              },
            }}
            secondaryText={personaText}
            size="medium"
            presence={{ status: personaStatus }}
            className="playerDisplay"
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
          <Stack.Item hidden={!selectedAccount}>
            <Divider>Account Information</Divider>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={200}>Profile Name</Text>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={100}>{selectedAccount?.profile.name}</Text>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={200}>Profile ID</Text>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={100}>{selectedAccount?.profile.id}</Text>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={200}>Game Ownership</Text>
          </Stack.Item>
          <Stack.Item hidden={!selectedAccount}>
            <Text size={100}>
              {selectedAccount?.ownership === undefined
                ? 'Unknown'
                : selectedAccount?.ownership.toString()}
            </Text>
          </Stack.Item>
          <Stack.Item>
            {selectedAccount && <br />}
            <Divider id={dropdownId}>Account Selection</Divider>
          </Stack.Item>
          <Stack.Item>
            <Dropdown
              aria-labelledby={dropdownId}
              placeholder={
                storedAccounts.length === 0
                  ? 'Add an account'
                  : 'Select an account'
              }
              defaultValue={selectedAccount ? selectedAccount.uuid : ''}
              defaultSelectedOptions={
                selectedAccount ? [selectedAccount.uuid] : []
              }
              value={selectedAccount ? selectedAccount.username : ''}
              selectedOptions={selectedAccount ? [selectedAccount.uuid] : []}
              onOptionSelect={(ev, data) => {
                if (data.optionValue === 'microsoft-add') {
                  window.electron.ipcRenderer.sendMessage(
                    ipcMessages.LOGIN_MS,
                    []
                  );
                } else {
                  const newSelectedAccount = storedAccounts.find(
                    (account) => account.uuid === data.optionValue
                  );
                  if (newSelectedAccount) {
                    setSelectedAccount(newSelectedAccount);
                  }
                }
              }}
            >
              <OptionGroup label="Microsoft Accounts">
                {storedAccounts.map((account) => (
                  <Option
                    key={account.uuid}
                    text={account.username}
                    value={account.uuid}
                  >
                    <Stack
                      horizontal
                      horizontalAlign="space-between"
                      verticalAlign="center"
                      style={{ width: '100%' }}
                    >
                      <Stack.Item>
                        <Persona
                          name={account.username}
                          avatar={{
                            image: {
                              src: `https://minotar.net/armor/bust/${account.username}/100.png`,
                            },
                          }}
                          secondaryText={
                            account.uuid === selectedAccount?.uuid
                              ? 'Active'
                              : 'Inactive'
                          }
                          size="medium"
                          presence={{
                            status:
                              account.uuid === selectedAccount?.uuid
                                ? 'available'
                                : 'away',
                          }}
                          className="playerDisplay"
                        />
                      </Stack.Item>
                      <Stack.Item>
                        <Tooltip
                          content={`Sign Out of ${account.username}`}
                          relationship="label"
                        >
                          <Button
                            size="small"
                            icon={<SignOutRegular />}
                            onClick={() => {
                              window.electron.ipcRenderer.sendMessage(
                                ipcMessages.LOGOUT,
                                [account.uuid]
                              );
                            }}
                          />
                        </Tooltip>
                      </Stack.Item>
                    </Stack>
                  </Option>
                ))}
                <Option
                  key="microsoft-add"
                  value="microsoft-add"
                  text="Add Account"
                >
                  + Add Microsoft Account
                </Option>
              </OptionGroup>
              <OptionGroup label="Mojang Accounts">
                <Option key="mojang-disabled" value="mojang-disabled" disabled>
                  Not Supported Yet
                </Option>
              </OptionGroup>
            </Dropdown>
          </Stack.Item>
        </Stack>
      </PopoverSurface>
    </Popover>
  );
}
