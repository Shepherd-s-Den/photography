#!/usr/bin/env python3

from dataclasses import dataclass
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from typing import Callable, Mapping
import urllib.error
import urllib.request
import webbrowser


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080
READINESS_TIMEOUT_SECONDS = 180
PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 10
DEFAULT_PROTOTYPE_FRONTEND_PORT = 5173


class PreflightError(RuntimeError):
    pass


class AlreadyRunning(PreflightError):
    pass


class StartupError(RuntimeError):
    pass


@dataclass(frozen=True)
class LocalState:
    directory: Path
    database: Path
    lock: Path


def make_local_state(project_root: Path) -> LocalState:
    directory = project_root / ".local"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return LocalState(
        directory=directory,
        database=directory / "photography.db",
        lock=directory / "photography.lock",
    )


def acquire_instance_lock(lock_path: Path):
    lock_file = lock_path.open("a+", encoding="utf-8")
    try:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as error:
        lock_file.close()
        raise AlreadyRunning("Another local photography backend is already running") from error
    return lock_file


def port_available(host: str, port: int) -> bool:
    try:
        addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return False
    if not addresses:
        return False

    family, socket_type, protocol, _, address = addresses[0]
    with socket.socket(family, socket_type, protocol) as listener:
        try:
            listener.bind(address)
        except OSError:
            return False
    return True


def preflight(host: str, port: int) -> None:
    for executable in ("java", "sbt"):
        if shutil.which(executable) is None:
            raise PreflightError(f"Required executable '{executable}' was not found on PATH")
    if not port_available(host, port):
        raise PreflightError(f"Port {port} is unavailable on {host}")


def service_url(host: str, port: int) -> str:
    display_host = {"0.0.0.0": "127.0.0.1", "::": "::1"}.get(host, host)
    if ":" in display_host and not display_host.startswith("["):
        display_host = f"[{display_host}]"
    return f"http://{display_host}:{port}"


def health_url(host: str, port: int) -> str:
    return f"{service_url(host, port)}/health"


def health_check(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            content_type = response.headers.get_content_type()
            payload = json.loads(response.read().decode("utf-8"))
            return response.status == 200 and content_type == "application/json" and payload == {"status": "ok"}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ConnectionError):
        return False


def page_check(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200 and response.headers.get_content_type() == "text/html"
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return False


def wait_for_readiness(
    process: subprocess.Popen[bytes],
    url: str,
    timeout: int = READINESS_TIMEOUT_SECONDS,
    readiness_check: Callable[[str], bool] | None = None,
    service_name: str = "Backend",
) -> None:
    check = readiness_check or health_check
    deadline = time.monotonic() + timeout
    while True:
        exit_code = process.poll()
        if exit_code is not None:
            raise StartupError(f"{service_name} exited before readiness with status {exit_code}")
        if check(url):
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"{service_name} did not become ready within {timeout} seconds")
        time.sleep(min(1, remaining))


