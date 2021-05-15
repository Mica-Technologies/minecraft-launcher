# Mica Minecraft Launcher
A simple-to-use launcher for Minecraft Forge, built on the Java and JavaFX platforms. Compatible with Windows, macOS and Linux.

## General Information

The Mica Minecraft Launcher is a cross-platform, easy-to-use modpack launcher that uses modpacks defined in JSON files that are hosted on the internet.

All versions (macOS, Windows, Linux) support client mode and server mode. Client mode is the default mode, unless a graphic interface is not supported.
- Client mode is the mode for those looking to play a Minecraft game (Minecraft Client). 
- Server mode is the mode for those looking to host a Minecraft server (Minecraft Server).

### Opening the Launcher (Switches)

If you're using the native Windows or macOS application (exe, dmg, app), only client mode is supported, and these switches will not work.

__To force server mode__: `launcher-file-name.jar -s`

__To force client mode__: `launcher-file-name.jar -c`

__To specify mod pack__: `launcher-file-name.jar [modpack name]` where [modpack name] is the name of the installed modpack.  For example, `launcher-file-name.jar Alto` would run the launcher with the Alto mod pack selected. 

__To force server mode with specified mod pack__: `launcher-file-name.jar -s [modpack name]`

__To force client mode with specified mod pack__: `launcher-file-name.jar -c [modpack name]`

## Adding a Mod pack to the Launcher

Adding a modpack to the Mica Minecraft Launcher is simple and easy! All you have to do is:

1. Open the launcher settings window
    1. If not already done, login to the launcher with your Minecraft/Mojang account
    2. On the modpack selection screen, click the "_Edit Mod Packs_" (Pencil Icon) button
    
2. Enter the URL of a mod pack, or choose one from the list of available mod packs

3. Click the "_Save_" button, and then "_Return_"

## Creating a Mod pack for the Launcher

Just like adding a modpack, creating one yourself is just as easy! All you have to do is:

