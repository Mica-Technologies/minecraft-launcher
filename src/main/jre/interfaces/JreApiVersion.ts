/**
 * Interface for responses from the JRE/JDK release information endpoint of the Bellsoft Liberica API.
 * @see https://bell-sw.com/pages/api/
 * @author Mica Technologies
 */
interface JreApiRelease {
  /**
   * The bitness of the JRE/JDK release (i.e. x64, x86, etc.).
   */
  bitness: number;
  /**
   * The build version of the JRE/JDK release.
   */
  buildVersion: number;
  /**
   * The boolean value indicating whether the JRE/JDK release is the latest LTS (Long-Term Support) release.
   */
  latestLTS: boolean;
  /**
   * The supported operating system of the JRE/JDK release.
   */
  os: string;
  /**
   * The update version of the JRE/JDK release.
   */
  updateVersion: number;
  /**
   * The download URL of the JRE/JDK release.
   */
  downloadUrl: string;
  /**
   * The interim version of the JRE/JDK release.
   */
  interimVersion: number;
  /**
   * The boolean value indicating whether the JRE/JDK release has reached end-of-life (EOL) status.
   */
  EOL: boolean;
  /**
   * The boolean value indicating whether the JRE/JDK release is the latest release in its feature version.
   */
  latestInFeatureVersion: boolean;
  /**
   * The boolean value indicating whether the JRE/JDK release is an LTS (Long-Term Support) release.
   */
  LTS: boolean;
  /**
   * The bundle type of the JRE/JDK release.
   */
  bundleType: string;
  /**
   * The version of the JRE/JDK release.
   */
  version: string;
  /**
   * The feature version of the JRE/JDK release.
   */
  featureVersion: number;
  /**
   * The package type of the JRE/JDK release.
   */
  packageType: string;
  /**
   * The SHA-1 hash of the JRE/JDK release.
   */
  sha1: string;
  /**
   * The boolean value indicating whether the JRE/JDK release is bundled with JavaFX.
   */
  FX: boolean;
  /**
   * The filename of the JRE/JDK release.
   */
  filename: string;
  /**
   * The installation type of the JRE/JDK release.
   */
  installationType: string;
  /**
   * The size of the JRE/JDK release.
   */
  size: number;
  /**
   * The patch version of the JRE/JDK release.
   */
  patchVersion: number;
  /**
   * The boolean value indicating whether the JRE/JDK release has GA (General Availability) status.
   */
  GA: boolean;
  /**
   * The architecture of the JRE/JDK release.
   */
  architecture: string;
  /**
   * The boolean value indicating whether the JRE/JDK release is the latest release.
   */
  latest: boolean;
}

/**
 * The default export for the JreApiRelease interface.
 */
export default JreApiRelease;
