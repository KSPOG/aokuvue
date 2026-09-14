from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from tkinter import BOTH, BOTTOM, END, HORIZONTAL, LEFT, RIGHT, X, filedialog, messagebox
import tkinter as tk
from tkinter import ttk

from PIL import Image, ImageTk


APP_NAME = "Aokuvue"
INSTALLER_VERSION = "1.1.1"
SOURCE_BRANCH = "main"
REPOSITORY = "KSPOG/aokuvue"
GITHUB_API = f"https://api.github.com/repos/{REPOSITORY}"
ADOPTIUM_API = (
    "https://api.adoptium.net/v3/assets/latest/21/hotspot"
    "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
)
USER_AGENT = f"AokuvueInstaller/{INSTALLER_VERSION}"

BG = "#07070D"
PANEL = "#090912"
PANEL_ALT = "#11111B"
BORDER = "#46376D"
VIOLET = "#8E7CFF"
VIOLET_HOVER = "#AA9FFF"
TEXT = "#F1EDFA"
MUTED = "#AAA3BD"
ERROR = "#FF8D9B"
SUCCESS = "#9ED8B3"


def local_app_data() -> Path:
    value = os.environ.get("LOCALAPPDATA", "").strip()
    if value:
        return Path(value)
    return Path.home() / "AppData" / "Local"


def canonical_state_file() -> Path:
    return local_app_data() / APP_NAME / "installer" / "state.json"


def legacy_state_files() -> list[Path]:
    if SOURCE_BRANCH != "main":
        return []
    root = local_app_data()
    return [
        root / "AOKUVUE" / "installer" / "state.json",
        root / "AOKVUE" / "installer" / "state.json",
    ]


def default_install_dir() -> Path:
    return local_app_data() / "Programs" / APP_NAME


def installer_cache_dir() -> Path:
    return local_app_data() / APP_NAME / "installer-cache"


def gradle_cache_dir() -> Path:
    return local_app_data() / APP_NAME / "build-tools" / "gradle"


def gradle_user_home_dir() -> Path:
    return local_app_data() / APP_NAME / "gradle-home"


def resource_path(name: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    bundled = base / "assets" / name
    if bundled.exists():
        return bundled
    repo_asset = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "images" / name
    return repo_asset


def read_json(path: Path) -> dict:
    try:
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
    except (OSError, ValueError, TypeError):
        pass
    return {}


def load_state() -> dict:
    canonical = canonical_state_file()
    data = read_json(canonical)
    if data:
        return data
    for candidate in legacy_state_files():
        data = read_json(candidate)
        if data:
            # Migrate any usable legacy preference to the canonical Aokuvue location.
            save_state(data)
            return data
    return {}


def save_state(state: dict) -> None:
    path = canonical_state_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = dict(state)
    payload["installerVersion"] = INSTALLER_VERSION
    payload["savedAtUnix"] = int(time.time())
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def preferred_install_dir(explicit: str | None, state: dict | None = None) -> Path:
    if explicit and explicit.strip():
        return Path(explicit.strip()).expanduser().absolute()
    values = state if state is not None else load_state()
    remembered = str(values.get("lastInstallPath", "")).strip()
    if remembered:
        return Path(remembered).expanduser().absolute()
    return default_install_dir().absolute()


def remember_install_dir(path: Path) -> None:
    state = load_state()
    state["lastInstallPath"] = str(path.expanduser().absolute())
    save_state(state)


def request_bytes(url: str, timeout: int = 90, accept: str = "*/*") -> bytes:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": accept},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def request_json(url: str, timeout: int = 45):
    return json.loads(request_bytes(url, timeout, "application/vnd.github+json").decode("utf-8"))


def download_file(
    url: str,
    destination: Path,
    progress=None,
    expected_sha256: str = "",
    accept: str = "*/*",
    user_agent: str = "",
) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": user_agent or USER_AGENT,
            "Accept": accept,
            "Accept-Encoding": "identity",
        },
    )
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as output:
            total = int(response.headers.get("Content-Length", "0") or 0)
            received = 0
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                output.write(block)
                digest.update(block)
                received += len(block)
                if progress:
                    progress(received, total)
        actual = digest.hexdigest()
        if expected_sha256 and actual.lower() != expected_sha256.lower():
            partial.unlink(missing_ok=True)
            raise RuntimeError(
                f"SHA-256 verification failed for {destination.name}: expected {expected_sha256}, got {actual}"
            )
        os.replace(partial, destination)
        return actual
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def browser_download_user_agent() -> str:
    return (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        f"Chrome/140.0.0.0 Safari/537.36 {USER_AGENT}"
    )


