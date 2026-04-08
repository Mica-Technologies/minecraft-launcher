# Edit Mod Packs

The modpack management screen lets you add, remove, and organize the modpacks available in your launcher. Access it from the [Main Screen](main-screen.md) by clicking **Edit Mod Packs**.

## Adding Modpacks

There are two ways to add a modpack to your launcher.

### Add by URL

If you have a direct URL to a modpack's `installable.json` manifest (typically provided by a modpack author or server administrator):

1. Paste the URL into the URL field at the top of the screen.
2. Click **Add**.
3. The launcher downloads the manifest, validates its structure, and adds the modpack to your list.

The URL should point to a JSON file in the launcher's modpack manifest format. Example:

```
https://example.com/modpacks/my-modpack/installable.json
```

Modpacks added by URL receive **automatic updates** whenever the hosted manifest changes. Each time you launch the modpack, the launcher checks the URL for a newer version and applies updates if available.

### Add from List

If you do not have a URL, you can browse a curated list of pre-approved modpacks:

1. Click **Add from List**.
2. Browse the available modpacks. Each entry shows the modpack name and a brief description.
3. Select one or more packs you want to install.
4. Confirm your selection.

The selected modpacks are added to your launcher and will appear on the [Main Screen](main-screen.md).

> **Tip:** Both methods support automatic updates. Whether you add a modpack by URL or from the list, the launcher will detect and apply updates when the author publishes a new version.

## Removing Modpacks

To remove a modpack you no longer want:

1. Select the modpack in the list.
2. Click **Remove Selected**.
3. Confirm the removal.

Removing a modpack **deletes its entry from the launcher and removes its downloaded files from disk**, including mods, configs, and other modpack-managed files. Game saves (world data) within the modpack folder will also be removed, so back up any saves you want to keep before removing a modpack.

## Generate Hosting Manifest

The **Generate Hosting Manifest** button creates an `installable.json` file from an existing modpack on disk. This is the standard format the launcher uses to install and update modpacks.

### What It Generates

The manifest includes:

- **Modpack metadata** -- name, version, description, author, Minecraft version, and Forge version.
- **File lists** -- every mod, config, resource pack, and shader pack, each with a download URL and SHA-1 hash for integrity verification.
- **Optional URLs** -- background image and website URLs if defined.

### How to Use It

1. Set up your modpack locally (install the mods, configs, and other files you want to include).
2. Click **Generate Hosting Manifest**.
3. The launcher creates an `installable.json` file that you can save to disk.
4. Upload the manifest file and all referenced mod/config files to a web server, CDN, or file hosting service.
5. Share the URL to your `installable.json` with other users so they can add the modpack using **Add by URL**.

This is the simplest way to distribute a custom modpack to friends or a server community without manually writing JSON.

## Modpack Editor

For more advanced modpack authoring -- creating definitions from scratch, editing individual file entries, searching Modrinth for mods, and validating the manifest -- use the [Modpack Editor](modpack-editor.md).

> **Note:** After adding or removing modpacks, return to the [Main Screen](main-screen.md) to see the updated list.
