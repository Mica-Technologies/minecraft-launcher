import { GetReleasesErrorResponse, GetReleasesResponse, LibericaAPI } from './LibericaApi' // Import the LibericaAPI class

export class JreManager {
  private api: LibericaAPI

  constructor() {
    this.api = new LibericaAPI() // Initialize the LibericaAPI class
  }

  /**
   * Downloads the latest version of the JRE for the specified major version.
   * @param version - The version of the JDK to download.
   * @param bundleType - The bundle type of the JDK to download (JRE or JDK).
   * @returns {Promise<void>}
   */
  async downloadJreByMajorVersion(version: string, bundleType: 'jdk' | 'jre'): Promise<void> {
    // Get the releases for the specified version and JRE bundle type
    const releasesResponse: GetReleasesResponse | GetReleasesErrorResponse =
      await this.api.getReleases({
        version,
        'bundle-type': bundleType
      })

    // Check if the response is an error
    if ('error' in releasesResponse) {
      throw new Error(releasesResponse.error)
    }
  }
}