def download_file_with_curl(url: str, destination: Path) -> None:
    curl = shutil.which("curl.exe") or shutil.which("curl")
    if not curl:
        raise RuntimeError("Windows curl.exe is unavailable")

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    partial.unlink(missing_ok=True)
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result = subprocess.run(
            [
                curl,
                "--fail",
                "--location",
                "--silent",
                "--show-error",
                "--retry", "2",
                "--retry-all-errors",
                "--connect-timeout", "30",
                "--max-time", "300",
                "--user-agent", browser_download_user_agent(),
                "--header", "Accept: */*",
                "--output", str(partial),
                url,
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=330,
            creationflags=creationflags,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or f"exit code {result.returncode}").strip()
            raise RuntimeError(f"curl download failed: {detail}")
        os.replace(partial, destination)
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_build_version(text: str) -> str:
    match = re.search(r"(?m)^\s*version\s*=\s*['\"]([^'\"]+)['\"]\s*$", text or "")
    if not match:
        raise RuntimeError("Unable to determine the Aokuvue application version from build.gradle")
    return match.group(1).strip()


def parse_version_text(value: str) -> tuple[int, ...]:
    parts = [int(item) for item in re.findall(r"\d+", (value or "").lstrip("vV"))[:4]]
    return tuple(parts or [0])


def latest_source_commit() -> str:
    data = request_json(f"{GITHUB_API}/commits/{SOURCE_BRANCH}")
    sha = str(data.get("sha", "")).strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
        raise RuntimeError(f"GitHub did not return a valid {APP_NAME} {SOURCE_BRANCH}-branch commit")
    return sha.lower()


def latest_app_version(commit: str) -> str:
    text = request_bytes(
        f"https://raw.githubusercontent.com/{REPOSITORY}/{commit}/build.gradle",
        45,
        "text/plain",
    ).decode("utf-8", errors="replace")
    return parse_build_version(text)


def source_archive_urls(commit: str) -> list[str]:
    return [
        f"https://codeload.github.com/{REPOSITORY}/zip/{commit}",
        f"{GITHUB_API}/zipball/{commit}",
        f"https://github.com/{REPOSITORY}/archive/{commit}.zip",
    ]


def download_source_with_git(commit: str, work_dir: Path, log) -> Path:
    git = shutil.which("git.exe") or shutil.which("git")
    if not git:
        raise RuntimeError("Git is not installed or is unavailable on PATH")

    source_root = work_dir / "source"
    project = source_root / f"aokuvue-{commit}"
    source_root.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(project, ignore_errors=True)
    project.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    repository_url = f"https://github.com/{REPOSITORY}.git"
    try:
        run_process([git, "init", "--quiet"], project, env, log)
        run_process([git, "remote", "add", "origin", repository_url], project, env, log)
        run_process(
            [
                git,
                "-c", "http.version=HTTP/1.1",
                "fetch",
                "--depth", "1",
                "--no-tags",
                "origin",
                commit,
            ],
            project,
            env,
            log,
        )
        run_process([git, "checkout", "--quiet", "--detach", "FETCH_HEAD"], project, env, log)
        if not (project / "build.gradle").is_file():
            raise RuntimeError("Git checkout completed but build.gradle was not found")
        return project
    except Exception:
        shutil.rmtree(project, ignore_errors=True)
        raise


def download_source(commit: str, work_dir: Path, log, set_progress) -> Path:
    log(f"Fetching Aokuvue source at {commit[:12]} with Git…")
    try:
        project = download_source_with_git(commit, work_dir, log)
        set_progress(32)
        return project
    except Exception as git_error:
        log(f"Git source fetch failed ({git_error}); trying archive downloads…")

    archive = work_dir / f"aokuvue-{commit}.zip"
    log(f"Downloading Aokuvue source at {commit[:12]}…")

    def report(received: int, total: int):
        if total > 0:
            set_progress(min(32.0, 6.0 + (received / total) * 26.0))

    errors: list[str] = []
    mirrors = source_archive_urls(commit)
    for index, url in enumerate(mirrors, start=1):
        try:
            log(f"Source mirror {index}/{len(mirrors)}: {urllib.parse.urlsplit(url).netloc}")
            try:
                download_file(
                    url,
                    archive,
                    report,
                    accept="*/*",
                    user_agent=browser_download_user_agent(),
                )
            except Exception as python_error:
                log(f"Python downloader failed ({python_error}); retrying with Windows curl…")
                download_file_with_curl(url, archive)
                set_progress(32)
            if not zipfile.is_zipfile(archive):
                raise RuntimeError("The downloaded response is not a valid ZIP archive")
            break
        except Exception as error:
            try:
                archive.unlink(missing_ok=True)
            except OSError as cleanup_error:
                log(f"Could not remove failed archive: {cleanup_error}")
            detail = f"{urllib.parse.urlsplit(url).netloc}: {error}"
            errors.append(detail)
            log(f"Source mirror {index} failed: {error}")
    else:
        raise RuntimeError("Unable to download the Aokuvue source archive. " + " | ".join(errors))

    source_root = work_dir / "source"
    source_root.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source_zip:
        source_zip.extractall(source_root)
    candidates = [path for path in source_root.iterdir() if path.is_dir()]
    if len(candidates) != 1:
        raise RuntimeError("Downloaded Aokuvue source archive has an unexpected structure")
    project = candidates[0]
    if not (project / "gradlew.bat").is_file() or not (project / "build.gradle").is_file():
        raise RuntimeError("Downloaded Aokuvue source is incomplete")
    return project


@dataclass(frozen=True)
class JdkPackage:
    url: str
    checksum: str
    name: str


def resolve_jdk_package() -> JdkPackage:
    assets = request_json(ADOPTIUM_API)
    if not isinstance(assets, list) or not assets:
        raise RuntimeError("Eclipse Adoptium did not return a Temurin JDK 21 package")
    package = assets[0].get("binary", {}).get("package", {})
    url = str(package.get("link", "")).strip()
    checksum = str(package.get("checksum", "")).strip().lower()
    name = str(package.get("name", "temurin-jdk-21.zip")).strip() or "temurin-jdk-21.zip"
    if not url or not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise RuntimeError("Eclipse Adoptium JDK metadata is incomplete")
    return JdkPackage(url=url, checksum=checksum, name=name)


def locate_jdk(root: Path) -> Path | None:
    direct = root / "bin" / "java.exe"
    if direct.is_file() and (root / "bin" / "javac.exe").is_file() and (root / "bin" / "jpackage.exe").is_file():
        return root
    if root.exists():
        for java in root.rglob("java.exe"):
            if java.parent.name.lower() == "bin":
                candidate = java.parent.parent
                if (candidate / "bin" / "javac.exe").is_file() and (candidate / "bin" / "jpackage.exe").is_file():
                    return candidate
    return None


def ensure_jdk(log, set_progress) -> Path:
    cache = installer_cache_dir()
    extracted = cache / "jdk-21"
    found = locate_jdk(extracted)
    if found:
        log(f"Using cached Temurin JDK 21: {found}")
        return found

    package = resolve_jdk_package()
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / package.name
    if not archive.is_file() or sha256_file(archive).lower() != package.checksum:
        log("Downloading verified Eclipse Temurin JDK 21…")

        def report(received: int, total: int):
            if total > 0:
                set_progress(min(50.0, 33.0 + (received / total) * 17.0))

        download_file(package.url, archive, report, package.checksum)
    else:
        log("Using verified cached Temurin JDK 21 archive.")

    shutil.rmtree(extracted, ignore_errors=True)
    extracted.mkdir(parents=True, exist_ok=True)
    log("Extracting Temurin JDK 21…")
    with zipfile.ZipFile(archive) as package_zip:
        package_zip.extractall(extracted)
    found = locate_jdk(extracted)
    if not found:
        raise RuntimeError("Temurin JDK 21 extraction completed but java.exe/javac.exe/jpackage.exe were not found")
    return found


def run_process(command: list[str], cwd: Path, env: dict[str, str], log) -> None:
    printable = " ".join(f'"{value}"' if " " in value else value for value in command)
    log(f"> {printable}")
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    process = subprocess.Popen(
        command,
        cwd=str(cwd),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        creationflags=creationflags,
    )
    assert process.stdout is not None
    for line in process.stdout:
        clean = line.rstrip()
        if clean:
            log(clean)
    code = process.wait()
    if code != 0:
        raise RuntimeError(f"Command failed with exit code {code}: {command[0]}")


def package_application(project: Path, jdk: Path, work_dir: Path, version: str, log, set_progress) -> Path:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    env["AOKUVUE_GRADLE_CACHE"] = str(gradle_cache_dir())
    gradle_home = gradle_user_home_dir()
    gradle_home.mkdir(parents=True, exist_ok=True)
    env["GRADLE_USER_HOME"] = str(gradle_home)
    project_cache = work_dir / "gradle-project-cache"
    project_cache.mkdir(parents=True, exist_ok=True)

    gradle = project / "gradlew.bat"
    log(f"Building and testing {APP_NAME}…")
    set_progress(52)
    run_process(
        [str(gradle), "clean", "test", "installDist", "--no-daemon", "--stacktrace",
         "--project-cache-dir", str(project_cache)],
        project,
        env,
        log,
    )
    set_progress(78)

    install_lib = project / "build" / "install" / "Aokuvue" / "lib"
    main_jar = install_lib / f"Aokuvue-{version}.jar"
    icon = project / "src" / "main" / "resources" / "images" / "aokuvue.ico"
    if not main_jar.is_file():
        raise RuntimeError(f"Expected application JAR was not produced: {main_jar}")
    if not icon.is_file():
        raise RuntimeError("Aokuvue Windows icon was not found in the source build")

    package_dest = work_dir / "package"
    package_dest.mkdir(parents=True, exist_ok=True)
    jpackage = jdk / "bin" / "jpackage.exe"
    log(f"Packaging the {APP_NAME} Windows application…")
    run_process(
        [
            str(jpackage),
            "--type", "app-image",
            "--name", APP_NAME,
            "--app-version", version,
            "--vendor", APP_NAME,
            "--description", "Aokuvue anime and manga desktop application",
            "--input", str(install_lib),
            "--main-jar", main_jar.name,
            "--main-class", "app.kspani.MainLauncher",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dprism.order=d3d,sw",
            "--icon", str(icon),
            "--dest", str(package_dest),
        ],
        project,
        env,
        log,
    )
    result = package_dest / APP_NAME
    if not (result / f"{APP_NAME}.exe").is_file():
        raise RuntimeError(f"jpackage completed but {APP_NAME}.exe was not produced")
    set_progress(88)
    return result


def process_running(pid: int) -> bool:
    if pid <= 0:
        return False
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            capture_output=True,
            text=True,
            timeout=8,
            creationflags=creationflags,
        )
        return str(pid) in result.stdout
    except (OSError, subprocess.SubprocessError):
        return False


