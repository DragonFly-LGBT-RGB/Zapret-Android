APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := arm64-v8a armeabi-v7a x86_64 x86
APP_CFLAGS := -O3 -DPKGNAME=ru/dragonfly/zapret/core
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
APP_LDFLAGS := -Wl,-z,max-page-size=16384
