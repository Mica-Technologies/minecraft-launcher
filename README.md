# Mica Minecraft Launcher

Mica Minecraft Launcher is a simple-to-use launcher for Minecraft, written in the Electron framework.
It is designed to be lightweight and easy to use, while still being powerful enough to be customizable and extensible.

## Features

- ** Coming Soon **

A full list of features can be found in the [User Guide](USER_GUIDE.md).

## Installation

Installation of the Mica Minecraft Launcher is simple.

Simply download the latest version of the Mica Minecraft Launcher for your platform
at [https://gitub.com/Mica-Technologies/minecraft-launcher/releases/latest](https://gitub.com/Mica-Technologies/minecraft-launcher/releases/latest),
and run the installer. The same installer can be used to install (new) and/or update the Mica Minecraft Launcher.

A full list of installation instructions can be found in the [User Guide](USER_GUIDE.md).

## Development

Development of the Mica Minecraft Launcher is done using the Electron React Boilerplate. The primary IDE for development
of the Mica Minecraft Launcher is JetBrains WebStorm.
It is recommended to use WebStorm for development, but any IDE that supports Node.js and React development will work,
such as Visual Studio Code.

### Prerequisites

- [Node.js](https://nodejs.org/en/) (v16.0.0 or higher)

### Setup

1. Clone the repository
2. Run `npm install` in the project root directory

Running `npm install` will install all the dependencies for the project. It will also install the dependencies for the
Electron React Boilerplate, which is used as the base for the Mica Minecraft Launcher.

### Running

To run the Mica Minecraft Launcher, run `npm start` in the project root directory. This will start the Mica Minecraft
Launcher in development mode.

For WebStorm users, this can be done by running the `start` configuration.

### Building

To build the Mica Minecraft Launcher, run `npm run package` in the project root directory. This will build the Mica
Minecraft Launcher for your current platform.

For WebStorm users, this can be done by running the `package` configuration.

_**Please note that builds distributed outside the Mica Minecraft Launcher website or official GitHub repository are not
supported and could be dangerous. Please only download builds from the official Mica Minecraft Launcher website or
official GitHub repository.**_

_**The official Mica Minecraft Launcher website
is [https://minecraft.micatechnologies.com](https://minecraft.micatechnologies.com), and the official GitHub repository
is [Mica-Technologies/minecraft-launcher](https://gitub.com/Mica-Technologies/minecraft-launcher).**_

### Testing

To test the Mica Minecraft Launcher, run `npm run test` in the project root directory. This will run the Mica Minecraft
Launcher tests.

For WebStorm users, this can be done by running the `test` configuration.

_Please note that the Mica Minecraft Launcher tests are not complete, and are not guaranteed to be run or provide
accurate results._

### Linting

To lint the Mica Minecraft Launcher, run `npm run lint` in the project root directory. This will run the Mica Minecraft
Launcher linter which will check the code for any issues and perform automatic fixes where possible.

For WebStorm users, this can be done by running the `lint` configuration.

## Contributing

Contributions to the Mica Minecraft Launcher are welcome and encouraged. All contributions must be licensed under the
MIT License or a compatible license. For information about the compatibility of licenses, see
[https://choosealicense.com/appendix/](https://choosealicense.com/appendix/).

### Code Style

The Mica Minecraft Launcher uses the [eslint](https://eslint.org/) linter to enforce code style. The linter is
configured to use the Electron React Boilerplate's recommended code style, as well as the recommended code styles for
ESLint and Typescript.

You can run the linter by running `npm run lint` in the project root directory. The linter will automatically fix
any issues that it can, and will output any remaining issues.

### Commit Messages

Commit messages should follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) specification.

### Code of Conduct

All contributors to the Mica Minecraft Launcher are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Violations of the Code of Conduct will not be tolerated. Our Code of Conduct is based on the Contributor Covenant. More
information can be found at [https://www.contributor-covenant.org](https://www.contributor-covenant.org).

## License

The Mica Minecraft Launcher is licensed under the MIT License. See the LICENSE file for more information.

License information for external contributions can be found in the header of the respective files.

## Credits

The Mica Minecraft Launcher is developed and compiled by the Mica Development Team. It would not be possible without the
contributions of the following:

- [Mica Development Team](https://micatechnologies.com)
- [Electron React Boilerplate](https://github.com/electron-react-boilerplate/electron-react-boilerplate)

### Technologies

- [Electron](https://electronjs.org/)
- [React](https://reactjs.org/)
- [Fluent UI](https://developer.microsoft.com/en-us/fluentui)
- [Microsoft MSAL and Graph](https://developer.microsoft.com/en-us/graph)

## Disclaimer

This project is not affiliated with or endorsed by Mojang AB, Minecraft, or Microsoft.

Minecraft is a trademark of Mojang AB. Microsoft is a trademark of Microsoft Corporation. GitHub is a trademark of
GitHub, Inc.
