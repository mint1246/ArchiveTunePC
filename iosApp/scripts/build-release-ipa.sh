#!/bin/bash
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$(uname -s)" != Darwin ]; then
    echo 'Build on macOS with Xcode and JDK 21.' >&2
    exit 1
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    if JH=$(/usr/libexec/java_home -v 21 2>/dev/null); then
        export JAVA_HOME="$JH"
    elif [ -x "$HOME/.local/jdk21/Contents/Home/bin/java" ]; then
        export JAVA_HOME="$HOME/.local/jdk21/Contents/Home"
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$ROOT"
python3 iosApp/scripts/verify-resources.py

ARCHIVE="$ROOT/iosApp/build/ArchiveTune.xcarchive"
STAGE="$ROOT/iosApp/build/ipa_stage"
IPA="$ROOT/iosApp/build/ArchiveTune-15.2.0-arm64.ipa"
rm -rf "$ARCHIVE" "$STAGE" "$IPA"

xcodebuild -project iosApp/ArchiveTune.xcodeproj \
    -scheme ArchiveTune \
    -configuration Release \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -archivePath "$ARCHIVE" \
    -derivedDataPath "$ROOT/iosApp/build/derived-release" \
    CODE_SIGNING_ALLOWED=NO \
    archive

APP="$ARCHIVE/Products/Applications/ArchiveTune.app"
test -d "$APP"
test "$(/usr/libexec/PlistBuddy -c 'Print :CADisableMinimumFrameDurationOnPhone' "$APP/Info.plist")" = true

mkdir -p "$STAGE/Payload"
cp -R "$APP" "$STAGE/Payload/"
(cd "$STAGE" && zip -qr "$IPA" Payload)
rm -rf "$STAGE"
echo "Built $ARCHIVE"
echo "Built $IPA"
