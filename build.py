#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build.py — сборка APK приложения ZapretAndroid одной командой.

Скрипт делает всё сам:
  1. находит или скачивает JDK 17 (Temurin);
  2. находит или скачивает Android SDK (cmdline-tools), ставит platform 35,
     build-tools, NDK и CMake, принимает лицензии;
  3. скачивает нативные компоненты: byedpi, hev-socks5-tunnel и готовые
     бинарники nfqws из релиза bol-van/zapret;
  4. запускает Gradle и кладёт готовый APK в папку out/.

Примеры:
    python3 build.py                  # debug APK
    python3 build.py --release        # release APK (подписан debug-ключом)
    python3 build.py --clean          # пересобрать с нуля
    python3 build.py --skip-native    # не перекачивать исходники движков
    python3 build.py --sdk-dir ~/Android/Sdk

Требуется: Python 3.8+, git, интернет. Windows/Linux/macOS.
"""

from __future__ import annotations

import argparse
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------- версии

JDK_VERSION = "17"
ANDROID_PLATFORM = "android-35"
BUILD_TOOLS = "35.0.0"
NDK_VERSION = "27.0.12077973"
CMAKE_VERSION = "3.22.1"
CMDLINE_TOOLS_BUILD = "11076708"

BYEDPI_REPO = "https://github.com/hufrea/byedpi.git"
BYEDPI_COMMIT = "ba532298de7b28cfe854aea83d061369d13ca290"
HEV_REPO = "https://github.com/heiher/hev-socks5-tunnel.git"
HEV_COMMIT = "4d6c334dbfb68a79d1970c2744e62d09f71df12f"
ZAPRET_VERSION = "v72.13"

# каталог релиза zapret -> ABI в APK
NFQWS_ABIS = {
    "android-arm64": "arm64-v8a",
    "android-arm": "armeabi-v7a",
    "android-x86": "x86",
    "android-x86_64": "x86_64",
}

ROOT = Path(__file__).resolve().parent
TOOLS_DIR = ROOT / ".build-tools"
IS_WINDOWS = os.name == "nt"

# --------------------------------------------------------------------------- вывод

class Out:
    quiet = False

    COLORS = sys.stdout.isatty() and not IS_WINDOWS

    @staticmethod
    def _c(code: str, text: str) -> str:
        return f"\033[{code}m{text}\033[0m" if Out.COLORS else text

    @staticmethod
    def step(text: str) -> None:
        print(Out._c("1;36", f"\n==> {text}"), flush=True)

    @staticmethod
    def info(text: str) -> None:
        if not Out.quiet:
            print(f"    {text}", flush=True)

    @staticmethod
    def warn(text: str) -> None:
        print(Out._c("1;33", f"    ! {text}"), flush=True)

    @staticmethod
    def error(text: str) -> None:
        print(Out._c("1;31", f"    ошибка: {text}"), file=sys.stderr, flush=True)

    @staticmethod
    def ok(text: str) -> None:
        print(Out._c("1;32", f"    {text}"), flush=True)


class BuildError(Exception):
    pass


# --------------------------------------------------------------------------- утилиты

def host_os() -> str:
    system = platform.system().lower()
    if system.startswith("darwin"):
        return "mac"
    if system.startswith("win"):
        return "windows"
    return "linux"


def host_arch() -> str:
    machine = platform.machine().lower()
    if machine in ("arm64", "aarch64"):
        return "aarch64"
    if machine in ("x86_64", "amd64"):
        return "x64"
    raise BuildError(f"неподдерживаемая архитектура: {machine}")


def run(cmd, cwd: Path | None = None, env: dict | None = None,
        stdin_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess:
    printable = " ".join(str(part) for part in cmd)
    Out.info(f"$ {printable}")
    full_env = os.environ.copy()
    if env:
        full_env.update(env)
    result = subprocess.run(
        [str(part) for part in cmd],
        cwd=str(cwd) if cwd else None,
        env=full_env,
        input=stdin_text,
        text=True,
        capture_output=Out.quiet,
    )
    if check and result.returncode != 0:
        if Out.quiet and result.stdout:
            print(result.stdout)
        if Out.quiet and result.stderr:
            print(result.stderr, file=sys.stderr)
        raise BuildError(f"команда завершилась с кодом {result.returncode}: {printable}")
    return result


def which(name: str) -> str | None:
    return shutil.which(name)


def download(url: str, target: Path, description: str = "") -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 0:
        Out.info(f"уже скачано: {target.name}")
        return target

    Out.info(f"скачиваю {description or url}")
    tmp = target.with_suffix(target.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "ZapretAndroid-build"})
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=60) as response, open(tmp, "wb") as output:
            total = int(response.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = response.read(1 << 16)
                if not chunk:
                    break
                output.write(chunk)
                done += len(chunk)
                if total and not Out.quiet and sys.stdout.isatty():
                    percent = done * 100 // total
                    sys.stdout.write(f"\r      {percent:3d}%  {done >> 20} МБ / {total >> 20} МБ")
                    sys.stdout.flush()
    except urllib.error.URLError as error:
        tmp.unlink(missing_ok=True)
        raise BuildError(f"не удалось скачать {url}: {error}") from error
    if sys.stdout.isatty() and not Out.quiet:
        sys.stdout.write("\r" + " " * 48 + "\r")
    tmp.replace(target)
    Out.info(f"готово за {time.time() - started:.0f} с: {target.name}")
    return target


def unzip(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(destination)
        # zipfile теряет права доступа
        for info in zf.infolist():
            mode = info.external_attr >> 16
            if mode & stat.S_IXUSR:
                path = destination / info.filename
                if path.exists():
                    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def make_executable(path: Path) -> None:
    if path.exists() and not IS_WINDOWS:
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


# --------------------------------------------------------------------------- JDK

def java_major_version(java_binary: Path | str) -> int | None:
    try:
        result = subprocess.run([str(java_binary), "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
    if not match:
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2):
        major = int(match.group(2))
    return major


def ensure_jdk(allow_download: bool) -> Path:
    Out.step("JDK 17+")

    candidates: list[Path] = []
    for variable in ("JAVA_HOME", "JDK_HOME"):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value))
    local = TOOLS_DIR / "jdk"
    if local.exists():
        candidates.append(local)

    for home in candidates:
        binary = home / "bin" / ("java.exe" if IS_WINDOWS else "java")
        version = java_major_version(binary) if binary.exists() else None
        if version and version >= 17:
            Out.ok(f"использую JDK {version}: {home}")
            return home

    system_java = which("java")
    if system_java:
        version = java_major_version(system_java)
        if version and version >= 17:
            home = Path(system_java).resolve().parent.parent
            Out.ok(f"использую системный JDK {version}: {home}")
            return home
        Out.warn(f"системная Java слишком старая (версия {version}), нужна 17+")

    if not allow_download:
        raise BuildError("JDK 17 не найден, а скачивание отключено (--no-download-jdk)")

    Out.info("скачиваю Temurin JDK 17")
    url = (
        f"https://api.adoptium.net/v3/binary/latest/{JDK_VERSION}/ga/"
        f"{host_os()}/{host_arch()}/jdk/hotspot/normal/eclipse?project=jdk"
    )
    archive = TOOLS_DIR / ("jdk.zip" if host_os() in ("windows", "mac") else "jdk.tar.gz")
    download(url, archive, "JDK 17 (~180 МБ)")

    extract_dir = TOOLS_DIR / "jdk-extract"
    if extract_dir.exists():
        shutil.rmtree(extract_dir)
    extract_dir.mkdir(parents=True)

    if archive.suffix == ".zip":
        unzip(archive, extract_dir)
    else:
        import tarfile
        with tarfile.open(archive) as tar:
            tar.extractall(extract_dir)

    roots = [p for p in extract_dir.iterdir() if p.is_dir()]
    if not roots:
        raise BuildError("архив JDK оказался пустым")
    jdk_root = roots[0]
    # на macOS структура Contents/Home
    if (jdk_root / "Contents" / "Home").exists():
        jdk_root = jdk_root / "Contents" / "Home"

    if local.exists():
        shutil.rmtree(local)
    shutil.move(str(jdk_root), str(local))
    shutil.rmtree(extract_dir, ignore_errors=True)

    version = java_major_version(local / "bin" / ("java.exe" if IS_WINDOWS else "java"))
    if not version or version < 17:
        raise BuildError("скачанный JDK не работает")
    Out.ok(f"JDK {version} установлен в {local}")
    return local


# --------------------------------------------------------------------------- Android SDK

def sdk_tool(sdk: Path, name: str) -> Path:
    suffix = ".bat" if IS_WINDOWS else ""
    return sdk / "cmdline-tools" / "latest" / "bin" / f"{name}{suffix}"


def ensure_android_sdk(sdk_dir: Path | None, jdk: Path) -> Path:
    Out.step("Android SDK")

    sdk = sdk_dir
    if sdk is None:
        for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
            value = os.environ.get(variable)
            if value and Path(value).exists():
                sdk = Path(value)
                break
    if sdk is None:
        default = Path.home() / ("AppData/Local/Android/Sdk" if IS_WINDOWS else
                                 "Library/Android/sdk" if host_os() == "mac" else "Android/Sdk")
        sdk = default if default.exists() else TOOLS_DIR / "android-sdk"

    sdk = sdk.expanduser().resolve()
    sdk.mkdir(parents=True, exist_ok=True)
    Out.info(f"каталог SDK: {sdk}")

    manager = sdk_tool(sdk, "sdkmanager")
    if not manager.exists():
        Out.info("cmdline-tools не найдены, скачиваю")
        name = {"linux": "linux", "mac": "mac", "windows": "win"}[host_os()]
        url = f"https://dl.google.com/android/repository/commandlinetools-{name}-{CMDLINE_TOOLS_BUILD}_latest.zip"
        archive = TOOLS_DIR / f"cmdline-tools-{name}.zip"
        download(url, archive, "Android command line tools (~120 МБ)")

        staging = TOOLS_DIR / "cmdline-staging"
        if staging.exists():
            shutil.rmtree(staging)
        unzip(archive, staging)

        target = sdk / "cmdline-tools" / "latest"
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            shutil.rmtree(target)
        shutil.move(str(staging / "cmdline-tools"), str(target))
        shutil.rmtree(staging, ignore_errors=True)
        manager = sdk_tool(sdk, "sdkmanager")
        make_executable(manager)

    if not manager.exists():
        raise BuildError(f"sdkmanager не найден: {manager}")
    make_executable(manager)

    env = {"JAVA_HOME": str(jdk), "ANDROID_SDK_ROOT": str(sdk), "ANDROID_HOME": str(sdk)}

    packages = [
        "platform-tools",
        f"platforms;{ANDROID_PLATFORM}",
        f"build-tools;{BUILD_TOOLS}",
        f"ndk;{NDK_VERSION}",
        f"cmake;{CMAKE_VERSION}",
    ]
    missing = [p for p in packages if not sdk_package_installed(sdk, p)]
    if missing:
        Out.info("принимаю лицензии Android SDK")
        run([manager, f"--sdk_root={sdk}", "--licenses"], env=env, stdin_text="y\n" * 60, check=False)
        Out.info("устанавливаю: " + ", ".join(missing))
        run([manager, f"--sdk_root={sdk}", *missing], env=env, stdin_text="y\n" * 20)
    else:
        Out.info("все компоненты уже установлены")

    Out.ok(f"SDK готов: {sdk}")
    return sdk


def sdk_package_installed(sdk: Path, package: str) -> bool:
    relative = package.replace(";", os.sep)
    path = sdk / relative
    if package.startswith("cmake;"):
        return (sdk / "cmake" / package.split(";")[1]).exists()
    return path.exists()


def write_local_properties(sdk: Path) -> None:
    content = "sdk.dir=" + str(sdk).replace("\\", "\\\\") + "\n"
    (ROOT / "local.properties").write_text(content, encoding="utf-8")
    Out.info("local.properties обновлён")


# --------------------------------------------------------------------------- нативные компоненты

SOURCE_SUFFIXES = {".h", ".hh", ".hpp", ".hxx", ".inc", ".c", ".cc", ".cpp", ".cxx", ".m", ".mm"}


def materialize_symlinks(directory: Path) -> int:
    """Чинит симлинки после клонирования на Windows.

    Git на Windows без прав на создание симлинков кладёт вместо них обычные
    текстовые файлы, внутри которых записан путь до цели. Компилятор такой
    «заголовок» прочитать не может и падает с `expected identifier or '('`.
    Заменяем такие файлы на #include-заглушки (для исходников) или на копии.
    """
    git = which("git")
    if not git:
        return 0
    try:
        listing = subprocess.run(
            [git, "ls-files", "-s", "--recurse-submodules"],
            cwd=str(directory), text=True, capture_output=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, OSError):
        return 0

    fixed = 0
    for line in listing.splitlines():
        if not line.startswith("120000"):
            continue
        _, _, relative = line.partition("\t")
        path = directory / relative.strip()
        if path.is_symlink() or not path.is_file():
            continue
        try:
            target_text = path.read_text(encoding="utf-8").strip()
        except (UnicodeDecodeError, OSError):
            continue
        if not target_text or "\n" in target_text or len(target_text) > 1024:
            continue
        target = (path.parent / target_text).resolve()
        if not target.is_file():
            continue
        try:
            if path.suffix.lower() in SOURCE_SUFFIXES:
                # именно #include, а не копия: внутри цели пути к соседним
                # файлам считаются относительно её собственного каталога
                path.write_text('#include "%s"\n' % target_text.replace("\\", "/"),
                                encoding="utf-8")
            else:
                shutil.copy2(target, path)
        except OSError:
            continue
        fixed += 1
    return fixed


def git_checkout(repo: str, directory: Path, commit: str, recursive: bool = False) -> None:
    git = which("git")
    if not git:
        raise BuildError("не найден git — установите его и повторите")

    if not (directory / ".git").exists():
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
        run([git, "init", "-q"], cwd=directory)
        run([git, "remote", "add", "origin", repo], cwd=directory)

    run([git, "fetch", "-q", "--depth", "1", "origin", commit], cwd=directory)
    run([git, "checkout", "-q", "FETCH_HEAD"], cwd=directory)
    if recursive:
        run([git, "submodule", "update", "--init", "--recursive", "--depth", "1"], cwd=directory)

    fixed = materialize_symlinks(directory)
    if fixed:
        Out.info(f"восстановлено симлинков (особенность Windows): {fixed}")


def fetch_nfqws(version: str, jni_libs: Path, local_zip: Path | None) -> bool:
    """Кладёт бинарники nfqws в jniLibs. Возвращает False, если не получилось."""
    if local_zip:
        archive = local_zip
        if not archive.exists():
            raise BuildError(f"файл не найден: {archive}")
    else:
        url = f"https://github.com/bol-van/zapret/releases/download/{version}/zapret-{version}.zip"
        archive = TOOLS_DIR / f"zapret-{version}.zip"
        try:
            download(url, archive, f"zapret {version} (~20 МБ)")
        except BuildError as error:
            Out.warn(str(error))
            return False

    with tempfile.TemporaryDirectory() as temporary:
        temp = Path(temporary)
        try:
            unzip(archive, temp)
        except zipfile.BadZipFile:
            archive.unlink(missing_ok=True)
            Out.warn("архив zapret повреждён, скачайте заново")
            return False

        binaries = next((p for p in temp.rglob("binaries") if p.is_dir()), None)
        if binaries is None:
            Out.warn("в архиве zapret нет каталога binaries")
            return False

        copied = 0
        for source_name, abi in NFQWS_ABIS.items():
            source = binaries / source_name / "nfqws"
            if not source.exists():
                Out.warn(f"нет бинарника для {source_name}")
                continue
            target_dir = jni_libs / abi
            target_dir.mkdir(parents=True, exist_ok=True)
            target = target_dir / "libnfqws.so"
            shutil.copy2(source, target)
            make_executable(target)
            copied += 1
            Out.info(f"{source_name} -> jniLibs/{abi}/libnfqws.so")
        return copied > 0


def fetch_native_sources(zapret_version: str, local_zip: Path | None) -> None:
    Out.step("Нативные компоненты (byedpi, hev-socks5-tunnel, nfqws)")

    cpp = ROOT / "app" / "src" / "main" / "cpp" / "byedpi"
    jni = ROOT / "app" / "src" / "main" / "jni" / "hev-socks5-tunnel"
    jni_libs = ROOT / "app" / "src" / "main" / "jniLibs"

    Out.info("byedpi")
    git_checkout(BYEDPI_REPO, cpp, BYEDPI_COMMIT)
    Out.info("hev-socks5-tunnel")
    git_checkout(HEV_REPO, jni, HEV_COMMIT, recursive=True)

    Out.info("nfqws")
    if fetch_nfqws(zapret_version, jni_libs, local_zip):
        Out.ok("nfqws добавлен — root-режим будет доступен")
    else:
        Out.warn(
            "nfqws не добавлен: APK соберётся, но режим root работать не будет. "
            "Скачайте zapret-<версия>.zip вручную и передайте --nfqws-zip путь/к/zapret.zip"
        )


# --------------------------------------------------------------------------- Gradle

NETWORK_MARKERS = (
    "Could not resolve", "Could not GET", "Could not HEAD", "Could not download",
    "Could not get resource", "socket exception", "getsockopt", "Connection reset",
    "Read timed out", "Connect timed out", "Network is unreachable",
    "Connection refused", "Remote host terminated the handshake", "UnknownHostException",
)


def run_streaming(cmd, cwd: Path, env: dict) -> tuple[int, str]:
    """Запускает команду, показывая вывод и одновременно запоминая его."""
    printable = " ".join(str(part) for part in cmd)
    Out.info(f"$ {printable}")
    full_env = os.environ.copy()
    full_env.update(env)
    process = subprocess.Popen(
        [str(part) for part in cmd], cwd=str(cwd), env=full_env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, errors="replace", bufsize=1,
    )
    collected: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        collected.append(line)
        if not Out.quiet:
            sys.stdout.write(line)
            sys.stdout.flush()
    process.wait()
    output = "".join(collected)
    if process.returncode != 0 and Out.quiet:
        print(output)
    return process.returncode, output


def proxy_gradle_args() -> list[str]:
    """Пробрасывает системный прокси из переменных окружения в Gradle."""
    arguments: list[str] = []
    for scheme in ("http", "https"):
        value = os.environ.get(f"{scheme.upper()}_PROXY") or os.environ.get(f"{scheme}_proxy")
        if not value:
            continue
        try:
            parsed = urllib.parse.urlparse(value if "//" in value else f"//{value}")
        except ValueError:
            continue
        if not parsed.hostname:
            continue
        arguments.append(f"-D{scheme}.proxyHost={parsed.hostname}")
        if parsed.port:
            arguments.append(f"-D{scheme}.proxyPort={parsed.port}")
    return arguments


def gradle_build(jdk: Path, sdk: Path, release: bool, clean: bool, extra_args: list[str],
                 retries: int = 3) -> Path:
    Out.step("Сборка Gradle")

    wrapper = ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")
    if not wrapper.exists():
        raise BuildError(f"не найден {wrapper.name}")
    make_executable(wrapper)

    env = {
        "JAVA_HOME": str(jdk),
        "ANDROID_SDK_ROOT": str(sdk),
        "ANDROID_HOME": str(sdk),
        "GRADLE_OPTS": os.environ.get("GRADLE_OPTS", "-Dorg.gradle.jvmargs=-Xmx3g"),
    }

    command = [wrapper if IS_WINDOWS else f"./{wrapper.name}", "--no-daemon"]
    command += proxy_gradle_args()
    if clean:
        command.append("clean")
    command.append("assembleRelease" if release else "assembleDebug")
    command += extra_args

    attempts = max(1, retries)
    for attempt in range(1, attempts + 1):
        code, output = run_streaming(command, cwd=ROOT, env=env)
        if code == 0:
            break
        network_problem = any(marker in output for marker in NETWORK_MARKERS)
        if network_problem and attempt < attempts:
            Out.warn(
                f"сбой загрузки зависимостей (попытка {attempt} из {attempts}); "
                "повтор через 15 секунд — скачанное уже лежит в кэше Gradle"
            )
            time.sleep(15)
            continue
        if network_problem:
            Out.warn(
                "Gradle не смог скачать зависимости. Если в логе есть "
                "'Permission denied: getsockopt' или 'socket exception' — сеть блокируют "
                "фаервол/антивирус именно для java.exe. Разрешите java.exe из папки JDK "
                "исходящие соединения (или временно отключите антивирус) и запустите снова."
            )
        raise BuildError(f"Gradle завершился с кодом {code}")

    variant = "release" if release else "debug"
    candidates = sorted((ROOT / "app" / "build" / "outputs" / "apk" / variant).glob("*.apk"))
    if not candidates:
        raise BuildError("Gradle отработал, но APK не найден")

    out_dir = ROOT / "out"
    out_dir.mkdir(exist_ok=True)
    target = out_dir / f"ZapretAndroid-{variant}.apk"
    shutil.copy2(candidates[0], target)
    return target


# --------------------------------------------------------------------------- main

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Сборка APK ZapretAndroid",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Пример: python3 build.py --release",
    )
    parser.add_argument("--release", action="store_true", help="собрать release вместо debug")
    parser.add_argument("--clean", action="store_true", help="выполнить clean перед сборкой")
    parser.add_argument("--skip-native", action="store_true",
                        help="не скачивать byedpi/hev/nfqws (если уже скачаны)")
    parser.add_argument("--skip-sdk", action="store_true",
                        help="не проверять и не доустанавливать Android SDK")
    parser.add_argument("--no-download-jdk", action="store_true",
                        help="не скачивать JDK, использовать только системный")
    parser.add_argument("--sdk-dir", type=Path, default=None, help="путь к Android SDK")
    parser.add_argument("--nfqws-zip", type=Path, default=None,
                        help="локальный zapret-<версия>.zip вместо скачивания")
    parser.add_argument("--zapret-version", default=ZAPRET_VERSION, help=f"версия zapret (по умолчанию {ZAPRET_VERSION})")
    parser.add_argument("--install", action="store_true", help="установить APK на подключённое устройство (adb)")
    parser.add_argument("--gradle-retries", type=int, default=3,
                        help="сколько раз повторять Gradle при сетевых сбоях (по умолчанию 3)")
    parser.add_argument("-q", "--quiet", action="store_true", help="меньше вывода")
    parser.add_argument("gradle_args", nargs="*", help="дополнительные аргументы Gradle")
    return parser.parse_args()


def install_apk(sdk: Path, apk: Path) -> None:
    Out.step("Установка на устройство")
    adb = sdk / "platform-tools" / ("adb.exe" if IS_WINDOWS else "adb")
    if not adb.exists():
        adb_path = which("adb")
        if not adb_path:
            Out.warn("adb не найден, пропускаю установку")
            return
        adb = Path(adb_path)
    run([adb, "install", "-r", apk], check=False)


def main() -> int:
    args = parse_args()
    Out.quiet = args.quiet
    started = time.time()

    print("ZapretAndroid — сборка APK")
    print(f"  проект: {ROOT}")
    print(f"  система: {platform.system()} {platform.machine()}, Python {platform.python_version()}")

    if sys.version_info < (3, 8):
        Out.error("нужен Python 3.8 или новее")
        return 1

    TOOLS_DIR.mkdir(exist_ok=True)

    try:
        jdk = ensure_jdk(allow_download=not args.no_download_jdk)

        if args.skip_sdk:
            sdk = args.sdk_dir or Path(os.environ.get("ANDROID_SDK_ROOT")
                                       or os.environ.get("ANDROID_HOME") or (TOOLS_DIR / "android-sdk"))
            sdk = sdk.expanduser().resolve()
            Out.step("Android SDK")
            Out.info(f"проверка пропущена, использую {sdk}")
        else:
            sdk = ensure_android_sdk(args.sdk_dir, jdk)

        write_local_properties(sdk)

        if args.skip_native:
            Out.step("Нативные компоненты")
            Out.info("пропущено (--skip-native)")
        else:
            fetch_native_sources(args.zapret_version, args.nfqws_zip)

        apk = gradle_build(jdk, sdk, args.release, args.clean, args.gradle_args,
                           retries=args.gradle_retries)

        size_mb = apk.stat().st_size / (1024 * 1024)
        Out.step("Готово")
        Out.ok(f"APK: {apk}  ({size_mb:.1f} МБ, за {time.time() - started:.0f} с)")

        if args.install:
            install_apk(sdk, apk)
        else:
            print("\n  Установка: adb install -r " + str(apk))
        return 0

    except BuildError as error:
        Out.error(str(error))
        print("\nЧастые причины:\n"
              "  • нет интернета или заблокирован dl.google.com — скачайте SDK вручную и укажите --sdk-dir\n"
              "  • не установлен git — нужен для byedpi и hev-socks5-tunnel\n"
              "  • мало места на диске (нужно ~6 ГБ под SDK и NDK)\n"
              "Повторный запуск продолжит с того же места — всё скачанное кэшируется в .build-tools/")
        return 1
    except KeyboardInterrupt:
        Out.error("прервано пользователем")
        return 130


if __name__ == "__main__":
    sys.exit(main())
