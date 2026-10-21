#!/usr/bin/env bash
#
# Wrap the Compose/jpackage Linux application image in an AppImage.
#
# The AppDir only rearranges the jpackage image: bin/ArchiveTune, lib/app jars,
# the trimmed runtime and the launcher configuration are copied unchanged under
# usr/lib/archivetune so the launcher keeps resolving $APPDIR relative to its own
# location. Nothing is compiled here except the optional Gradle build.
#
# Usage:
#   JAVA_HOME=/path/to/jdk-21 APPIMAGETOOL=/path/to/appimagetool.AppImage \
#     packaging/linux/build-appimage.sh
#
#   APPIMAGETOOL=/path/to/appimagetool.AppImage \
#     packaging/linux/build-appimage.sh --skip-build
#
# The AppImage is host-ABI specific: it carries the jpackage runtime for the
# build machine's architecture and glibc. libVLC, Chromium and secret-tool stay
# host dependencies and are not bundled. Only build output is staged; no user
# data, browser profile, keyring entry, configuration, cache or log is copied.
set -euo pipefail
umask 022

APP_NAME=ArchiveTune
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)

IMAGE_DIR=$REPO_ROOT/desktopApp/build/compose/binaries/main/app/$APP_NAME
STAGING_ROOT=$REPO_ROOT/build/packaging/appimage
APPDIR=$STAGING_ROOT/$APP_NAME.AppDir
OUTPUT_DIR=$REPO_ROOT/dist
ICON_SOURCE=$REPO_ROOT/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

VERSION=${VERSION:-15.10}
ARCH=${ARCH:-$(uname -m)}
APPIMAGETOOL=${APPIMAGETOOL:-}
SKIP_BUILD=0
KEEP_STAGING=0

info() { printf '%s\n' "==> $*"; }
warn() { printf '%s\n' "warning: $*" >&2; }
die() { printf '%s\n' "error: $*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Build an ArchiveTune AppImage from the Compose jpackage application image.

Options:
  --skip-build        Do not run Gradle; package the existing jpackage image.
  --version <value>   Version used in the output file name (default: 15.10).
  --arch <value>      AppImage architecture (default: host architecture).
  --image <dir>       jpackage application image to package.
  --output <dir>      Output directory (default: <repo>/dist).
  --keep-staging      Keep the generated AppDir for inspection.
  -h, --help          Show this help.

Environment:
  JAVA_HOME           JDK 21 used for the Gradle build (skipped with --skip-build).
  APPIMAGETOOL        Path to appimagetool (binary or AppImage). Required.
  APPIMAGE_EXTRACT_AND_RUN
                      Defaults to 1 so appimagetool runs without FUSE.
  VERSION, ARCH       Defaults for --version and --arch.
USAGE
}

normalize_arch() {
  case "$1" in
    amd64 | x86_64) printf '%s\n' x86_64 ;;
    arm64 | aarch64) printf '%s\n' aarch64 ;;
    *) printf '%s\n' "$1" ;;
  esac
}