def wait_for_pid(pid: int, log, timeout: float = 60.0) -> None:
    if pid <= 0:
        return
    log(f"Waiting for {APP_NAME} process {pid} to close…")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not process_running(pid):
            return
        time.sleep(0.4)
    raise RuntimeError(f"{APP_NAME} did not close in time. Close the application and try the update again.")


def running_aokuvue_pids() -> list[int]:
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result = subprocess.run(
            ["tasklist", "/FI", f"IMAGENAME eq {APP_NAME}.exe", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            timeout=8,
            creationflags=creationflags,
        )
        pids: list[int] = []
        for line in result.stdout.splitlines():
            match = re.search(rf'^"{re.escape(APP_NAME)}\.exe","(\d+)"', line, re.IGNORECASE)
            if match:
                pids.append(int(match.group(1)))
        return pids
    except (OSError, subprocess.SubprocessError):
        return []


def stop_running_aokuvue(log) -> None:
    pids = running_aokuvue_pids()
    if not pids:
        return
    log(f"Closing the running {APP_NAME} application…")
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    for pid in pids:
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T"],
            capture_output=True,
            timeout=10,
            creationflags=creationflags,
        )
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and any(process_running(pid) for pid in pids):
        time.sleep(0.3)
    if any(process_running(pid) for pid in pids):
        raise RuntimeError(f"{APP_NAME} is still running. Close it before installing the update.")


