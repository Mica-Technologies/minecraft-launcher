#!/bin/bash

# Print Welcome/Startup Msg
printf "Mica Technologies macOS App Build Script v0.1\n"

# Verify Libraries
printf "\nThis script requires the following libraries or applications: graphicsmagick, imagemagick and node (npm).\n"
#select yn in "Continue" "Install"; do
#	case $yn in
#		Install ) brew install graphicsmagick imagemagick node; break;;
#		Continue ) break;;
#	esac
#done

printf "\nThe application must be in the current folder. Looking For: micaforgelauncher-unsigned.app\n"

# Rename launcher to signed name
printf "\nPreparing signed .app file for processing\n"
rm -rf "micaforgelauncher-signed.app"
sleep 1
cp -r "micaforgelauncher-unsigned.app" "micaforgelauncher-signed.app"
sleep 2
find "micaforgelauncher-signed.app" -name ‘*.DS_Store’ -type f -delete
printf "Preparing signed .app file for processing: DONE\n"

# Rename Java legal folder to bypass codesign error with incompatible names
printf "\nPatching Java legal file incompatible names\n"
#find -E "micaforgelauncher-signed.app/Contents/PlugIns/JRE/Contents/Home/jre/legal/" -type d -regex ".+\..+" -exec bash -c "echo {} | sed 's/\.\///g' | sed 's/\./_/g' | sed 's/ /\ /g' | sed 's/micaforgelauncher-signed_app/micaforgelauncher-signed.app/g'  | xargs mv {} " \;
#find -E "micaforgelauncher-signed.app/Contents/PlugIns/JRE/Contents/Home/jre/man/" -type d -regex ".+\..+" -exec bash -c "echo {} | sed 's/\.\///g' | sed 's/\./_/g' | sed 's/ /\ /g' | sed 's/micaforgelauncher-signed_app/micaforgelauncher-signed.app/g'  | xargs mv {} " \;
printf "Patching Java legal file incompatible names: DONE\n"

# Code sign the application (.app)
printf "\nPerforming code signing of application (.app)\n"
#find "micaforgelauncher-signed.app/Contents/PlugIns" -type f \( -name "*.jar" -or -name "*.dylib" \) -exec codesign --timestamp --force --deep --entitlements ../src/main/resources/darwin/entitlements.plist --sign 'Developer ID Application' {} \;
#codesign -vvv --deep --force --timestamp --strict --entitlements ../src/main/resources/darwin/entitlements.plist --options runtime --sign "Developer ID Application" "micaforgelauncher-signed.app"

find "micaforgelauncher-signed.app" -type f -not -path "*/Contents/PlugIns/*" -not -path "*/Contents/MacOS/JavaAppLauncher" -not -path "*libapplauncher.dylib" -exec codesign --timestamp --entitlements /Users/alexanderhawk/Git/Personal/Minecraft-Forge-Launcher/src/main/resources/darwin/entitlements.plist -s "Developer ID Application" --options runtime -v {} \;
find "micaforgelauncher-signed.app/Contents/PlugIns/JRE" -type f -not -path "*/legal/*" -not -path "*/man/*" -exec codesign -f --timestamp --entitlements /Users/alexanderhawk/Git/Personal/Minecraft-Forge-Launcher/src/main/resources/darwin/entitlements.plist -s "Developer ID Application" --options runtime -v {} \;
#codesign -f --timestamp --entitlements /Users/alexanderhawk/Git/Personal/Minecraft-Forge-Launcher/src/main/resources/darwin/entitlements.plist -s "Developer ID Application" --options runtime -v micaforgelauncher-signed.app/Contents/PlugIns/JRE/Contents/Home/jre
codesign -f --deep --timestamp --entitlements /Users/alexanderhawk/Git/Personal/Minecraft-Forge-Launcher/src/main/resources/darwin/micaforgelauncher.entitlements -s "Developer ID Application" --options runtime -v "micaforgelauncher-signed.app"

printf "Performing code signing of application (.app): DONE\n"
printf "NOTE: APPLICATION .APP FILE MUST BE NOTARIZED BY APPLE\n"

# Install and/or verify Node.js create-dmg application
printf "\nUpdating/Installing Node.js create-dmg project from sindresorhus"
npm install --global create-dmg
printf "Updating/Installing Node.js create-dmg project from sindresorhus: DONE\n"

# Rename launcher to final distribution name
printf "\nPreparing signed .app file for distribution\n"
rm -rf "Mica Forge Launcher.app"
cp -r "micaforgelauncher-signed.app" "Mica Forge Launcher.app"
rm -rf "micaforgelauncher-signed.app"
rm -rf "micaforgelauncher-unsigned.app"
printf "Preparing signed .app file for distribution: DONE\n"

# Build DMG
printf "\nBuilding DMG using create-dmg project from sindresorhus"
create-dmg "Mica Forge Launcher.app" --overwrite --dmg-title=micaforgelauncher.dmg
find . -name '*Mica Forge Launcher*.dmg' -execdir mv {} micaforgelauncher.dmg \;
mv "Mica Forge Launcher.app" "micaforgelauncher-signed.app"
printf "Building DMG using create-dmg project from sindresorhus: DONE\n"