#!/bin/bash

# Print Welcome/Startup Msg
printf "\n\nMica Technologies macOS App Notarization Script v0.1\n" | tee -a notarize.log
now="$(date)"
printf "TIME: %s\n" "$now" | tee -a notarize.log

#### FUNCTIONS
function_doSubmit() {
	echo "Submitting application to Apple Developer services for notarization" | tee -a notarize.log
	xcrun altool -t osx -f micaforgelauncher.dmg --primary-bundle-id com.micatechnologies.minecraft.forgelauncher.LauncherApp --notarize-app -u hawkaj@icloud.com -p "@keychain:APPLENOTARIZE" | tee -a notarize.log
}

function_doCheck() {
	echo "Checking the status of a notarization request" | tee -a notarize.log
	read -p "Enter the notarization request ID: " reqid
	xcrun altool --notarization-info $reqid -u hawkaj@icloud.com -p "@keychain:APPLENOTARIZE"  | tee -a notarize.log
	select yn in "Staple dmg Now" "Exit"; do
		case $yn in
			"Staple dmg Now" ) function_doStaple; break;;
			"Exit" ) break;;
		esac
	done
}

function_doStaple() {
	echo "Stapling dmg file" | tee -a notarize.log
	xcrun stapler staple micaforgelauncher.dmg | tee -a notarize.log
	xcrun stapler validate micaforgelauncher.dmg | tee -a notarize.log
}
#### END FUNCTIONS

# Check which mode to run
printf "\nThis script has multiple modes. Please select one!\n"
select yn in "Submit to Apple" "Check Apple Request" "Staple Ticket"; do
	case $yn in
		"Submit to Apple" ) function_doSubmit; break;;
		"Check Apple Request" ) function_doCheck; break;;
		"Staple Ticket" ) function_doStaple; break;;
	esac
done

printf "\nNotarization Script Complete\n" | tee -a notarize.log