def install_app_image(app_image: Path, destination: Path, log, set_progress) -> None:
    destination = destination.expanduser().absolute()
    parent = destination.parent
    parent.mkdir(parents=True, exist_ok=True)
    staging = parent / f".{APP_NAME}.staging-{uuid.uuid4().hex[:8]}"
    backup = parent / f".{APP_NAME}.previous"
    shutil.rmtree(staging, ignore_errors=True)
    shutil.rmtree(backup, ignore_errors=True)

    log(f"Staging {APP_NAME} in {destination}…")
    shutil.copytree(app_image, staging)
    if not (staging / f"{APP_NAME}.exe").is_file():
        shutil.rmtree(staging, ignore_errors=True)
        raise RuntimeError(f"Staged {APP_NAME} application is incomplete")
    set_progress(94)

    had_existing = destination.exists()
    try:
        if had_existing:
            log(f"Replacing the existing {APP_NAME} installation…")
            destination.replace(backup)
        staging.replace(destination)
        if not (destination / f"{APP_NAME}.exe").is_file():
            raise RuntimeError(f"Installed {APP_NAME}.exe could not be verified")
        shutil.rmtree(backup, ignore_errors=True)
    except Exception:
        if destination.exists():
            shutil.rmtree(destination, ignore_errors=True)
        if backup.exists():
            backup.replace(destination)
        shutil.rmtree(staging, ignore_errors=True)
        raise
    set_progress(100)


