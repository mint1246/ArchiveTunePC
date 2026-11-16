#!/bin/bash
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$(uname -s)" != Darwin ]; then
    echo 'Build on macOS with Xcode and JDK 21.' >&2
    exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export JAVA_HOME
fi
case "$(uname -m)" in
    arm64) SIM_ARCH=arm64 ;;
    x86_64) SIM_ARCH=x86_64 ;;
    *) echo 'Unsupported Mac architecture.' >&2; exit 1 ;;
esac
cd "$ROOT"
python3 iosApp/scripts/verify-resources.py
xcodebuild -project iosApp/ArchiveTune.xcodeproj -scheme ArchiveTune \
    -configuration Debug -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build \
    ARCHS="$SIM_ARCH" CODE_SIGNING_ALLOWED=NO build
APP="$ROOT/iosApp/build/Build/Products/Debug-iphonesimulator/ArchiveTune.app"
test -d "$APP"
# Compose enforces this at startup; a successful compile alone cannot catch it.
test "$(/usr/libexec/PlistBuddy -c 'Print :CADisableMinimumFrameDurationOnPhone' "$APP/Info.plist")" = true
ditto -c -k --sequesterRsrc --keepParent "$APP" "$ROOT/iosApp/build/ArchiveTune-simulator.zip"
echo "Built $APP"
