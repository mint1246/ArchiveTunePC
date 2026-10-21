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
#   APPIMAGETOOL=/path/to/appimagetool.AppImage \
#     packaging/linux/build-appimage.sh --native
#
# With --native the GraalVM native image is packaged instead of the jpackage
# image. Its payload (the executable, the libjava.so/libjvm.so runtime shims,
# the Skiko library in lib/ and the resolver sidecars in resolver/) is copied
# unchanged under usr/lib/archivetune as well, and AppRun starts the native
# executable instead of the jpackage launcher. Both modes therefore keep the
# relative lookups of their own payload and produce different artifacts:
# ArchiveTune-<version>-<arch>.AppImage and
# ArchiveTune-<version>-graal-<arch>.AppImage.
#
# The AppImage is host-ABI specific: it carries the jpackage runtime for the
# build machine's architecture and glibc, and the native image is linked for the
# build machine as well. libVLC, Chromium and secret-tool stay host
# dependencies and are not bundled. Only build output is staged; no user data,
# browser profile, keyring entry, configuration, cache or log is copied.
set -euo pipefail
umask 022

APP_NAME=ArchiveTune
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)

# Payload sources of the two supported modes. The default is selected after the
# options are parsed, so --image overrides the source of both modes.
JVM_IMAGE_DIR=$REPO_ROOT/desktopApp/build/compose/binaries/main/app/$APP_NAME
NATIVE_IMAGE_DIR=$REPO_ROOT/desktopApp/build/compose/binaries/main-native/app/$APP_NAME
IMAGE_DIR=
STAGING_ROOT=$REPO_ROOT/build/packaging/appimage
APPDIR=$STAGING_ROOT/$APP_NAME.AppDir
OUTPUT_DIR=$REPO_ROOT/dist
ICON_SOURCE=$REPO_ROOT/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

VERSION=${VERSION:-15.10}
ARCH=${ARCH:-$(uname -m)}
APPIMAGETOOL=${APPIMAGETOOL:-}
SKIP_BUILD=0
KEEP_STAGING=0
MODE=jvm
IMAGE_OVERRIDE=

info() { printf '%s\n' "==> $*"; }
warn() { printf '%s\n' "warning: $*" >&2; }
die() { printf '%s\n' "error: $*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Build an ArchiveTune AppImage from the Compose jpackage application image, or
from the GraalVM native image with --native.

Options:
  --native            Package the GraalVM native image instead of the jpackage image.
  --skip-build        Do not run Gradle; package the existing image.
  --version <value>   Version used in the output file name (default: 15.10).
  --arch <value>      AppImage architecture (default: host architecture).
  --image <dir>       Application image to package (default: the image of the
                      selected mode; overrides both modes).
  --output <dir>      Output directory (default: <repo>/dist).
  --keep-staging      Keep the generated AppDir for inspection.
  -h, --help          Show this help.

Output:
  The jpackage image becomes <output>/ArchiveTune-<version>-<arch>.AppImage and
  the native image <output>/ArchiveTune-<version>-graal-<arch>.AppImage.

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
    --native) MODE=native ;;
    --skip-build) SKIP_BUILD=1 ;;
    --keep-staging) KEEP_STAGING=1 ;;
    --version) [[ $# -ge 2 ]] || die "--version needs a value"; VERSION=$2; shift ;;
    --version=*) VERSION=${1#*=} ;;
    --arch) [[ $# -ge 2 ]] || die "--arch needs a value"; ARCH=$2; shift ;;
    --arch=*) ARCH=${1#*=} ;;
    --image) [[ $# -ge 2 ]] || die "--image needs a value"; IMAGE_OVERRIDE=$2; shift ;;
    --image=*) IMAGE_OVERRIDE=${1#*=} ;;
    --output) [[ $# -ge 2 ]] || die "--output needs a value"; OUTPUT_DIR=$2; shift ;;
    --output=*) OUTPUT_DIR=${1#*=} ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

# Payload layout of the selected mode.
#
# The jpackage image starts its launcher in bin/ and keeps the resolver sidecars
# in lib/app/resources/resolver; the native image keeps the executable and the
# resolver sidecars in the image root and the Skiko library in lib/.
case "$MODE" in
  jvm)
    DEFAULT_IMAGE_DIR=$JVM_IMAGE_DIR
    FLAVOR=
    DESKTOP_NAME=$APP_NAME
    PAYLOAD_LAUNCHER=bin/$APP_NAME
    PAYLOAD_RESOLVER=lib/app/resources/resolver
    ;;
  native)
    DEFAULT_IMAGE_DIR=$NATIVE_IMAGE_DIR
    FLAVOR=graal
    DESKTOP_NAME="$APP_NAME (GraalVM)"
    PAYLOAD_LAUNCHER=$APP_NAME
    PAYLOAD_RESOLVER=resolver
    ;;
  *) die "unknown mode: $MODE" ;;
esac

# --image overrides the default source of both modes.
IMAGE_DIR=${IMAGE_OVERRIDE:-$DEFAULT_IMAGE_DIR}
RESOLVER_DIR=$IMAGE_DIR/$PAYLOAD_RESOLVER

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
OUTPUT_PATH=$OUTPUT_DIR/$APP_NAME-$VERSION${FLAVOR:+-$FLAVOR}-$ARCH.AppImage

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

require_jvm_image() {
  [[ -x $IMAGE_DIR/bin/$APP_NAME ]] \
    || die "missing launcher: $IMAGE_DIR/bin/$APP_NAME"
  [[ -f $IMAGE_DIR/lib/app/$APP_NAME.cfg ]] \
    || die "missing launcher configuration: $IMAGE_DIR/lib/app/$APP_NAME.cfg"
  [[ -f $IMAGE_DIR/lib/runtime/lib/server/libjvm.so ]] \
    || die "missing bundled runtime: $IMAGE_DIR/lib/runtime/lib/server/libjvm.so"
}

require_native_image() {
  [[ -x $IMAGE_DIR/$APP_NAME ]] \
    || die "missing native executable: $IMAGE_DIR/$APP_NAME"
  # The GraalVM runtime shims replace the full JDK libraries next to the
  # executable. The full JVM copies can cause a crash during shutdown.
  [[ -f $IMAGE_DIR/libjava.so ]] \
    || die "missing native runtime shim: $IMAGE_DIR/libjava.so"
  [[ -f $IMAGE_DIR/libjvm.so ]] \
    || die "missing native runtime shim: $IMAGE_DIR/libjvm.so"
  # Skiko is loaded from the lib/ directory next to the executable.
  local candidate
  local found=0
  for candidate in "$IMAGE_DIR"/lib/libskiko-linux-*.so; do
    if [[ -f $candidate ]]; then
      found=1
      break
    fi
  done
  [[ $found -eq 1 ]] \
    || die "missing Skiko native library: $IMAGE_DIR/lib/libskiko-linux-*.so"
}

require_image() {
  case "$MODE" in
    jvm)
      [[ -d $IMAGE_DIR ]] \
        || die "jpackage image not found: $IMAGE_DIR (run ./gradlew :desktopApp:createDistributable with JDK 21, or drop --skip-build)"
      require_jvm_image
      ;;
    native)
      [[ -d $IMAGE_DIR ]] \
        || die "native image not found: $IMAGE_DIR (run ./gradlew nativeRelease with JDK 21, or drop --skip-build)"
      require_native_image
      ;;
  esac
  [[ -f $ICON_SOURCE ]] || die "missing launcher icon: $ICON_SOURCE"
}

