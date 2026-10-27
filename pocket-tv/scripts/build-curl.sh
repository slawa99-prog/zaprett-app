#!/usr/bin/env bash
# Android executable with libcurl and OpenSSL linked in; only Android system libs remain.
set -euo pipefail
ABI="${1:?Android ABI required}"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$PROJECT/build/native-$ABI"
PREFIX="$WORK/prefix"
NDK_VERSION=27.2.12479018
export ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?}}/ndk/$NDK_VERSION"
TOOLS="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$TOOLS:$PATH"
case "$ABI" in
  arm64-v8a) TARGET=android-arm64 ;;
  armeabi-v7a) TARGET=android-arm ;;
  x86_64) TARGET=android-x86_64 ;;
  x86) TARGET=android-x86 ;;
  *) exit 2 ;;
esac
mkdir -p "$WORK" "$PREFIX"
cd "$WORK"
curl -fL --retry 3 -o openssl.tar.gz https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz
curl -fL --retry 3 -o curl.tar.xz https://github.com/curl/curl/releases/download/curl-8_22_0/curl-8.22.0.tar.xz
printf '%s\n' 'a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2  openssl.tar.gz' 'f7ef3ae8a22e521f289803fe93543eb64c329b58aa73a9e224dfd915a2a5f4f7  curl.tar.xz' | sha256sum -c -
tar -xzf openssl.tar.gz
tar -xJf curl.tar.xz
cd openssl-3.5.8
./Configure "$TARGET" -D__ANDROID_API__=28 no-shared no-tests no-module no-legacy --prefix="$PREFIX" --libdir=lib -Os -fPIC -Wl,-z,max-page-size=16384
make -j"$(nproc)" build_libs
make install_dev
cd "$WORK"
cmake -S curl-8.22.0 -B curl-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_STATIC_CURL=ON \
  -DBUILD_TESTING=OFF -DBUILD_LIBCURL_DOCS=OFF -DBUILD_MISC_DOCS=OFF -DENABLE_CURL_MANUAL=OFF \
  -DHTTP_ONLY=ON -DCURL_USE_OPENSSL=ON -DOPENSSL_USE_STATIC_LIBS=ON \
  -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
  -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" \
  -DCURL_USE_PKGCONFIG=OFF -DCURL_USE_LIBPSL=OFF -DCURL_ZLIB=OFF -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF \
  -DUSE_LIBIDN2=OFF -DUSE_NGHTTP2=OFF -DCURL_USE_LIBSSH2=OFF \
  -DCURL_CA_BUNDLE=none -DCURL_CA_PATH=none -DCURL_DISABLE_OPENSSL_AUTO_LOAD_CONFIG=ON
cmake --build curl-build --target curl --parallel "$(nproc)"
OUT="$PROJECT/src/main/jniLibs/$ABI"
mkdir -p "$OUT" "$PROJECT/build/reports/native"
cp curl-build/src/curl "$OUT/libpocketcurl.so"
"$TOOLS/llvm-strip" "$OUT/libpocketcurl.so"
"$TOOLS/llvm-readelf" -d "$OUT/libpocketcurl.so" > "$PROJECT/build/reports/native/$ABI.txt"
# A missing Termux/OpenSSL dependency must be a build failure, not a device surprise.
python3 - "$PROJECT/build/reports/native/$ABI.txt" <<'PY'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text()
deps=re.findall(r'\(NEEDED\).*?\[(.*?)\]', text)
assert deps and set(deps) <= {'libc.so','libdl.so','libm.so','liblog.so','libandroid.so'}, deps
assert 'com.termux' not in text, text
print('Android system dependencies:', ', '.join(deps))
PY
mkdir -p "$PROJECT/src/main/assets/licenses"
cp "$WORK/curl-8.22.0/COPYING" "$PROJECT/src/main/assets/licenses/curl.txt"
cp "$WORK/openssl-3.5.8/LICENSE.txt" "$PROJECT/src/main/assets/licenses/openssl.txt"
sha256sum "$OUT/libpocketcurl.so"
