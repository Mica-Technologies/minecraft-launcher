#!/usr/bin/env bash
# Copyright (c) 2026 Mica Technologies
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

# Packages the Linux app image (with the native starter already in front of the jpackage launcher)
# as an AppImage: the format for systems where the .deb / .rpm cannot be installed, above all
# SteamOS, whose system partition is read-only and replaced on every update. An AppImage is one
# file the user keeps in their home folder.
#
# Run by the linux-packaging profile after the starter step. Uses pinned releases of appimagetool
# and of the static type2-runtime, checked by SHA-256. The static runtime needs no libfuse2 on the
# user's system (a SteamOS preview build and Fedora Atomic have already dropped it). AppRun is a
# link to the starter, which sees $APPIMAGE and registers that file, not the temporary mount, as
# the launcher's path (MMCL_STARTER_PATH).
#
# Linux x86_64 only. Needs curl and sha256sum; downloads ~12 MB of tools on every build.
#
# usage: build-appimage.sh <app image> <name> <version> <environment> <icon> <work dir> <dest dir>
#   <app image>  the jpackage image folder (holding bin/ and lib/)
#   <icon>       the launcher icon, PNG
#   <dest dir>   where the .AppImage is written

set -euo pipefail

app_image="$1"
name="$2"
version="$3"
environment="$4"
icon="$5"
work_dir="$6"
dest_dir="$7"

# ---- pinned tools --------------------------------------------------------------------------
APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
RUNTIME_URL="https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64"
RUNTIME_SHA256="2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"

if [ "$(uname -s)" != "Linux" ] || [ "$(uname -m)" != "x86_64" ]; then
    echo "build-appimage.sh: only Linux x86_64 is supported (got $(uname -s) $(uname -m))" >&2
    exit 1
fi
if [ ! -x "$app_image/bin/$name" ] || [ ! -x "$app_image/bin/$name Core" ]; then
    echo "build-appimage.sh: '$app_image' has no starter and Core launcher in bin/; run the starter step first" >&2
    exit 1
fi

fetch() {
    local url="$1" file="$2" sha256="$3"
    curl -fsSL --retry 3 --output "$file" "$url"
    if ! echo "$sha256  $file" | sha256sum --check --status -; then
        echo "build-appimage.sh: checksum mismatch for $url" >&2
        exit 1
    fi
}

tools="$work_dir/tools"
mkdir -p "$tools"
fetch "$APPIMAGETOOL_URL" "$tools/appimagetool" "$APPIMAGETOOL_SHA256"
fetch "$RUNTIME_URL" "$tools/runtime-x86_64" "$RUNTIME_SHA256"
chmod +x "$tools/appimagetool"

# ---- AppDir --------------------------------------------------------------------------------
if [ "$environment" = "DEV" ]; then id="mica-minecraft-launcher-dev"; else id="mica-minecraft-launcher"; fi

app_dir="$work_dir/AppDir"
rm -rf "$app_dir"
mkdir -p "$app_dir"
cp -a "$app_image/." "$app_dir/"
ln -s "bin/$name" "$app_dir/AppRun"
cp "$icon" "$app_dir/$id.png"
ln -s "$id.png" "$app_dir/.DirIcon"
# Only the AppImage tooling reads this file; the launcher writes the desktop entry users see
# (SchemeRegistrar), with the AppImage's own path in Exec.
cat > "$app_dir/$id.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$name
Comment=Minecraft modpack launcher
Exec=$id %u
Icon=$id
Terminal=false
Categories=Game;
Keywords=minecraft;modpack;forge;launcher;mica;
EOF

# ---- build ---------------------------------------------------------------------------------
mkdir -p "$dest_dir"
out="$dest_dir/${name// /-}-$version-x86_64.AppImage"
rm -f "$out"
# appimagetool is itself an AppImage; extract-and-run spares the build host a FUSE dependency.
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tools/appimagetool" --no-appstream \
    --runtime-file "$tools/runtime-x86_64" "$app_dir" "$out"
chmod 755 "$out"
echo "AppImage built: $out"
