#!/usr/bin/env bash
#
# Fetches all third-party native components required to build ZapretAndroid:
#
#   * byedpi              (MIT)  - no-root DPI bypass engine, compiled into libbyedpi.so
#   * hev-socks5-tunnel   (MIT)  - tun2socks used by the VpnService
#   * nfqws from zapret   (MIT)  - root engine, prebuilt Android binaries from the release
#
# Usage: scripts/fetch-native-sources.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
JNI="$ROOT/app/src/main/jni"
JNILIBS="$ROOT/app/src/main/jniLibs"

# Pinned versions – bump deliberately, not accidentally.
BYEDPI_COMMIT="ba532298de7b28cfe854aea83d061369d13ca290"
HEV_COMMIT="4d6c334dbfb68a79d1970c2744e62d09f71df12f"
ZAPRET_VERSION="${ZAPRET_VERSION:-v72.13}"

clone_at() {
    local url="$1" dir="$2" commit="$3" recursive="${4:-no}"
    if [ -d "$dir/.git" ]; then
        echo "==> $dir already present, updating"
    else
        rm -rf "$dir"
        mkdir -p "$dir"
        git -C "$dir" init -q
        git -C "$dir" remote add origin "$url"
    fi
    git -C "$dir" fetch -q --depth 1 origin "$commit"
    git -C "$dir" checkout -q FETCH_HEAD
    if [ "$recursive" = "recursive" ]; then
        git -C "$dir" submodule update --init --recursive --depth 1
    fi
    materialize_symlinks "$dir"
}

# Git Bash на Windows кладёт вместо симлинков текстовые файлы с путём внутри —
# компилятор такой "заголовок" прочитать не может. Заменяем на #include-заглушки.
materialize_symlinks() {
    local dir="$1" mode path target text
    git -C "$dir" ls-files -s --recurse-submodules 2>/dev/null |
    while IFS=$'\t' read -r meta path; do
        case "$meta" in 120000*) ;; *) continue ;; esac
        target="$dir/$path"
        [ -L "$target" ] && continue
        [ -f "$target" ] || continue
        text="$(cat "$target")"
        case "$text" in *'"'*|*$'\n'*|"") continue ;; esac
        [ -f "$(dirname "$target")/$text" ] || continue
        case "$path" in
            *.h|*.hh|*.hpp|*.inc|*.c|*.cc|*.cpp|*.cxx|*.m|*.mm)
                printf '#include "%s"\n' "$text" > "$target" ;;
            *)
                cp "$(dirname "$target")/$text" "$target" ;;
        esac
        echo "    symlink fixed: $path"
    done
}

echo "==> byedpi"
clone_at "https://github.com/hufrea/byedpi.git" "$CPP/byedpi" "$BYEDPI_COMMIT"

echo "==> hev-socks5-tunnel"
clone_at "https://github.com/heiher/hev-socks5-tunnel.git" "$JNI/hev-socks5-tunnel" "$HEV_COMMIT" recursive

echo "==> nfqws $ZAPRET_VERSION"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/zapret.zip" \
    "https://github.com/bol-van/zapret/releases/download/$ZAPRET_VERSION/zapret-$ZAPRET_VERSION.zip"
unzip -q -o "$TMP/zapret.zip" -d "$TMP/zapret"

BASE="$(find "$TMP/zapret" -maxdepth 2 -type d -name binaries | head -n1)"
if [ -z "$BASE" ]; then
    echo "!! binaries/ directory not found inside the release archive" >&2
    exit 1
fi

copy_binary() {
    local src_dir="$1" abi="$2"
    if [ -f "$BASE/$src_dir/nfqws" ]; then
        mkdir -p "$JNILIBS/$abi"
        cp "$BASE/$src_dir/nfqws" "$JNILIBS/$abi/libnfqws.so"
        chmod 0755 "$JNILIBS/$abi/libnfqws.so"
        echo "    $src_dir -> jniLibs/$abi/libnfqws.so"
    else
        echo "    !! $src_dir/nfqws missing in the archive" >&2
    fi
}

copy_binary android-arm64  arm64-v8a
copy_binary android-arm    armeabi-v7a
copy_binary android-x86    x86
copy_binary android-x86_64 x86_64

echo "==> done"
