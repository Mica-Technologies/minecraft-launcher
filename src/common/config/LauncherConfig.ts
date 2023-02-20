export default interface LauncherConfig {
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
}

export function fromJson(json: string): LauncherConfig {
  return JSON.parse(json);
}

export function toJson(config: LauncherConfig): string {
  return JSON.stringify(config);
}

export function getDefaultConfig(): LauncherConfig {
  return {
    gameConfiguration: {
      minRAM: 1024,
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
  };
}
