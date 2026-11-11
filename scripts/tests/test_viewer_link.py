"""Check reconnect/ownership rules without accessing a phone or user service."""

import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import select
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch


PATH = Path(__file__).resolve().parents[1] / "viewer-link.py"
SPEC = importlib.util.spec_from_file_location("viewer_link", PATH)
link = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(link)
SERIAL = "100.115.117.61:5555"
OTHER = "100.100.100.100:5555"


class FakeAdb:
    def __init__(self):
        self.online = True
        self.model = "SM-F711B"
        self.owner = None
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        if args == ("forward", "--list"):
            output = f"{self.owner[0]} {link.LOCAL} {self.owner[1]}\n" if self.owner else ""
        elif args == ("-s", SERIAL, "get-state"):
            return subprocess.CompletedProcess(args, 0 if self.online else 1, "device" if self.online else "", "")
        elif args == ("connect", SERIAL):
            self.online = True
            output = "connected"
        elif args == ("-s", SERIAL, "shell", "getprop", "ro.product.model"):
            output = self.model
        elif args == ("-s", SERIAL, "forward", "--no-rebind", link.LOCAL, link.REMOTE):
            if self.owner:
                return subprocess.CompletedProcess(args, 1, "", "cannot rebind")
            self.owner = (SERIAL, link.REMOTE)
            output = "18443"
        elif args == ("-s", SERIAL, "forward", "--remove", link.LOCAL):
            self.owner = None
            output = ""
        else:
            raise AssertionError(f"Unexpected ADB operation: {args}")
        return subprocess.CompletedProcess(args, 0, output, "")


