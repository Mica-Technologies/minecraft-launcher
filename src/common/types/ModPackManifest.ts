/**
 * @file ModPackInterface.ts
 * @description The JSON interface for a mod pack definition file.
 * @author Mica Technologies
 */

/**
 * The JSON interface for a mod in a mod pack definition file.
 */
export interface PackMod {
  name: string;
  remote: string;
  local: string;
  sha1: string;
  clientReq: boolean;
  serverReq: boolean;
}

/**
 * The JSON interface for a config in a mod pack definition file.
 */
export interface PackConfig {
  remote: string;
  local: string;
  sha1: string;
  clientReq: boolean;
  serverReq: boolean;
}

/**
 * The JSON interface for a resource pack in a mod pack definition file.
 */
export interface PackResourcePack {
  remote: string;
  local: string;
  sha1: string;
}

/**
 * The JSON interface for a shader pack in a mod pack definition file.
 */
export interface PackShaderPack {
  remote: string;
  local: string;
  sha1: string;
}

/**
 * The JSON interface for an initial file in a mod pack definition file.
 */
export interface PackInitialFile {
  remote: string;
  local: string;
  sha1?: string;
  md5?: string;
  clientReq: boolean;
  serverReq: boolean;
}

/**
 * The JSON interface for a mod pack definition file.
 */
export default interface ModPackInterface {
  packName: string;
  packVersion: string;
  packURL: string;
  packLogoURL: string;
  packBackgroundURL: string;
  packMinRAMGB: string;
  packForgeURL: string;
  packForgeHash: string;
  packMods: PackMod[];
  packConfigs: PackConfig[];
  packResourcePacks: PackResourcePack[];
  packShaderPacks: PackShaderPack[];
  packInitialFiles: PackInitialFile[];
}

/**
 * Utility function to convert a JSON string to a ModPackInterface.
 * @param json The JSON string to convert.
 */
export function fromJson(json: string): ModPackInterface {
  return JSON.parse(json);
}

/**
 * Utility function to convert a ModPackInterface to a JSON string.
 * @param modPackInterface The ModPackInterface to convert.
 */
export function toJson(modPackInterface: ModPackInterface): string {
  return JSON.stringify(modPackInterface);
}

/**
 * Utility function to check if a JSON string is a valid mod pack definition file.
 * @param json The JSON string to check.
 */
export function isModPack(json: string) {
  const jsonInterface = JSON.parse(json);
  return (
    jsonInterface.packName !== undefined &&
    jsonInterface.packVersion !== undefined &&
    jsonInterface.packURL !== undefined &&
    jsonInterface.packLogoURL !== undefined &&
    jsonInterface.packBackgroundURL !== undefined &&
    jsonInterface.packMinRAMGB !== undefined &&
    jsonInterface.packForgeURL !== undefined &&
    jsonInterface.packForgeHash !== undefined &&
    jsonInterface.packMods !== undefined &&
    jsonInterface.packConfigs !== undefined &&
    jsonInterface.packResourcePacks !== undefined &&
    jsonInterface.packShaderPacks !== undefined &&
    jsonInterface.packInitialFiles !== undefined
  );
}
