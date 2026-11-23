# ArchiveTune for iOS

Native SwiftUI host for the Compose Multiplatform app in `shared`, with AVPlayer
playback and app-sandbox persistence. Requires macOS, Xcode with an iOS Simulator
runtime, and JDK 21. No signing account is needed for the simulator.

```sh
./iosApp/scripts/build-simulator.sh
```

Set `JAVA_HOME` if JDK 21 is installed outside `/Library/Java/JavaVirtualMachines`.
The script supports Intel and Apple Silicon hosts. Xcode invokes the standalone
shared build and embeds the framework for the selected SDK/architecture.

Outputs:

- `iosApp/build/Build/Products/Debug-iphonesimulator/ArchiveTune.app`
- `iosApp/build/ArchiveTune-simulator.zip` (for the Mac architecture used to build it)

For interactive development, open `ArchiveTune.xcodeproj`, select the ArchiveTune
scheme and an iPhone simulator, and run. Ensure Xcode can locate JDK 21. For a
physical iPhone, set your development team in the target's Signing settings and
select your phone. Device signing and physical-device background audio acceptance
are separate from unsigned simulator verification.

Shared behavior tests: `./gradlew -p shared jvmTest`. Apple platform tests:
`./gradlew -p shared iosX64Test` on Intel, or `iosSimulatorArm64Test` on Apple Silicon.

The iOS module uses Compose 1.10.3 and Coil 3.4 to retain Intel simulator support;
newer desktop dependencies are unchanged. The standalone shared build pins its
Compose plugin to the matching version. See `../IOS_PORT.md` for implemented
features, pending parity work, and device acceptance checks.