# The hybrid resolver requires yt-dlp for compatibility fallback. The Gradle stage task writes these
# beside the launcher's compose.application.resources.dir under lib/app/resources.
require_resolver() {
  local resolver_dir=$1
  local name
  for name in yt-dlp bun manifest.properties THIRD_PARTY_NOTICES.md; do
    [[ -f $resolver_dir/$name ]] \
      || die "resolver sidecar missing: $resolver_dir/$name (rebuild the image so the resolver sidecars are staged again)"
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
  if [[ $MODE == native ]]; then
    info "building the GraalVM native image with $JAVA_HOME"
    (
      cd "$REPO_ROOT"
      ./gradlew nativeRelease
    )
  else
    info "building the jpackage image with $JAVA_HOME"
    (
      cd "$REPO_ROOT"
      ./gradlew :desktopApp:createDistributable
    )
  fi
else
  info "--skip-build: using the existing $MODE image"
fi

require_image
require_resolver "$RESOLVER_DIR"

info "staging $APP_NAME $VERSION ($ARCH, $MODE image)"
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
Name=$DESKTOP_NAME
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

# The AppRun starts the launcher of the selected mode. Both launchers resolve
# their own payload relative to their own path, so the payload stays at
# usr/lib/archivetune in either mode.
if [[ $MODE == native ]]; then
  cat >"$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
# Entry point for the AppImage. The native executable finds its runtime shims,
# the Skiko library in lib/ and the resolver sidecars in resolver/ relative to
# its own path, so only the AppDir root is needed.
set -eu
APPDIR="${APPDIR:-$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)}"
exec "$APPDIR/usr/lib/archivetune/ArchiveTune" "$@"
APPRUN
else
  cat >"$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
# Entry point for the AppImage. The jpackage launcher locates lib/app and
# lib/runtime relative to its own path, so only the AppDir root is needed.
set -eu
APPDIR="${APPDIR:-$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)}"
exec "$APPDIR/usr/lib/archivetune/bin/ArchiveTune" "$@"
APPRUN
fi
chmod 0755 -- "$APPDIR/AppRun"

# appimagetool needs the desktop file and icon at the AppDir root, named after
# the Icon= value, in addition to the hicolor/application copies above.
[[ -x $APPDIR/usr/lib/archivetune/$PAYLOAD_LAUNCHER ]] \
  || die "staged launcher is missing or not executable"
[[ -x $APPDIR/usr/lib/archivetune/$PAYLOAD_RESOLVER/yt-dlp ]] \
  || die "staged yt-dlp resolver sidecar is missing or not executable"
[[ -x $APPDIR/usr/lib/archivetune/$PAYLOAD_RESOLVER/bun ]] \
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
