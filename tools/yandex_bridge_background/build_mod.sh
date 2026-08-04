#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 INPUT_APK OUTPUT_APK [WORK_DIR]" >&2
    exit 2
fi

input_apk=$1
output_apk=$2
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
expected_input_sha=0b6e336aaeb0211cd011de28ba66567e9c1e879b5aa6fb449ebf11fef14a71fd
expected_cert_sha=72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7

if [ ! -f "$input_apk" ]; then
    echo "input APK not found: $input_apk" >&2
    exit 2
fi
input_sha=$(shasum -a 256 "$input_apk" | awk '{print $1}')
if [ "$input_sha" != "$expected_input_sha" ]; then
    echo "refusing unexpected input APK SHA-256: $input_sha" >&2
    exit 3
fi

if [ "$#" -eq 3 ]; then
    work_dir=$3
    mkdir -p "$work_dir"
    cleanup_work=false
else
    work_dir=$(mktemp -d "${TMPDIR:-/tmp}/kia-yandex-background.XXXXXX")
    cleanup_work=true
fi

cleanup() {
    if [ "$cleanup_work" = true ]; then
        case "$work_dir" in
            */kia-yandex-background.*)
                if [ -d "$work_dir" ] && [ ! -L "$work_dir" ]; then
                    rm -rf -- "$work_dir"
                fi
                ;;
            *)
                echo "refusing unexpected temporary directory: $work_dir" >&2
                ;;
        esac
    fi
}
trap cleanup EXIT INT TERM

java_home=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
export JAVA_HOME=$java_home
build_tools=$(find "$android_sdk/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -1)
decode_dir="$work_dir/decode"
unsigned_apk="$work_dir/yandex-unsigned.apk"
aligned_apk="$work_dir/yandex-aligned.apk"

"$java_home/bin/java" -jar "$repo_dir/tools/apktool_2.9.3.jar" \
    d --no-res --force -o "$decode_dir" "$input_apk"
patch -l -V none -p1 -d "$decode_dir" < "$script_dir/YandexBackgroundLocation.patch"
"$java_home/bin/java" -jar "$repo_dir/tools/apktool_2.9.3.jar" \
    b "$decode_dir" -o "$unsigned_apk"
"$build_tools/zipalign" -f -p 4 "$unsigned_apk" "$aligned_apk"
"$build_tools/apksigner" sign \
    --ks "$repo_dir/signing/kia-debug-release.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$output_apk" "$aligned_apk"
"$build_tools/zipalign" -c -p 4 "$output_apk"
verification=$("$build_tools/apksigner" verify --verbose --print-certs "$output_apk" 2>&1)
printf '%s\n' "$verification"
case "$verification" in
    *"certificate SHA-256 digest: $expected_cert_sha"*) ;;
    *)
        echo "output certificate does not match the public KIA update key" >&2
        exit 4
        ;;
esac
badging=$("$build_tools/aapt" dump badging "$output_apk" | sed -n '1p')
case "$badging" in
    *"name='ru.yandex.yandexnavi'"*"versionCode='71011062'"*) ;;
    *)
        echo "output package/version identity changed: $badging" >&2
        exit 5
        ;;
esac

printf 'output_sha256=%s\n' "$(shasum -a 256 "$output_apk" | awk '{print $1}')"