class ViewerLinkTest(unittest.TestCase):
    def setUp(self):
        self.adb = FakeAdb()
        self.patcher = patch.object(link, "adb", self.adb)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        environment = patch.dict(os.environ, XDG_RUNTIME_DIR=directory.name)
        environment.start()
        self.addCleanup(environment.stop)

    def test_management_lock_serializes_independent_processes(self):
        # A real child imports the helper but never calls ADB or systemd.
        code = """
import importlib.util, sys
spec = importlib.util.spec_from_file_location('child_link', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print('attempting', flush=True)
with module.management_lock():
    print('acquired', flush=True)
"""
        child = None
        try:
            with link.management_lock():
                child = subprocess.Popen(
                    [sys.executable, "-c", code, str(PATH)],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                )
                self.assertTrue(select.select([child.stdout], [], [], 3)[0])
                self.assertEqual(child.stdout.readline().strip(), "attempting")
                self.assertFalse(select.select([child.stdout], [], [], 0.2)[0])
                self.assertIsNone(child.poll())
            output, errors = child.communicate(timeout=3)
            self.assertEqual(child.returncode, 0, errors)
            self.assertEqual(output.strip(), "acquired")
            self.assertEqual(self.adb.calls, [])
        finally:
            if child is not None:
                if child.poll() is None:
                    child.kill()
                child.communicate()

    def test_missing_forward_is_restored_without_reopening_camera(self):
        self.assertEqual(link.ensure_link(SERIAL), "ready")
        self.adb.owner = None  # ADB lost the mapping while the phone stayed online.
        self.assertEqual(link.ensure_link(SERIAL), "ready")
        self.assertEqual(self.adb.owner, (SERIAL, link.REMOTE))
        self.assertEqual(sum("--no-rebind" in args for args in self.adb.calls), 2)

    def test_existing_route_is_reused(self):
        self.adb.owner = (SERIAL, link.REMOTE)
        self.assertEqual(link.ensure_link(SERIAL), "ready")
        self.assertFalse(any("--no-rebind" in args for args in self.adb.calls))

    def test_other_device_or_destination_is_never_rebound(self):
        for owner in [(OTHER, link.REMOTE), (SERIAL, "tcp:9999")]:
            self.adb.owner = owner
            self.assertEqual(link.ensure_link(SERIAL), "conflict")
            self.assertEqual(self.adb.owner, owner)
        self.assertFalse(any("--no-rebind" in args or "--remove" in args for args in self.adb.calls))

    def test_private_device_reconnects_then_recovers_route(self):
        self.adb.online = False
        self.assertEqual(link.ensure_link(SERIAL, reconnect=True), "waiting")
        self.assertIn(("connect", SERIAL), self.adb.calls)
        self.assertEqual(link.ensure_link(SERIAL, reconnect=True), "ready")
        self.assertEqual(self.adb.owner, (SERIAL, link.REMOTE))

    def test_unapproved_network_addresses_are_not_reconnected(self):
        for serial in ["8.8.8.8:5555", "example.com:5555", "100.115.117.61:6666", "R5CR71L00WN"]:
            with self.subTest(serial=serial):
                with patch.object(link, "adb", return_value=subprocess.CompletedProcess([], 1, "", "")) as adb:
                    self.assertEqual(link.ensure_link(serial, reconnect=True), "waiting")
                    self.assertEqual(adb.call_count, 1)

    def test_wrong_model_does_not_create_a_route(self):
        self.adb.model = "Other phone"
        self.assertEqual(link.ensure_link(SERIAL), "wrong_device")
        self.assertIsNone(self.adb.owner)

    def test_active_service_for_another_target_is_not_changed(self):
        with patch.object(link, "service_target", return_value=OTHER):
            with self.assertRaises(link.LinkError):
                link.start(SERIAL)
        self.assertEqual(self.adb.calls, [])

    def test_start_reuses_the_same_service(self):
        with patch.object(link, "service_target", return_value=SERIAL), patch.object(link, "run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
            with redirect_stdout(io.StringIO()):
                link.start(SERIAL)
            run.assert_called_once_with("systemctl", "--user", "start", link.UNIT)

    def test_start_without_user_service_still_opens_one_time_link(self):
        with patch.object(link, "run", side_effect=FileNotFoundError), redirect_stderr(io.StringIO()) as error:
            link.start(SERIAL)
        self.assertEqual(self.adb.owner, (SERIAL, link.REMOTE))
        self.assertIn("Chưa có tự phục hồi", error.getvalue())

    def test_stop_removes_only_the_matching_route(self):
        self.adb.owner = (SERIAL, link.REMOTE)
        with patch.object(link, "service_target", return_value=SERIAL), patch.object(link, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
            with redirect_stdout(io.StringIO()):
                link.stop()
        self.assertIsNone(self.adb.owner)
        self.assertFalse(any("connect" in args for args in self.adb.calls))

    def test_stop_leaves_another_routes_owner_intact(self):
        self.adb.owner = (OTHER, link.REMOTE)
        with patch.object(link, "service_target", return_value=SERIAL), patch.object(link, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
            with redirect_stdout(io.StringIO()):
                link.stop()
        self.assertEqual(self.adb.owner, (OTHER, link.REMOTE))
        self.assertFalse(any("--remove" in args for args in self.adb.calls))

    def test_stop_without_known_target_does_not_infer_ownership_from_port(self):
        self.adb.owner = (OTHER, link.REMOTE)
        with patch.object(link, "service_target", return_value=None), redirect_stdout(io.StringIO()):
            link.stop()
        self.assertEqual(self.adb.owner, (OTHER, link.REMOTE))
        self.assertFalse(any("--remove" in args for args in self.adb.calls))

    def test_watcher_recovers_from_transient_adb_timeout(self):
        attempts = 0

        def flaky(*args):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise subprocess.TimeoutExpired("adb", 10)
            return self.adb(*args)

        with patch.object(link, "adb", side_effect=flaky):
            with patch.object(link.time, "sleep", side_effect=[None, InterruptedError]):
                with redirect_stdout(io.StringIO()) as output:
                    with self.assertRaises(InterruptedError):
                        link.watch(SERIAL)
        self.assertEqual(self.adb.owner, (SERIAL, link.REMOTE))
        self.assertIn(link.MESSAGES["error"], output.getvalue())
        self.assertIn(link.MESSAGES["ready"], output.getvalue())

    def test_service_name_collision_does_not_target_an_unrelated_process(self):
        with patch.object(link, "run", side_effect=self.service_commands(script="/unrelated.py")):
            with self.assertRaises(link.LinkError):
                link.service_target()

    def service_commands(self, target=SERIAL, script=str(link.SCRIPT)):
        def command(*args):
            if args[0] == "systemctl":
                output = "LoadState=loaded\nActiveState=activating\nSubState=auto-restart\nMainPID=0\n"
            elif "GetUnit" in args:
                output = json.dumps({"data": ["/test/unit"]})
            else:
                output = json.dumps({"data": [["/usr/bin/python3", ["/usr/bin/python3", script, "watch", target]]]})
            return subprocess.CompletedProcess(args, 0, output, "")
        return command

    def test_installed_helper_is_recognized_with_typed_target(self):
        with patch.object(link, "run", side_effect=self.service_commands(script=str(link.INSTALLED_HELPER))):
            self.assertEqual(link.service_target(), SERIAL)
        with patch.object(link, "run", side_effect=self.service_commands(script="/tmp/other/viewer-link.py")):
            with self.assertRaises(link.LinkError):
                link.service_target()

    def test_target_is_retained_while_service_has_no_main_pid(self):
        with patch.object(link, "run", side_effect=self.service_commands()):
            self.assertEqual(link.service_target(), SERIAL)

    def test_stop_cancels_a_service_waiting_for_restart(self):
        self.adb.owner = (SERIAL, link.REMOTE)
        with patch.object(link, "run", side_effect=self.service_commands()) as run:
            with redirect_stdout(io.StringIO()):
                link.stop()
            self.assertIn(unittest.mock.call("systemctl", "--user", "stop", link.UNIT), run.call_args_list)
        self.assertIsNone(self.adb.owner)

    def test_other_target_cannot_start_during_service_restart(self):
        with patch.object(link, "run", side_effect=self.service_commands(target=OTHER)):
            with self.assertRaises(link.LinkError):
                link.start(SERIAL)
        self.assertEqual(self.adb.calls, [])

    def test_existing_route_after_reconnect_does_not_skip_model_check(self):
        self.adb.owner = (SERIAL, link.REMOTE)
        self.adb.model = "Other phone"
        self.assertEqual(link.ensure_link(SERIAL), "wrong_device")


if __name__ == "__main__":
    unittest.main()
