import signal
import stat
import tempfile
import unittest
from pathlib import Path
from subprocess import TimeoutExpired
from unittest import mock

import local


class LocalStateComponentTest(unittest.TestCase):
    def test_should_state_directory_be_private_and_use_a_local_database(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            state = local.make_local_state(Path(temporary_directory))

            self.assertEqual(state.directory, Path(temporary_directory) / ".local")
            self.assertEqual(state.database, state.directory / "photography.db")
            self.assertEqual(stat.S_IMODE(state.directory.stat().st_mode), 0o700)

    def test_should_instance_lock_reject_a_second_launcher_and_release_after_close(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            state = local.make_local_state(Path(temporary_directory))
            first_lock = local.acquire_instance_lock(state.lock)

            try:
                with self.assertRaises(local.AlreadyRunning):
                    local.acquire_instance_lock(state.lock)
            finally:
                first_lock.close()

            second_lock = local.acquire_instance_lock(state.lock)
            second_lock.close()


class LocalRunnerComponentTest(unittest.TestCase):
    def test_should_health_url_use_loopback_for_wildcard_bind_addresses(self):
        ipv4_url = local.health_url("0.0.0.0", 8080)
        ipv6_url = local.health_url("::", 8080)
        local_url = local.health_url("127.0.0.1", 8080)

        self.assertEqual(ipv4_url, "http://127.0.0.1:8080/health")
        self.assertEqual(ipv6_url, "http://[::1]:8080/health")
        self.assertEqual(local_url, "http://127.0.0.1:8080/health")

    def test_should_preflight_check_required_tools_and_port_availability(self):
        with mock.patch.object(local.shutil, "which", side_effect=["/usr/bin/java", "/usr/bin/sbt"]):
            with mock.patch.object(local, "port_available", return_value=True):
                local.preflight("127.0.0.1", 8080)

        with mock.patch.object(local.shutil, "which", return_value=None):
            with self.assertRaises(local.PreflightError):
                local.preflight("127.0.0.1", 8080)

        with mock.patch.object(
            local.shutil,
            "which",
            side_effect=lambda executable: None if executable == "sbt" else "/usr/bin/java",
        ):
            with self.assertRaises(local.PreflightError):
                local.preflight("127.0.0.1", 8080)

        with mock.patch.object(local.shutil, "which", return_value="/usr/bin/tool"):
            with mock.patch.object(local, "port_available", return_value=False):
                with self.assertRaises(local.PreflightError):
                    local.preflight("127.0.0.1", 8080)

    def test_should_readiness_wait_for_the_exact_health_response(self):
        process = mock.Mock()
        process.poll.return_value = None

        with mock.patch.object(local, "health_check", side_effect=[False, True]):
            with mock.patch.object(local.time, "sleep"):
                local.wait_for_readiness(process, "http://127.0.0.1:8080/health")

        process.poll.assert_called()

    def test_should_readiness_fail_if_process_exits_or_deadline_expires(self):
        exited_process = mock.Mock()
        exited_process.poll.return_value = 7

        with self.assertRaises(local.StartupError):
            local.wait_for_readiness(exited_process, "http://127.0.0.1:8080/health")

        running_process = mock.Mock()
        running_process.poll.return_value = None
        with mock.patch.object(local.time, "monotonic", side_effect=[0, 181]):
            with mock.patch.object(local, "health_check", return_value=False):
                with self.assertRaises(TimeoutError):
                    local.wait_for_readiness(
                        running_process,
                        "http://127.0.0.1:8080/health",
                        timeout=180,
                    )

    def test_should_shutdown_terminate_the_process_group_and_escalate_if_needed(self):
        process = mock.Mock()
        process.pid = 42
        process.poll.return_value = None
        process.wait.return_value = 0

        with mock.patch.object(local.os, "killpg") as kill_process_group:
            local.stop_process_tree(process)

        kill_process_group.assert_called_once_with(42, signal.SIGTERM)

        stuck_process = mock.Mock()
        stuck_process.pid = 43
        stuck_process.poll.return_value = None
        stuck_process.wait.side_effect = [TimeoutExpired("sbt", 10), 0]
        with mock.patch.object(local.os, "killpg") as kill_process_group:
            local.stop_process_tree(stuck_process)

        expected_signals = [mock.call(43, signal.SIGTERM), mock.call(43, signal.SIGKILL)]

        self.assertEqual(kill_process_group.call_args_list, expected_signals)

    def test_should_launcher_pass_overrides_to_the_child_and_stop_it(self):
        process = mock.Mock()
        process.pid = 44
        process.poll.return_value = None
        process.wait.return_value = 0

        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "preflight") as preflight:
                with mock.patch.object(local.subprocess, "Popen", return_value=process) as start:
                    with mock.patch.object(local, "wait_for_readiness") as ready:
                        with mock.patch.object(local, "stop_process_tree") as stop:
                            with mock.patch("builtins.print") as output:
                                result = local.run_local_backend(
                                    Path(temporary_directory),
                                    {"PHOTOGRAPHY_HOST": "0.0.0.0", "PHOTOGRAPHY_PORT": "8090"},
                                )

            state = local.make_local_state(Path(temporary_directory))
            next_lock = local.acquire_instance_lock(state.lock)
            next_lock.close()

        self.assertEqual(result, 0)
        preflight.assert_called_once_with("0.0.0.0", 8090)
        self.assertEqual(start.call_args.args[0], ["sbt", "-batch", "run"])
        self.assertEqual(start.call_args.kwargs["cwd"], Path(temporary_directory) / "backend")
        self.assertTrue(start.call_args.kwargs["start_new_session"])
        self.assertEqual(start.call_args.kwargs["env"]["PHOTOGRAPHY_HOST"], "0.0.0.0")
        self.assertEqual(start.call_args.kwargs["env"]["PHOTOGRAPHY_PORT"], "8090")
        expected_database = str(Path(temporary_directory) / ".local" / "photography.db")
        actual_database = start.call_args.kwargs["env"]["PHOTOGRAPHY_SQLITE_PATH"]

        self.assertEqual(actual_database, expected_database)
        ready.assert_called_once_with(process, "http://127.0.0.1:8090/health")
        stop.assert_called_once_with(process)
        output.assert_called_once_with("Photography backend ready at http://127.0.0.1:8090")

    def test_should_readiness_timeout_stop_the_child_and_release_the_lock(self):
        process = mock.Mock()
        process.pid = 46
        process.poll.return_value = None

        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "preflight"):
                with mock.patch.object(local.subprocess, "Popen", return_value=process):
                    with mock.patch.object(local, "wait_for_readiness", side_effect=TimeoutError("not ready")):
                        with mock.patch.object(local, "stop_process_tree") as stop:
                            with mock.patch("builtins.print"):
                                with self.assertRaises(TimeoutError):
                                    local.run_local_backend(Path(temporary_directory), {})

            stop.assert_called_once_with(process)
            state = local.make_local_state(Path(temporary_directory))
            next_lock = local.acquire_instance_lock(state.lock)
            next_lock.close()

    def test_should_preflight_failure_avoid_starting_the_backend(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "preflight", side_effect=local.PreflightError("port busy")):
                with mock.patch.object(local.subprocess, "Popen") as start:
                    with self.assertRaises(local.PreflightError):
                        local.run_local_backend(Path(temporary_directory), {})

        start.assert_not_called()

    def test_should_interruption_stop_the_child_and_release_the_lock(self):
        process = mock.Mock()
        process.pid = 45
        process.poll.return_value = None
        process.wait.side_effect = KeyboardInterrupt()

        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "preflight"):
                with mock.patch.object(local.subprocess, "Popen", return_value=process):
                    with mock.patch.object(local, "wait_for_readiness"):
                        with mock.patch.object(local, "stop_process_tree") as stop:
                            with mock.patch("builtins.print"):
                                with self.assertRaises(KeyboardInterrupt):
                                    local.run_local_backend(Path(temporary_directory), {})

            stop.assert_called_once_with(process)
            state = local.make_local_state(Path(temporary_directory))
            next_lock = local.acquire_instance_lock(state.lock)
            next_lock.close()


if __name__ == "__main__":
    unittest.main()
