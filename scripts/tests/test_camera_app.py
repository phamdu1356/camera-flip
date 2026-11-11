"""Launcher routing tests, without browser/phone/session access."""
import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch, MagicMock

PATH = Path(__file__).resolve().parents[1] / 'flip-camera-app.py'
SPEC = importlib.util.spec_from_file_location('camera_app', PATH)
app = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(app)


class CameraAppTest(unittest.TestCase):
    def helper(self, state):
        helper = types.SimpleNamespace(is_tailscale_target=lambda _: True,
            ensure_link=MagicMock(return_value=state), start=MagicMock(),
            MESSAGES={'conflict': 'Cổng thuộc đường khác', 'waiting': 'Offline'})
        loader = types.SimpleNamespace(exec_module=lambda _: None)
        self.stack = [patch.object(app.importlib.util, 'spec_from_file_location', return_value=types.SimpleNamespace(loader=loader)),
                      patch.object(app.importlib.util, 'module_from_spec', return_value=helper)]
        for item in self.stack:
            item.start()
            self.addCleanup(item.stop)
        return helper

    def test_existing_profile_app_window_uses_strict_https_and_helper(self):
        helper = self.helper('ready')
        with tempfile.NamedTemporaryFile() as browser:
            config = {'helper': '/unused', 'serial': '100.115.117.61:5555', 'browser': browser.name}
            response = MagicMock()
            response.__enter__.return_value.status = 200
            with patch.object(app.subprocess, 'check_output', return_value=b'public-cert') as adb, \
                 patch.object(app.ssl, 'DER_cert_to_PEM_cert', return_value='public-cert'), \
                 patch.object(app.ssl, 'create_default_context') as tls, \
                 patch.object(app.urllib.request, 'urlopen', return_value=response) as request, \
                 patch.object(app.subprocess, 'Popen') as launch:
                launch.return_value.wait.return_value = 0
                app.open_app(config)
            helper.start.assert_called_once_with(config['serial'])
            tls.assert_called_once_with(cadata='public-cert')
            self.assertIs(request.call_args.kwargs['context'], tls.return_value)
            self.assertEqual(launch.call_args.args[0], [browser.name, '--app=' + app.URL, '--class=FlipCamera'])
            self.assertNotIn('password', str(adb.call_args))

    def test_foreign_route_never_launches_browser(self):
        helper = self.helper('conflict')
        with patch.object(app.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(RuntimeError, 'Cổng'):
                app.open_app({'helper': '/unused', 'serial': '100.115.117.61:5555'})
        helper.start.assert_not_called()
        launch.assert_not_called()

    def test_offline_retries_are_bounded_and_do_not_launch(self):
        helper = self.helper('waiting')
        with patch.object(app.time, 'sleep'), patch.object(app.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(RuntimeError, 'Offline'):
                app.open_app({'helper': '/unused', 'serial': '100.115.117.61:5555'})
        self.assertEqual(helper.ensure_link.call_count, 6)
        helper.start.assert_not_called()
        launch.assert_not_called()

    def test_browser_startup_failure_is_reported(self):
        self.helper('ready')
        with tempfile.NamedTemporaryFile() as browser:
            config = {'helper': '/unused', 'serial': '100.115.117.61:5555', 'browser': browser.name}
            response = MagicMock()
            response.__enter__.return_value.status = 200
            with patch.object(app.subprocess, 'check_output', return_value=b'public-cert'), \
                 patch.object(app.ssl, 'DER_cert_to_PEM_cert', return_value='public-cert'), \
                 patch.object(app.ssl, 'create_default_context'), \
                 patch.object(app.urllib.request, 'urlopen', return_value=response), \
                 patch.object(app.subprocess, 'Popen') as launch:
                launch.return_value.wait.return_value = 7
                with self.assertRaisesRegex(RuntimeError, '7'):
                    app.open_app(config)

    def test_desktop_exec_escapes_both_parser_layers(self):
        import subprocess
        spec = importlib.util.spec_from_file_location('camera_installer', PATH.with_name('install-camera-app.py'))
        installer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installer)
        with tempfile.TemporaryDirectory() as directory:
            entry = Path(directory) / 'fixture.desktop'
            entry.write_text('[Desktop Entry]\nType=Application\nName=Fixture\nExec=' +
                installer.quote_exec('/tmp/space Unicode ả \\ quote" dollar$ tick` percent%/app') + '\n')
            result = subprocess.run(['desktop-file-validate', str(entry)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