def stop_process_tree(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=PROCESS_SHUTDOWN_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            return
        process.wait()


def parse_port(environment: Mapping[str, str]) -> int:
    raw_port = environment.get("PHOTOGRAPHY_PORT")
    if raw_port is None:
        return DEFAULT_PORT
    try:
        port = int(raw_port)
    except ValueError as error:
        raise PreflightError("PHOTOGRAPHY_PORT must be an integer in 1..65535") from error
    if port < 1 or port > 65535:
        raise PreflightError("PHOTOGRAPHY_PORT must be an integer in 1..65535")
    return port


def parse_prototype_frontend_port(environment: Mapping[str, str]) -> int:
    raw_port = environment.get(
        "PHOTOGRAPHY_PROTOTYPE_FRONTEND_PORT",
        str(DEFAULT_PROTOTYPE_FRONTEND_PORT),
    )
    try:
        port = int(raw_port)
    except ValueError as error:
        raise PreflightError("PHOTOGRAPHY_PROTOTYPE_FRONTEND_PORT must be an integer in 1..65535") from error
    if port < 1 or port > 65535:
        raise PreflightError("PHOTOGRAPHY_PROTOTYPE_FRONTEND_PORT must be an integer in 1..65535")
    return port


def active_prototype_slug(project_root: Path) -> str | None:
    active_file = project_root / ".agent-work" / "active-prototype"
    if not active_file.exists():
        return None
    slug = active_file.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", slug):
        raise PreflightError("The local active-prototype marker must contain one lowercase slug")
    spike = project_root / ".agent-work" / slug / "spike"
    frontend = spike / "frontend"
    if not frontend.is_dir() or not (frontend / "package.json").is_file():
        raise PreflightError(f"Prototype frontend package was not found: {frontend}")
    return slug


def install_prototype_frontend(frontend: Path, environment: Mapping[str, str]) -> None:
    package = frontend / "package.json"
    lock = frontend / "package-lock.json"
    installed_lock = frontend / "node_modules" / ".package-lock.json"
    vite = frontend / "node_modules" / ".bin" / "vite"
    if not package.is_file():
        raise PreflightError(f"Prototype frontend package was not found: {package}")

    input_mtime = max(package.stat().st_mtime_ns, lock.stat().st_mtime_ns if lock.exists() else 0)
    install_needed = (
        not vite.is_file()
        or not installed_lock.is_file()
        or input_mtime > installed_lock.stat().st_mtime_ns
    )
    if not install_needed:
        return

    command = ["npm", "ci"] if lock.is_file() else ["npm", "install"]
    command.extend(["--no-audit", "--no-fund"])
    print("Installing prototype frontend dependencies")
    result = subprocess.run(command, cwd=frontend, env=environment, check=False)
    if result.returncode != 0:
        raise StartupError(f"Prototype frontend dependency installation failed with status {result.returncode}")


def wait_for_services(
    backend: subprocess.Popen[bytes],
    frontend: subprocess.Popen[bytes],
) -> int:
    while True:
        backend_exit = backend.poll()
        if backend_exit is not None:
            return backend_exit
        frontend_exit = frontend.poll()
        if frontend_exit is not None:
            raise StartupError(f"Prototype frontend exited with status {frontend_exit}")
        time.sleep(1)


def run_local_backend(
    project_root: Path,
    environment: Mapping[str, str] | None = None,
    prototype_slug: str | None = None,
) -> int:
    launch_environment = dict(os.environ if environment is None else environment)
    state = make_local_state(project_root)
    lock_file = acquire_instance_lock(state.lock)
    process: subprocess.Popen[bytes] | None = None
    frontend_process: subprocess.Popen[bytes] | None = None
    try:
        host = launch_environment.get("PHOTOGRAPHY_HOST") or DEFAULT_HOST
        port = parse_port(launch_environment)
        preflight(host, port)
        has_backend_spike = False
        if prototype_slug is not None:
            frontend_port = parse_prototype_frontend_port(launch_environment)
            if shutil.which("npm") is None:
                raise PreflightError("Required executable 'npm' was not found on PATH")
            if frontend_port == port or not port_available(host, frontend_port):
                raise PreflightError(f"Prototype frontend port {frontend_port} is unavailable on {host}")
            frontend = project_root / ".agent-work" / prototype_slug / "spike" / "frontend"
            backend_main = (
                project_root
                / ".agent-work"
                / prototype_slug
                / "spike"
                / "backend"
                / "photography"
                / "spike"
                / "Main.scala"
            )
            has_backend_spike = backend_main.is_file()
            install_prototype_frontend(frontend, launch_environment)
            prototype_data = project_root / ".agent-work" / prototype_slug / "spike" / "data"
            prototype_data.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.chmod(prototype_data, 0o700)
            launch_environment["PHOTOGRAPHY_SQLITE_PATH"] = str(
                prototype_data / "PROTOTYPE-wipe-me.sqlite"
            )
            launch_environment["PHOTOGRAPHY_PROTOTYPE_FRONTEND_PORT"] = str(frontend_port)
            if has_backend_spike:
                launch_environment["PHOTOGRAPHY_PROTOTYPE_MODE"] = "1"
            else:
                launch_environment.pop("PHOTOGRAPHY_PROTOTYPE_MODE", None)
        else:
            launch_environment["PHOTOGRAPHY_SQLITE_PATH"] = str(state.database)
            launch_environment.pop("PHOTOGRAPHY_PROTOTYPE_MODE", None)
        if prototype_slug is not None and has_backend_spike:
            backend_command = ["sbt", "-batch", "runMain photography.spike.Main"]
        else:
            backend_command = ["sbt", "-batch", "run"]
        process = subprocess.Popen(
            backend_command,
            cwd=project_root / "backend",
            env=launch_environment,
            start_new_session=True,
        )
        endpoint = service_url(host, port)
        wait_for_readiness(process, f"{endpoint}/health")
        print(f"Photography backend ready at {endpoint}")
        if prototype_slug is not None:
            launch_environment["VITE_API_TARGET"] = endpoint
            frontend_process = subprocess.Popen(
                ["npm", "run", "dev", "--", "--host", host, "--port", str(frontend_port), "--strictPort"],
                cwd=frontend,
                env=launch_environment,
                start_new_session=True,
            )
            frontend_endpoint = service_url(host, frontend_port)
            wait_for_readiness(
                frontend_process,
                frontend_endpoint,
                readiness_check=page_check,
                service_name="Prototype frontend",
            )
            print(f"Local prototype ready at {frontend_endpoint}")
            webbrowser.open(frontend_endpoint)
            return wait_for_services(process, frontend_process)
        return process.wait()
    finally:
        try:
            if frontend_process is not None:
                stop_process_tree(frontend_process)
        finally:
            try:
                if process is not None:
                    stop_process_tree(process)
            finally:
                lock_file.close()


def main() -> int:
    project_root = Path(__file__).resolve().parent.parent
    try:
        return run_local_backend(project_root, prototype_slug=active_prototype_slug(project_root))
    except (PreflightError, StartupError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
