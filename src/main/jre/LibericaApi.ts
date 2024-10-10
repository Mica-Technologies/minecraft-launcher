import axios from 'axios'
import { StatusCodes as HttpStatusCodes } from 'http-status-codes'
import { paths } from '@launcherTypes/LibericaApi'

// CONSTANTS

export const BASE_URL = 'https://api.bell-sw.com/v1'
export const HTTP_GET_STRING = 'get'
export const APPLICATION_TYPE_JSON = 'application/json'

// ENDPOINTS

export const ENDPOINTS = {
  releases: '/liberica/releases',
  releasesFile: '/liberica/releases/{filename}',
  versions: '/liberica/versions',
  versionsFeatureVersion: '/liberica/versions/{feature-version}',
  architectures: '/liberica/architectures',
  operatingSystems: '/liberica/operating-systems',
  installationTypes: '/liberica/installation-types',
  packageTypes: '/liberica/package-types',
  bundleTypes: '/liberica/bundle-types',
  vendor: '/vendor',
  products: '/products'
} as const
export type Endpoints = (typeof ENDPOINTS)[keyof typeof ENDPOINTS]

// TYPES

export type GetReleasesParams =
  paths[typeof ENDPOINTS.releases][typeof HTTP_GET_STRING]['parameters']['query']