while (($# > 0)); do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --keep-staging) KEEP_STAGING=1 ;;
    --version) [[ $# -ge 2 ]] || die "--version needs a value"; VERSION=$2; shift ;;
    --version=*) VERSION=${1#*=} ;;
    --arch) [[ $# -ge 2 ]] || die "--arch needs a value"; ARCH=$2; shift ;;
    --arch=*) ARCH=${1#*=} ;;
    --image) [[ $# -ge 2 ]] || die "--image needs a value"; IMAGE_DIR=$2; shift ;;
    --image=*) IMAGE_DIR=${1#*=} ;;
    --output) [[ $# -ge 2 ]] || die "--output needs a value"; OUTPUT_DIR=$2; shift ;;
    --output=*) OUTPUT_DIR=${1#*=} ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

[[ -n $VERSION ]] || die "version must not be empty"
[[ $VERSION =~ ^[0-9][0-9A-Za-z._+~-]*$ ]] \
  || die "unsupported version '$VERSION'; use digits with optional . _ + ~ - characters"

ARCH=$(normalize_arch "$ARCH")
HOST_ARCH=$(normalize_arch "$(uname -m)")
[[ -n $ARCH ]] || die "architecture must not be empty"
[[ $ARCH == "$HOST_ARCH" ]] \
  || die "cannot package $ARCH on $HOST_ARCH: the jpackage runtime is built for the host architecture"

# Resolve a relative output directory before anything changes the working
# directory, then create it so the final path is absolute.
case "$OUTPUT_DIR" in
  /*) ;;
  *) OUTPUT_DIR=$PWD/$OUTPUT_DIR ;;
esac
mkdir -p -- "$OUTPUT_DIR"
OUTPUT_DIR=$(CDPATH= cd -- "$OUTPUT_DIR" && pwd -P)
OUTPUT_PATH=$OUTPUT_DIR/$APP_NAME-$VERSION-$ARCH.AppImage

resolve_tool() {
  local tool=${APPIMAGETOOL:-}
  if [[ -z $tool ]]; then
    tool=$(command -v appimagetool || true)
  fi
  [[ -n $tool ]] \
    || die "appimagetool not found; set APPIMAGETOOL=/path/to/appimagetool.AppImage"
  [[ -f $tool ]] || die "APPIMAGETOOL is not a file: $tool"
  [[ -x $tool ]] || die "APPIMAGETOOL is not executable: $tool"
  APPIMAGETOOL=$tool
}

check_java() {
  [[ -n ${JAVA_HOME:-} ]] \
    || die "JAVA_HOME must point at a JDK 21 when the script builds the image (or pass --skip-build)"
  [[ -x $JAVA_HOME/bin/jpackage ]] \
    || die "JAVA_HOME does not contain bin/jpackage: $JAVA_HOME (a JDK 21 is required)"
  local spec
  spec=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^ *java\.specification\.version = //p' | head -n 1)
  if [[ -z $spec ]]; then
    warn "could not read the Java version from $JAVA_HOME; continuing"
  elif [[ ${spec%%.*} -lt 21 ]]; then
    die "JDK 21 or newer is required, found $spec in $JAVA_HOME"
  fi
}

require_image() {
  [[ -d $IMAGE_DIR ]] \
    || die "jpackage image not found: $IMAGE_DIR (run ./gradlew :desktopApp:createDistributable with JDK 21, or drop --skip-build)"
  [[ -x $IMAGE_DIR/bin/$APP_NAME ]] \
    || die "missing launcher: $IMAGE_DIR/bin/$APP_NAME"
  [[ -f $IMAGE_DIR/lib/app/$APP_NAME.cfg ]] \
    || die "missing launcher configuration: $IMAGE_DIR/lib/app/$APP_NAME.cfg"
  [[ -f $IMAGE_DIR/lib/runtime/lib/server/libjvm.so ]] \
    || die "missing bundled runtime: $IMAGE_DIR/lib/runtime/lib/server/libjvm.so"
  [[ -f $ICON_SOURCE ]] || die "missing launcher icon: $ICON_SOURCE"
}

# yt-dlp is the default desktop stream resolver, so an image without its staged sidecars
# would install an AppImage that cannot play YouTube audio. The Gradle stage task writes these
# beside the launcher's compose.application.resources.dir under lib/app/resources.
require_resolver() {
  local resolver_dir=$IMAGE_DIR/lib/app/resources/resolver
  local name
  for name in yt-dlp bun manifest.properties THIRD_PARTY_NOTICES.md; do
    [[ -f $resolver_dir/$name ]] \
      || die "resolver sidecar missing: $resolver_dir/$name (rebuild :desktopApp:createDistributable so stageBundledResolverSidecars runs)"
  done
  [[ -x $resolver_dir/yt-dlp ]] || die "resolver yt-dlp is not executable: $resolver_dir/yt-dlp"
  [[ -x $resolver_dir/bun ]] || die "resolver bun is not executable: $resolver_dir/bun"
  grep -qx 'platform=linux-x86_64' "$resolver_dir/manifest.properties" \
    || die "resolver sidecars were not staged for linux-x86_64: $resolver_dir/manifest.properties"
  grep -qx 'bun.file=bun' "$resolver_dir/manifest.properties" \
    || die "resolver manifest does not describe the Linux Bun sidecar: $resolver_dir/manifest.properties"
  # The retired QuickJS runtime must not survive in a package that ships Bun.
  [[ ! -e $resolver_dir/qjs && ! -e $resolver_dir/qjs.exe ]] \
    || die "stale QuickJS sidecar found beside the Bun runtime: $resolver_dir"
}

# Delete only the staging directory this script owns. Anything else is refused.
clean_staging() {
  [[ -n $STAGING_ROOT && -n $APP_NAME ]] || die "staging paths are not initialized"
  case "$APPDIR" in
    "$STAGING_ROOT"/*) ;;
    *) die "refusing to remove unexpected staging path: $APPDIR" ;;
  esac
  [[ $APPDIR == */"$APP_NAME".AppDir ]] \
    || die "refusing to remove unexpected staging path: $APPDIR"
  [[ $STAGING_ROOT == "$REPO_ROOT"/build/* ]] \
    || die "refusing to remove staging outside the build directory: $STAGING_ROOT"
  if [[ -e $APPDIR ]]; then
    rm -rf --one-file-system -- "$APPDIR"
  fi
}

cleanup() {
  if [[ $KEEP_STAGING -eq 0 ]]; then
    clean_staging
  else
    info "keeping staging directory: $APPDIR"
  fi
}
trap cleanup EXIT

resolve_tool

if [[ $SKIP_BUILD -eq 0 ]]; then
  check_java
  info "building the jpackage image with $JAVA_HOME"
  (
    cd "$REPO_ROOT"
    ./gradlew :desktopApp:createDistributable
  )
else
  info "--skip-build: using the existing jpackage image"
fi

require_image
require_resolver

info "staging $APP_NAME $VERSION ($ARCH)"
clean_staging
mkdir -p -- \
  "$APPDIR/usr/lib/archivetune" \
  "$APPDIR/usr/share/applications" \
  "$APPDIR/usr/share/icons/hicolor/192x192/apps"

# Preserve the image layout, modes and the launcher's runtime-relative lookups.
cp -a -- "$IMAGE_DIR/." "$APPDIR/usr/lib/archivetune/"

install -m 0644 -- "$ICON_SOURCE" "$APPDIR/archivetune.png"
install -m 0644 -- "$ICON_SOURCE" "$APPDIR/usr/share/icons/hicolor/192x192/apps/archivetune.png"

cat >"$APPDIR/archivetune.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=ArchiveTune
GenericName=Music Player
Comment=A private, feature-rich YouTube Music client and music player
Exec=archivetune
Icon=archivetune
Terminal=false
StartupNotify=true
StartupWMClass=ArchiveTune
Categories=AudioVideo;Audio;Player;
Keywords=music;youtube;player;streaming;
X-AppImage-Version=$VERSION
DESKTOP
install -m 0644 -- "$APPDIR/archivetune.desktop" \
  "$APPDIR/usr/share/applications/archivetune.desktop"

cat >"$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
# Entry point for the AppImage. The jpackage launcher locates lib/app and
# lib/runtime relative to its own path, so only the AppDir root is needed.
set -eu
APPDIR="${APPDIR:-$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)}"
exec "$APPDIR/usr/lib/archivetune/bin/ArchiveTune" "$@"
APPRUN
chmod 0755 -- "$APPDIR/AppRun"

# appimagetool needs the desktop file and icon at the AppDir root, named after
# the Icon= value, in addition to the hicolor/application copies above.
[[ -x $APPDIR/usr/lib/archivetune/bin/$APP_NAME ]] \
  || die "staged launcher is missing or not executable"
[[ -x $APPDIR/usr/lib/archivetune/lib/app/resources/resolver/yt-dlp ]] \
  || die "staged yt-dlp resolver sidecar is missing or not executable"
[[ -x $APPDIR/usr/lib/archivetune/lib/app/resources/resolver/bun ]] \
  || die "staged Bun resolver sidecar is missing or not executable"

info "packaging with $APPIMAGETOOL"
export ARCH
# Run appimagetool without FUSE, which also covers tools stored as AppImages.
export APPIMAGE_EXTRACT_AND_RUN="${APPIMAGE_EXTRACT_AND_RUN:-1}"
rm -f -- "$OUTPUT_PATH"
"$APPIMAGETOOL" "$APPDIR" "$OUTPUT_PATH"

[[ -f $OUTPUT_PATH ]] || die "appimagetool did not produce $OUTPUT_PATH"
chmod 0755 -- "$OUTPUT_PATH"

info "created $OUTPUT_PATH ($(du -h -- "$OUTPUT_PATH" | cut -f1))"
if command -v sha256sum >/dev/null 2>&1; then
  info "sha256: $(sha256sum -- "$OUTPUT_PATH" | cut -d' ' -f1)"
fi
printf '%s\n' "$OUTPUT_PATH"
