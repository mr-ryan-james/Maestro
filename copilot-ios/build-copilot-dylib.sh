#!/usr/bin/env bash
# Builds the Maestro copilot as a fat iOS-simulator dylib (arm64 + x86_64), suitable for
# injection via SIMCTL_CHILD_DYLD_INSERT_LIBRARIES. Self-contained: links only the iOS SDK.
set -euo pipefail
cd "$(dirname "$0")"

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
DEPLOY="${COPILOT_IOS_DEPLOYMENT_TARGET:-16.0}"
OUT_DIR="build"
mkdir -p "$OUT_DIR"

BOOT_C="Sources/CBootstrap/bootstrap.c"
SWIFT_SOURCES=(Sources/CopilotIOS/*.swift)
FRAMEWORKS=(-framework UIKit -framework Foundation -framework QuartzCore)

build_arch() {
  local arch="$1"
  local triple="${arch}-apple-ios${DEPLOY}-simulator"
  local boot_o="$OUT_DIR/bootstrap-${arch}.o"
  local dylib="$OUT_DIR/libmaestro-copilot-${arch}.dylib"

  xcrun clang -c "$BOOT_C" -target "$triple" -isysroot "$SDK" -o "$boot_o"
  xcrun swiftc -emit-library -o "$dylib" \
    -target "$triple" -sdk "$SDK" -swift-version 5 \
    -module-name MaestroCopilot \
    -O \
    "${SWIFT_SOURCES[@]}" "$boot_o" \
    "${FRAMEWORKS[@]}" \
    -Xlinker -install_name -Xlinker "@rpath/libmaestro-copilot.dylib"
  echo "$dylib"
}

echo "[copilot] building arm64 (simulator, ios${DEPLOY})..."
ARM_DYLIB="$(build_arch arm64)"
echo "[copilot] building x86_64 (simulator, ios${DEPLOY})..."
X86_DYLIB="$(build_arch x86_64)"

FAT="$OUT_DIR/libmaestro-copilot.dylib"
lipo -create "$ARM_DYLIB" "$X86_DYLIB" -output "$FAT"

# Stage into the driver's resources so it ships in maestro-ios-driver.jar via installDist,
# exactly like the prebuilt XCTest runner zips. No Xcode needed at package/install time.
RES_DIR="../maestro-ios-driver/src/main/resources/copilot"
mkdir -p "$RES_DIR"
cp "$FAT" "$RES_DIR/libmaestro-copilot.dylib"

echo "[copilot] built fat dylib: $FAT"
echo "[copilot] staged to: $RES_DIR/libmaestro-copilot.dylib"
lipo -info "$FAT"
file "$FAT"