export type GetReleasesResponse =
  paths[typeof ENDPOINTS.releases][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetReleasesErrorResponse =
  paths[typeof ENDPOINTS.releases][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.BAD_REQUEST]['content'][typeof APPLICATION_TYPE_JSON]
export type GetReleasesFileParams =
  paths[typeof ENDPOINTS.releasesFile][typeof HTTP_GET_STRING]['parameters']['path']
export type GetReleasesFileResponse =
  paths[typeof ENDPOINTS.releasesFile][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetReleasesFileErrorResponse =
  paths[typeof ENDPOINTS.releasesFile][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.NOT_FOUND]['content'][typeof APPLICATION_TYPE_JSON]
export type GetVersionsResponse =
  paths[typeof ENDPOINTS.versions][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetVersionsFeatureVersionParams =
  paths[typeof ENDPOINTS.versionsFeatureVersion][typeof HTTP_GET_STRING]['parameters']['path']
export type GetVersionsFeatureVersionResponse =
  paths[typeof ENDPOINTS.versionsFeatureVersion][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetArchitecturesResponse =
  paths[typeof ENDPOINTS.architectures][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetOperatingSystemsResponse =
  paths[typeof ENDPOINTS.operatingSystems][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetInstallationTypesResponse =
  paths[typeof ENDPOINTS.installationTypes][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetPackageTypesResponse =
  paths[typeof ENDPOINTS.packageTypes][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetBundleTypesResponse =
  paths[typeof ENDPOINTS.bundleTypes][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetVendorResponse =
  paths[typeof ENDPOINTS.vendor][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]
export type GetProductsResponse =
  paths[typeof ENDPOINTS.products][typeof HTTP_GET_STRING]['responses'][typeof HttpStatusCodes.OK]['content'][typeof APPLICATION_TYPE_JSON]

// API CLASS

export class LibericaAPI {
  /**
   * Get a list of releases with optional filters.
   * @param {GetReleasesParams} params - The parameters for the request.
   * @returns {Promise<GetReleasesResponse|GetReleasesErrorResponse>} - A promise that resolves to the response data.
   */
  async getReleases(
    params: GetReleasesParams = {}
  ): Promise<GetReleasesResponse | GetReleasesErrorResponse> {
    try {
      const query = new URLSearchParams(params as any).toString()
      const response = await axios.get<GetReleasesResponse>(
        `${BASE_URL}${ENDPOINTS.releases}?${query}`,
        {
          headers: { Accept: APPLICATION_TYPE_JSON }
        }
      )
      return response.data
    } catch (error) {
      if (axios.isAxiosError(error) && error.response) {
        return error.response.data as GetReleasesErrorResponse
      }
      throw error
    }
  }

  /**
   * Get a release file by filename.
   * @param {GetReleasesFileParams} params - The parameters for the request.
   * @returns {Promise<GetReleasesFileResponse|GetReleasesFileErrorResponse>} - A promise that resolves to the response data.
   */
  async getReleasesFile(
    params: GetReleasesFileParams
  ): Promise<GetReleasesFileResponse | GetReleasesFileErrorResponse> {
    try {
      const { filename } = params
      const response = await axios.get<GetReleasesFileResponse>(
        `${BASE_URL}${ENDPOINTS.releasesFile.replace('{filename}', filename)}`,
        {
          headers: { Accept: APPLICATION_TYPE_JSON }
        }
      )
      return response.data
    } catch (error) {
      if (axios.isAxiosError(error) && error.response) {
        return error.response.data as GetReleasesFileErrorResponse
      }
      throw error
    }
  }

  /**
   * Get a list of available versions.
   * @returns {Promise<GetVersionsResponse>} - A promise that resolves to the response data.
   */
  async getVersions(): Promise<GetVersionsResponse> {
    const response = await axios.get<GetVersionsResponse>(`${BASE_URL}${ENDPOINTS.versions}`, {
      headers: { Accept: APPLICATION_TYPE_JSON }
    })
    return response.data
  }

  /**
   * Get a version by feature version.
   * @param {GetVersionsFeatureVersionParams} params - The parameters for the request.
   * @returns {Promise<GetVersionsFeatureVersionResponse>} - A promise that resolves to the response data.
   */
  async getVersionsFeatureVersion(
    params: GetVersionsFeatureVersionParams
  ): Promise<GetVersionsFeatureVersionResponse> {
    const { 'feature-version': featureVersion } = params
    const response = await axios.get<GetVersionsFeatureVersionResponse>(
      `${BASE_URL}${ENDPOINTS.versionsFeatureVersion.replace('{feature-version}', featureVersion)}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of available architectures.
   * @returns {Promise<GetArchitecturesResponse>} - A promise that resolves to the response data.
   */
  async getArchitectures(): Promise<GetArchitecturesResponse> {
    const response = await axios.get<GetArchitecturesResponse>(
      `${BASE_URL}${ENDPOINTS.architectures}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of available operating systems.
   * @returns {Promise<GetOperatingSystemsResponse>} - A promise that resolves to the response data.
   */
  async getOperatingSystems(): Promise<GetOperatingSystemsResponse> {
    const response = await axios.get<GetOperatingSystemsResponse>(
      `${BASE_URL}${ENDPOINTS.operatingSystems}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of available installation types.
   * @returns {Promise<GetInstallationTypesResponse>} - A promise that resolves to the response data.
   */
  async getInstallationTypes(): Promise<GetInstallationTypesResponse> {
    const response = await axios.get<GetInstallationTypesResponse>(
      `${BASE_URL}${ENDPOINTS.installationTypes}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of available package types.
   * @returns {Promise<GetPackageTypesResponse>} - A promise that resolves to the response data.
   */
  async getPackageTypes(): Promise<GetPackageTypesResponse> {
    const response = await axios.get<GetPackageTypesResponse>(
      `${BASE_URL}${ENDPOINTS.packageTypes}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of available bundle types.
   * @returns {Promise<GetBundleTypesResponse>} - A promise that resolves to the response data.
   */
  async getBundleTypes(): Promise<GetBundleTypesResponse> {
    const response = await axios.get<GetBundleTypesResponse>(
      `${BASE_URL}${ENDPOINTS.bundleTypes}`,
      {
        headers: { Accept: APPLICATION_TYPE_JSON }
      }
    )
    return response.data
  }

  /**
   * Get a list of vendors.
   * @returns {Promise<GetVendorResponse>} - A promise that resolves to the response data.
   */
  async getVendor(): Promise<GetVendorResponse> {
    const response = await axios.get<GetVendorResponse>(`${BASE_URL}${ENDPOINTS.vendor}`, {
      headers: { Accept: APPLICATION_TYPE_JSON }
    })
    return response.data
  }

  /**
   * Get a list of products.
   * @returns {Promise<GetProductsResponse>} - A promise that resolves to the response data.
   */
  async getProducts(): Promise<GetProductsResponse> {
    const response = await axios.get<GetProductsResponse>(`${BASE_URL}${ENDPOINTS.products}`, {
      headers: { Accept: APPLICATION_TYPE_JSON }
    })
    return response.data
  }
}
