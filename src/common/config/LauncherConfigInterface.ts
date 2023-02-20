export default interface LauncherConfigInterface {
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

export function fromJson(json: string): LauncherConfigInterface {
  return JSON.parse(json);
}

export function toJson(config: LauncherConfigInterface): string {
  return JSON.stringify(config);
}

export function getDefaultConfig(): LauncherConfigInterface {
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
