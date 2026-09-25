#!/usr/bin/env bash
# Cross-compile a static ffmpeg (+ libmp3lame, libopus) for Android using the NDK.
# Usage: build-ffmpeg.sh <abi>   e.g. arm64-v8a | armeabi-v7a | x86_64
# Result binaries are copied to app/src/main/jniLibs/<abi>/libffmpeg.so, libffprobe.so
set -euo pipefail

ABI="${1:?usage: $0 <abi>}"
API=24
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?NDK env not found}}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
PREFIX="$PWD/prefix-$ABI"
BUILD="$PWD/build-$ABI"
JOBS="$(nproc)"

case "$ABI" in
  arm64-v8a)
    TRIP=aarch64-linux-android; ARCH=aarch64; CPU=armv8-a ;;
  armeabi-v7a)
    TRIP=armv7a-linux-androideabi; ARCH=arm; CPU=armv7-a
    ARCH_FLAGS="-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp" ;;
  x86_64)
    TRIP=x86_64-linux-android; ARCH=x86_64; CPU=nehalem ;;
  *) echo "unknown abi $ABI"; exit 1 ;;
esac
ARCH_FLAGS="${ARCH_FLAGS:-}"

export PATH="$TOOLCHAIN/bin:$PATH"
CC="$TRIP$API-clang"
CXX="$TRIP$API-clang++"
# NDK >= r26 ships only llvm-* binutils; provide prefixed names for configure/libtool
for t in ar as nm ranlib strip objcopy readelf; do
  ln -sf "$TOOLCHAIN/bin/llvm-$t" "$TOOLCHAIN/bin/$TRIP-$t"
done
mkdir -p "$PREFIX" "$BUILD"
cd "$BUILD"

dl() {
  local url="$1" tarball
  tarball="$(basename "$url")"
  for i in 1 2 3; do
    curl -fL --retry 3 -o "$tarball" "$url" && break
    [ "$i" = 3 ] && return 1
    sleep 5
  done
  tar xf "$tarball"
  echo "${tarball%.tar.*}" | sed 's/-[0-9].*//' > /dev/null || true
}

echo "==> lame"
dl https://download.sourceforge.net/lame/lame-3.100.tar.gz
(cd lame-3.100 && ./configure \
  --host="$TRIP" --prefix="$PREFIX" \
  --enable-static --disable-shared --disable-frontend --disable-analyzer-hooks \
  --disable-gtktest CC="$CC" CXX="$CXX" RANLIB="$TRIP-ranlib" \
  CFLAGS="-O2 -fPIC $ARCH_FLAGS" && make -j"$JOBS" && make install)

# lame does not ship a pkg-config file; ffmpeg requires one
mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$PREFIX/lib/pkgconfig/mp3lame.pc" <<PCEOF
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: mp3lame
Description: MP3 audio encoder
Version: 3.100.0
Cflags: -I\${includedir} -I\${includedir}/lame
Libs: -L\${libdir} -lmp3lame -lm
PCEOF
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

echo "==> opus"
dl https://archive.xiph.org/src/opus/opus-1.5.2.tar.gz
(cd opus-1.5.2 && ./configure \
  --host="$TRIP" --prefix="$PREFIX" \
  --enable-static --disable-shared --disable-doc --disable-extra-programs \
  CC="$CC" CXX="$CXX" RANLIB="$TRIP-ranlib" \
  CFLAGS="-O2 -fPIC $ARCH_FLAGS" CXXFLAGS="-O2 -fPIC $ARCH_FLAGS" && \
  make -j"$JOBS" && make install)

echo "==> ffmpeg"
dl https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz
(cd ffmpeg-7.1 && ./configure \
  --target-os=android \
  --enable-cross-compile \
  --cross-prefix="$TRIP-" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --arch="$ARCH" --cpu="$CPU" \
  --cc="$CC" --cxx="$CXX" \
  --prefix="$PREFIX" \
  --enable-static --disable-shared \
  --disable-autodetect \
  --disable-doc --disable-debug --enable-small \
  --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
  --enable-gpl \
  --enable-libmp3lame --enable-libopus \
  --enable-ffmpeg --enable-ffprobe --disable-ffplay \
  --extra-cflags="-I$PREFIX/include $ARCH_FLAGS" \
  --extra-ldflags="-L$PREFIX/lib" \
  --extra-libs="-lmp3lame -lopus -lm" \
  --pkg-config-flags=--static \
  --pkg-config=pkg-config && \
  make -j"$JOBS" && make install)"

OUT="app/src/main/jniLibs/$ABI"
mkdir -p "$OUT"
cp "$PREFIX/bin/ffmpeg" "$OUT/libffmpeg.so"
cp "$PREFIX/bin/ffprobe" "$OUT/libffprobe.so"
chmod +x "$OUT/libffmpeg.so" "$OUT/libffprobe.so"
ls -la "$OUT"
echo "==> done: $ABI"
