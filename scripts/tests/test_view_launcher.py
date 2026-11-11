"""Exercise launcher routing without using a phone or opening a real window."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SERIAL = "100.115.117.61:5555"
NATIVE = """Active Camera Clients:
[
(Camera ID: 2, Client Package Name: com.phamdu.flipcamera.debug, Cost: 100)
]
== Camera service events log ==
"""
IDLE_WITH_HISTORY = """Active Camera Clients: []
== Camera service events log ==
CONNECT device 2 client for package com.phamdu.flipcamera.debug
"""


class ViewLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.log = self.directory / "calls.jsonl"
        common = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ['FLIP_TEST_LOG'], 'a') as log:
    log.write(json.dumps([Path(sys.argv[0]).name, sys.argv[1:]]) + '\\n')
"""
        adb = common + """
args = sys.argv[1:]
if args == ['devices', '-l']:
    print('List of devices attached\\n100.115.117.61:5555 device model:SM_F711B')
    sys.exit(0)
if args[:1] == ['-s']:
    assert args[1] == '100.115.117.61:5555'
    args = args[2:]
if args == ['get-state']:
    print('device')
elif args == ['shell', 'getprop', 'ro.product.model']:
    print('SM-F711B')
elif args == ['shell', 'dumpsys', 'media.camera']:
    print(os.environ['FLIP_TEST_CAMERA_DUMP'])
elif args == ['forward', '--list']:
    pass
elif args == ['forward', '--no-rebind', 'tcp:18443', 'tcp:8443']:
    pass
else:
    print('Unexpected ADB operation: ' + repr(args), file=sys.stderr)
    sys.exit(2)
"""
        systemctl = common + "\nif 'show' in sys.argv: print('LoadState=not-found\\nActiveState=inactive\\nSubState=dead')\n"
        for name, text in (("adb", adb), ("scrcpy", common), ("xdg-open", common),
                           ("systemctl", systemctl), ("systemd-run", common)):
            path = self.directory / name
            path.write_text(text)
            path.chmod(0o755)

    def launch(self, *args, camera_dump=NATIVE):
        env = dict(os.environ)
        env.update(
            PATH=str(self.directory) + os.pathsep + env["PATH"],
            FLIP_TEST_LOG=str(self.log),
            FLIP_TEST_CAMERA_DUMP=camera_dump,
            XDG_RUNTIME_DIR=str(self.directory),
        )
        env.pop("FLIP_CAMERA_SERIAL", None)
        env.pop("FLIP_CAMERA_ID", None)
        result = subprocess.run(
            ["bash", str(ROOT / "run.sh"), *args],
            env=env,
            text=True,
            capture_output=True,
            timeout=10,
        )
        calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, calls

    def test_native_owner_uses_existing_viewer_instead_of_second_camera(self):
        result, calls = self.launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["xdg-open", ["https://localhost:18443/?live=1"]], calls)
        self.assertFalse(any(name == "scrcpy" for name, _ in calls))
        self.assertIn(["adb", ["-s", SERIAL, "forward", "--no-rebind", "tcp:18443", "tcp:8443"]], calls)

    def test_explicit_direct_camera_refuses_to_compete_with_native_recorder(self):
        result, calls = self.launch("--flip")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--live", result.stderr)
        self.assertFalse(any(name in {"scrcpy", "xdg-open"} for name, _ in calls))

    def test_old_camera_events_do_not_block_idle_camera(self):
        result, calls = self.launch(camera_dump=IDLE_WITH_HISTORY)
        self.assertEqual(result.returncode, 0, result.stderr)
        command = next(args for name, args in calls if name == "scrcpy")
        self.assertIn("--video-source=camera", command)
        self.assertIn("--camera-id=2", command)

    def test_phone_controls_use_display_mirroring_while_native_camera_is_busy(self):
        result, calls = self.launch("--phone", SERIAL)
        self.assertEqual(result.returncode, 0, result.stderr)
        command = next(args for name, args in calls if name == "scrcpy")
        self.assertIn("--serial=" + SERIAL, command)
        self.assertFalse(any(arg.startswith("--video-source=camera") for arg in command))
        self.assertFalse(any("media.camera" in args for name, args in calls if name == "adb"))

    def test_unknown_camera_state_does_not_start_capture(self):
        result, calls = self.launch(camera_dump="Camera service unavailable")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--phone", result.stderr)
        self.assertFalse(any(name == "scrcpy" for name, _ in calls))

    def test_help_does_not_touch_the_device(self):
        result, calls = self.launch("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("--history", result.stdout)
        self.assertEqual(calls, [])

    def test_live_starts_a_user_connection_guard(self):
        result, calls = self.launch("--live", SERIAL)
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = [args for name, args in calls if name == "systemd-run"]
        self.assertEqual(len(commands), 1)
        self.assertIn("--unit=flip-camera-viewer-link.service", commands[0])
        self.assertEqual(commands[0][-2:], ["watch", SERIAL])
        self.assertIn("--stop-viewer-link", result.stdout)

    def test_extra_arguments_are_rejected_before_device_access(self):
        result, calls = self.launch("--phone", SERIAL, "extra")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