1. Create a new JSON modpack definition, or download the example one [HERE](https://github.com/Mica-Technologies/minecraft-launcher/blob/main/full_modpack_json_example.json)

2. Edit the required fields in the modpack definition to fit your modpack

3. Add mods you'd like in your modpack to the packMods field

4. Add optional fields such as packConfigs, packResourcePacks, packShaderPacks and packInitialFiles

5. Upload the completed JSON modpack definition to an internet location, and get the download URL

6. Share the download URL with anyone who would like to use the modpack

__Basic Example__:

```json
{
    "packName": "Example Modpack",
    "packVersion": "1.3.5",
    "packURL": "www.example.com",
    "packLogoURL": "https://image.shutterstock.com/image-vector/example-signlabel-features-speech-bubble-260nw-1223219848.jpg",
    "packMinRAMGB": "4",
    "packForgeURL": "https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.12.2-14.23.5.2847/forge-1.12.2-14.23.5.2847-universal.jar",
    "packForgeHash": "c15dbf708064a9db9a9d66dd84688b9f31b6006e",
    "packMods": [{
            "name": "Journey Map",
            "remote": "https://media.forgecdn.net/files/2755/458/journeymap-1.12.2-5.5.5.jar",
            "local": "journey-map.jar",
            "sha1": "29875c4d6c543562066f44b727eb020d4fd062c3",
            "clientReq": true,
            "serverReq": true
        },
        {
            "name": "Foam Fix",
            "remote": "https://media.forgecdn.net/files/2809/906/foamfix-0.10.8-1.12.2.jar",
            "local": "foam-fix.jar",
            "sha1": "1ee4134a48f84d8a0122bc4efc18a49620b588ab",
            "clientReq": true,
            "serverReq": true
        }
    ]
}

```

__Full Example__:

```json
{
    "packName": "Example Modpack",
    "packVersion": "1.3.5",
    "packURL": "www.example.com",
    "packLogoURL": "https://image.shutterstock.com/image-vector/example-signlabel-features-speech-bubble-260nw-1223219848.jpg",
    "packBackgroundURL": "https://wallpaperstock.net/minecraft-wallpapers_27410_1600x1200.jpg",
    "packMinRAMGB": "4",
    "packForgeURL": "https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.12.2-14.23.5.2847/forge-1.12.2-14.23.5.2847-universal.jar",
    "packForgeHash": "c15dbf708064a9db9a9d66dd84688b9f31b6006e",
    "packMods": [{
            "name": "Journey Map",
            "remote": "https://media.forgecdn.net/files/2755/458/journeymap-1.12.2-5.5.5.jar",
            "local": "journey-map.jar",
            "sha1": "29875c4d6c543562066f44b727eb020d4fd062c3",
            "clientReq": true,
            "serverReq": true
        },
        {
            "name": "Foam Fix",
            "remote": "https://media.forgecdn.net/files/2809/906/foamfix-0.10.8-1.12.2.jar",
            "local": "foam-fix.jar",
            "sha1": "1ee4134a48f84d8a0122bc4efc18a49620b588ab",
            "clientReq": true,
            "serverReq": true
        }
    ],
    "packConfigs": [{
        "remote": "https://file-examples.com/wp-content/uploads/2017/02/file_example_CSV_5000.csv",
        "local": "example.csv",
        "sha1": "314ccdfe54b570ab4f3cf09bedb5ae5f0ee01cd8",
        "clientReq": true,
        "serverReq": true
    }],
    "packResourcePacks": [{
        "remote": "https://file-examples.com/wp-content/uploads/2017/02/zip_10MB.zip",
        "local": "example-respack.zip",
        "sha1": "0a81e8ce46a60629114ae9a55d6427b2150d14bf"
    }],
    "packShaderPacks": [{
        "remote": "https://file-examples.com/wp-content/uploads/2017/02/zip_10MB.zip",
        "local": "example-shaders.zip",
        "sha1": "0a81e8ce46a60629114ae9a55d6427b2150d14bf"
    }],
    "packInitialFiles": [{
        "remote": "",
        "local": "",
        "sha1": "",
        "clientReq": true,
        "serverReq": false
    }]
}
```

### Modpack Definition Fields

| NAME              | EXAMPLE                                                                                                                                                                                              | EXPLANATION                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | REQUIRED |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| packName          | "Example Modpack"                                                                                                                                                                                    | Name of mod pack                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | YES      |
| packVersion       | "1.0.0"                                                                                                                                                                                              | Version of mod pack                                                                                                                                                                                                                                                                                                                                                                                                                                                               | YES      |
| packURL           | "www.example.com"                                                                                                                                                                                    | URL of mod pack (can be blank)                                                                                                                                                                                                                                                                                                                                                                                                                                                    | YES      |
| packLogoURL       | "www.example.com/image.png"                                                                                                                                                                          | URL of mod pack logo (.png only, include https if required)                                                                                                                                                                                                                                                                                                                                                                                                                       | YES      |
| packBackgroundURL | "www.example.com/otherimage.png"                                                                                                                                                                     | URL of mod pack background image (.png only, include https if required)                                                                                                                                                                                                                                                                                                                                                                                                           | NO       |
| packMinRAMGB      | "4"                                                                                                                                                                                                  | Minimum RAM (in GB)                                                                                                                                                                                                                                                                                                                                                                                                                                                               | YES      |
| packForgeURL      | "www.forge.com/download.jar"                                                                                                                                                                         | URL of Forge Jar (installer)                                                                                                                                                                                                                                                                                                                                                                                                                                                      | YES      |
| packForgeHash     | "c91838b9f7ea97d9ace713"                                                                                                                                                                             | SHA-1 SUM of Forge Jar at packForgeURL                                                                                                                                                                                                                                                                                                                                                                                                                                            | YES      |
| packMods          | [{<br>    "name": "Mod Name",<br>    "remote": "www.mod.com/url",<br>    "local": "local-filename.jar",<br>    "sha1": "71c9a720418eb9a9c",<br>    "clientReq": true,<br>    "serverReq": true<br>}] | List of mods in mod pack (no limit)<br><br>name: Mod name<br>remote: Mod download URL<br>local: Mod local filename<br>sha1: Mod file sha-1 sum<br>clientReq: required for client<br>serverReq: required for server                                                                                                                                                                                                                                                                | YES      |
| packConfigs       | [{<br>    "remote": "www.config.com/url",<br>    "local": "local-filename.cfg",<br>    "sha1": "9c7274d7a7bc752013f",<br>    "clientReq": true,<br>    "serverReq": false<br>}]                      | List of controlled mod configs (no limit)<br><br>remote: Config download URL<br>local: Config local filename<br>sha1: Config file sha-1 sum<br>clientReq: required for client<br>serverReq: required for server                                                                                                                                                                                                                                                                   | NO       |
| packResourcePacks | [{<br>    "remote": "www.res-pack.com/url",<br>    "local": "resource-pack.zip",<br>    "sha1": "c381fea7137c6296a3"<br>}]                                                                           | List of resource packs (no limit)<br><br>remote: Resource pack (.zip) download URL<br>local: Resource pack local filename<br>sha1: Resource pack file sha-1 sum                                                                                                                                                                                                                                                                                                                   | NO       |
| packShaderPacks   | [{<br>    "remote": "www.shader-pack.com/url",<br>    "local": "shader-pack.zip",<br>    "sha1": "9a71cb24ef4c9ba2e1"<br>}]                                                                          | List of shader packs (no limit)<br><br>remote: Shader pack (.zip) download URL<br>local: Shader pack local filename<br>sha1: Shader pack file sha-1 sum                                                                                                                                                                                                                                                                                                                           | NO       |
| packInitialFiles  | [{<br>    "remote": "www.example.com/file.txt",<br>    "local": "initial-file.txt",<br>    "sha1": "-1",<br>    "clientReq": true,<br>    "serverReq": false<br>}]                                   | List of initial files (no limit)<br><br>Initial files are files that do not fit <br>the existing categories. Initial files<br>are handled like other mod pack files, <br>and will be re-downloaded each time the <br>local file fails sha-1 verification, if<br>sha-1 verification is enabled.<br><br>remote: Initial file download URL<br>local: Initial file local filename<br>sha1: Initial file sha-1 sum<br>clientReq: required for client<br>serverReq: required for server | NO       |

## Development

### Contributing
There are many ways to contribute to this project. If you have an issue or feature suggestion, please feel free to bring it to the development team's attention by adding a GitHub Issue to the project on the Issues tab of this repo. To contribute to the source code or functionality of the application, please download [IntelliJ IDEA](https://www.jetbrains.com/idea/download/#section=windows) and checkout this repository as a Maven project.
   
### Branches
The `main` branch is a protected branch, and pushing to it is not permitted. Development must be completed on a secondary branch, and then merged to the main project using a pull request. At least one person must review all pull requests before they may be merged, and all pull requests are automatically build tested. If the pull request is not reviewed, or the code fails to build, the pull request cannot be merged.
   

