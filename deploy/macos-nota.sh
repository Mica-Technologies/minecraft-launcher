#!/bin/bash

#
# Copyright (c) 2020 Mica Technologies
#
# This program is free software: you can redistribute it and/or modify it
# under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License,
# or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty
# of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#

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

