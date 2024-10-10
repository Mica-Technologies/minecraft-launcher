/**
 * @file LauncherConfig.ts
 * @description The JSON interface for a launcher configuration file.
 * @author Mica Technologies
 */

/**
 * The JSON interface for a launcher configuration file.
 */
export interface LauncherConfig {
  gameConfiguration: {
    minRAM: number;
    maxRAM: number;
    customJvmArguments: string[];
  };
  modPackSelectionConfiguration: {
    sortBy: 'name' | 'lastPlayed';
    sortDescending: boolean;
    lastSelectedModPack: string;
    favoriteModPacks: string[];
    autoStartModPack?: string | null;
  };
  socialConfiguration: {
    discordEnabled: boolean;
    hideGameDetails: boolean;
  };
  styleConfiguration: {
    theme: 'auto' | 'light' | 'dark';
    useNativeTitleBar: boolean;
    useWindowsTitleBarOverlay: boolean;
  };
  advancedConfiguration: {
    resetLauncherOnNextStart: boolean;
  };
}

/**
 * Function to get the default launcher configuration.
 * @returns The default launcher configuration.
 */
export function getDefaultConfig(): LauncherConfig {
  return {
    gameConfiguration: {
      minRAM: 2048,
      maxRAM: 4096,
      customJvmArguments: [],
    },
    modPackSelectionConfiguration: {
      sortBy: 'name',
      sortDescending: false,
      lastSelectedModPack: '',
      favoriteModPacks: [],
    },
    socialConfiguration: {
      discordEnabled: true,
      hideGameDetails: false,
    },
    styleConfiguration: {
      theme: 'auto',
      useNativeTitleBar: false,
      useWindowsTitleBarOverlay: false,
    },
    advancedConfiguration: {
      resetLauncherOnNextStart: false,
    },
  };
}