def launch_application(install_dir: Path) -> None:
    executable = install_dir.expanduser().absolute() / f"{APP_NAME}.exe"
    if not executable.is_file():
        raise RuntimeError(f"{APP_NAME} is not installed at {install_dir}")
    subprocess.Popen([str(executable)], cwd=str(executable.parent))


class InstallerWindow:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.root = tk.Tk()
        self.root.title(f"{APP_NAME} / Installer")
        self.root.geometry("895x622")
        self.root.minsize(760, 560)
        self.root.configure(bg=BG)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.busy = False
        self.install_dir = tk.StringVar(value=str(preferred_install_dir(args.install_dir)))
        self.status = tk.StringVar(value=f"Ready to install or update {APP_NAME}.")
        self.progress = tk.DoubleVar(value=0)
        self.background_photo = None
        self.logo_photo = None

        icon = resource_path("aokuvue.ico")
        if icon.is_file():
            try:
                self.root.iconbitmap(default=str(icon))
            except tk.TclError:
                pass

        self._configure_styles()
        self._build()
        self.root.after(100, self._pump_events)
        if args.update:
            self.status.set(f"Update mode — preparing to update {APP_NAME}.")
            self.root.after(550, self.start_install)

    def _configure_styles(self):
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure(
            "Aokuvue.Horizontal.TProgressbar",
            troughcolor="#161421",
            background=VIOLET,
            bordercolor="#161421",
            lightcolor=VIOLET,
            darkcolor=VIOLET,
        )

    def _build(self):
        canvas = tk.Canvas(self.root, highlightthickness=0, bg=BG)
        canvas.pack(fill=BOTH, expand=True)
        self.canvas = canvas
        background = resource_path("aokuvue-moonlight.png")
        if background.is_file():
            try:
                image = Image.open(background).convert("RGB")
                image.thumbnail((1400, 900), Image.Resampling.LANCZOS)
                self.background_photo = ImageTk.PhotoImage(image)
                canvas.create_image(0, 0, image=self.background_photo, anchor="nw")
            except Exception:
                pass
        canvas.create_rectangle(0, 0, 1600, 1000, fill="#07070D", stipple="gray50", outline="")

        outer = tk.Frame(canvas, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        window = canvas.create_window(112, 68, window=outer, anchor="nw", width=680, height=535)
        self.panel_window = window

        body = tk.Frame(outer, bg=PANEL)
        body.pack(fill=BOTH, expand=True, padx=25, pady=24)

        logo_path = resource_path("aokuvue-icon.png")
        if logo_path.is_file():
            try:
                logo = Image.open(logo_path).convert("RGBA")
                logo.thumbnail((70, 70), Image.Resampling.LANCZOS)
                self.logo_photo = ImageTk.PhotoImage(logo)
                tk.Label(body, image=self.logo_photo, bg=PANEL).pack(anchor="w")
            except Exception:
                pass

        tk.Label(body, text=APP_NAME, bg=PANEL, fg=TEXT, font=("Georgia", 26)).pack(anchor="w", pady=(10, 1))
        tk.Label(body, text="Installer & Updater", bg=PANEL, fg=TEXT, font=("Georgia", 21)).pack(anchor="w")
        tk.Label(
            body,
            text=f"Install or update {APP_NAME} from the official KSPOG/aokuvue {SOURCE_BRANCH} branch.",
            bg=PANEL,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(9, 12))

        path_row = tk.Frame(body, bg=PANEL)
        path_row.pack(fill=X)
        self.path_entry = tk.Entry(
            path_row,
            textvariable=self.install_dir,
            bg=PANEL_ALT,
            fg=TEXT,
            insertbackground=TEXT,
            relief="flat",
            highlightbackground="#39334D",
            highlightcolor=VIOLET,
            highlightthickness=1,
            font=("Segoe UI", 9),
        )
        self.path_entry.pack(side=LEFT, fill=X, expand=True, ipady=7)
        self.path_entry.bind("<FocusOut>", lambda _event: self._remember_current_path())
        self.browse_button = self._button(path_row, "Browse…", self.browse, primary=False, width=10)
        self.browse_button.pack(side=RIGHT, padx=(9, 0), ipady=3)

        tk.Label(
            body,
            text="The selected install path is remembered automatically. Updates use the same folder unless you choose a different location.",
            bg=PANEL,
            fg=MUTED,
            wraplength=620,
            justify="left",
            font=("Segoe UI", 8),
        ).pack(anchor="w", pady=(8, 9))

        self.status_label = tk.Label(
            body,
            textvariable=self.status,
            bg=PANEL,
            fg=TEXT,
            wraplength=620,
            justify="left",
            font=("Segoe UI", 9),
        )
        self.status_label.pack(anchor="w", pady=(0, 6))

        self.progress_bar = ttk.Progressbar(
            body,
            variable=self.progress,
            maximum=100,
            orient=HORIZONTAL,
            style="Aokuvue.Horizontal.TProgressbar",
        )
        self.progress_bar.pack(fill=X, pady=(0, 10))

        actions = tk.Frame(body, bg=PANEL)
        actions.pack(fill=X, pady=(0, 10))
        self.install_button = self._button(actions, "Build & Update", self.start_install, primary=True, width=15)
        self.install_button.pack(side=LEFT, ipady=4)
        self.launch_button = self._button(actions, f"Launch {APP_NAME}", self.launch, primary=False, width=18)
        self.launch_button.pack(side=LEFT, padx=(10, 0), ipady=4)
        self._refresh_launch_state()

        log_frame = tk.Frame(body, bg="#06060A", highlightbackground="#322B49", highlightthickness=1)
        log_frame.pack(fill=BOTH, expand=True)
        scrollbar = tk.Scrollbar(log_frame)
        scrollbar.pack(side=RIGHT, fill="y")
        self.log_text = tk.Text(
            log_frame,
            height=7,
            bg="#06060A",
            fg="#CFC8DF",
            insertbackground=TEXT,
            relief="flat",
            wrap="word",
            yscrollcommand=scrollbar.set,
            font=("Consolas", 8),
        )
        self.log_text.pack(side=LEFT, fill=BOTH, expand=True, padx=8, pady=6)
        scrollbar.configure(command=self.log_text.yview)
        self.log_text.configure(state="disabled")
        self._log(f"{APP_NAME} Installer & Updater v{INSTALLER_VERSION}")
        self._log(f"Install path: {self.install_dir.get()}")

        canvas.bind("<Configure>", self._resize_panel)

    def _resize_panel(self, event):
        width = min(680, max(620, event.width - 80))
        x = max(40, (event.width - width) // 2)
        height = min(535, max(500, event.height - 55))
        y = max(28, (event.height - height) // 2)
        self.canvas.coords(self.panel_window, x, y)
        self.canvas.itemconfigure(self.panel_window, width=width, height=height)

    def _button(self, parent, text, command, primary: bool, width: int):
        background = VIOLET if primary else "#151320"
        foreground = "#090910" if primary else TEXT
        active_bg = VIOLET_HOVER if primary else "#242035"
        return tk.Button(
            parent,
            text=text,
            command=command,
            width=width,
            bg=background,
            fg=foreground,
            activebackground=active_bg,
            activeforeground=foreground,
            relief="flat",
            bd=0,
            highlightthickness=1,
            highlightbackground=VIOLET if primary else BORDER,
            font=("Segoe UI Semibold", 9),
            cursor="hand2",
        )

    def browse(self):
        selected = filedialog.askdirectory(initialdir=self.install_dir.get(), title=f"Choose {APP_NAME} install folder")
        if selected:
            self.install_dir.set(selected)
            self._remember_current_path()
            self._refresh_launch_state()

    def _remember_current_path(self):
        value = self.install_dir.get().strip()
        if value:
            try:
                remember_install_dir(Path(value))
            except OSError as error:
                self._log(f"Could not remember install path: {error}")

    def _refresh_launch_state(self):
        path = Path(self.install_dir.get().strip() or default_install_dir())
        installed = (path / f"{APP_NAME}.exe").is_file()
        self.launch_button.configure(state="normal" if installed and not self.busy else "disabled")
        if installed and not self.busy and not self.args.update:
            self.status.set(f"Existing {APP_NAME} installation detected — update mode.")

    def start_install(self):
        if self.busy:
            return
        value = self.install_dir.get().strip()
        if not value:
            messagebox.showerror(f"{APP_NAME} Installer", "Choose an install folder first.", parent=self.root)
            return
        destination = Path(value).expanduser().absolute()
        self.install_dir.set(str(destination))
        self._remember_current_path()

        if not self.args.wait_pid:
            pids = running_aokuvue_pids()
            if pids:
                accepted = messagebox.askyesno(
                    f"Close {APP_NAME}?",
                    f"{APP_NAME} is currently running and must close before its files can be updated.\n\nClose {APP_NAME} and continue?",
                    parent=self.root,
                )
                if not accepted:
                    return

        self.busy = True
        self.install_button.configure(state="disabled")
        self.browse_button.configure(state="disabled")
        self.launch_button.configure(state="disabled")
        self.progress.set(1)
        self.status_label.configure(fg=TEXT)
        thread = threading.Thread(target=self._install_worker, args=(destination,), daemon=True)
        thread.start()

    def _install_worker(self, destination: Path):
        try:
            # Stage on the destination volume. This avoids filling the system temp
            # drive and keeps the final directory swap on one filesystem.
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(
                prefix=f".{APP_NAME.replace(' ', '')}Installer-",
                dir=destination.parent,
            ) as temporary:
                work_dir = Path(temporary)
                self._status(f"Checking the latest {APP_NAME} version…")
                commit = latest_source_commit()
                version = latest_app_version(commit)
                self._log_threadsafe(f"Latest {APP_NAME}: {version} ({commit[:12]})")
                self._progress(5)

                project = download_source(commit, work_dir, self._log_threadsafe, self._progress)
                jdk = ensure_jdk(self._log_threadsafe, self._progress)
                image = package_application(project, jdk, work_dir, version, self._log_threadsafe, self._progress)

                if self.args.wait_pid:
                    self._status(f"Waiting for {APP_NAME} to close before updating…")
                    wait_for_pid(int(self.args.wait_pid), self._log_threadsafe)
                else:
                    stop_running_aokuvue(self._log_threadsafe)

                self._status(f"Installing {APP_NAME} {version}…")
                install_app_image(image, destination, self._log_threadsafe, self._progress)
                remember_install_dir(destination)
                self.events.put(("complete", version))
        except Exception as error:
            self.events.put(("failed", error))

    def launch(self):
        try:
            launch_application(Path(self.install_dir.get()))
        except Exception as error:
            messagebox.showerror(f"{APP_NAME} Installer", str(error), parent=self.root)

    def _progress(self, value: float):
        self.events.put(("progress", float(value)))

    def _status(self, value: str):
        self.events.put(("status", value))

    def _log_threadsafe(self, value: str):
        self.events.put(("log", value))

    def _log(self, value: str):
        self.log_text.configure(state="normal")
        self.log_text.insert(END, value.rstrip() + "\n")
        self.log_text.see(END)
        self.log_text.configure(state="disabled")

    def _pump_events(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "progress":
                    self.progress.set(float(value))
                elif kind == "status":
                    self.status.set(str(value))
                elif kind == "log":
                    self._log(str(value))
                elif kind == "complete":
                    self.busy = False
                    version = str(value)
                    self.status.set(f"{APP_NAME} {version} installed successfully.")
                    self.status_label.configure(fg=SUCCESS)
                    self.install_button.configure(state="normal")
                    self.browse_button.configure(state="normal")
                    self.progress.set(100)
                    self._refresh_launch_state()
                    self._log(f"{APP_NAME} {version} installation complete.")
                elif kind == "failed":
                    self.busy = False
                    error = value
                    self.status.set(f"Installation failed: {error}")
                    self.status_label.configure(fg=ERROR)
                    self.install_button.configure(state="normal")
                    self.browse_button.configure(state="normal")
                    self._refresh_launch_state()
                    self._log(f"ERROR: {error}")
        except queue.Empty:
            pass
        if self.root.winfo_exists():
            self.root.after(100, self._pump_events)

    def on_close(self):
        if self.busy:
            if not messagebox.askyesno(
                f"Exit {APP_NAME} Installer?",
                "An install or update is still running. Exiting now will cancel the installer window. Continue?",
                parent=self.root,
            ):
                return
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def run_self_test() -> int:
    assert APP_NAME.strip()
    assert INSTALLER_VERSION.strip()
    assert SOURCE_BRANCH in {"main", "dev"}
    assert len(source_archive_urls("a" * 40)) == 3
    assert source_archive_urls("a" * 40)[0].startswith("https://codeload.github.com/")
    assert default_install_dir().name == APP_NAME
    assert canonical_state_file().parent.parent.name == APP_NAME
    assert installer_cache_dir().parent.name == APP_NAME
    if SOURCE_BRANCH == "dev":
        assert APP_NAME != "Aokuvue"
        assert not legacy_state_files()
    assert parse_build_version("group='x'\nversion = '1.5.18'\n") == "1.5.18"
    assert parse_version_text("v1.10.0") > parse_version_text("1.9.9")

    remembered = preferred_install_dir(None, {"lastInstallPath": r"C:\Apps\Aokuvue"})
    explicit = preferred_install_dir(r"D:\Custom\Aokuvue", {"lastInstallPath": r"C:\Ignored"})
    assert str(remembered).lower().endswith(r"apps\aokuvue")
    assert str(explicit).lower().endswith(r"custom\aokuvue")
    assert gradle_user_home_dir().name == "gradle-home"
    print(f"{APP_NAME} Installer & Updater v{INSTALLER_VERSION} ({SOURCE_BRANCH}) self-test passed")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=f"{APP_NAME} Installer & Updater")
    parser.add_argument("--update", action="store_true", help="Automatically start an application update")
    parser.add_argument("--install-dir", help="Install/update destination")
    parser.add_argument("--wait-pid", type=int, default=0, help="Wait for an Aokuvue process to exit before replacing files")
    parser.add_argument("--self-test", action="store_true", help="Run non-GUI installer source tests")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(list(sys.argv[1:] if argv is None else argv))
    if args.self_test:
        return run_self_test()
    if os.name != "nt":
        print(f"{APP_NAME} Installer & Updater is Windows-only.", file=sys.stderr)
        return 2
    InstallerWindow(args).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
