#!/usr/bin/env python3
"""Install/remove the per-user Linux Flip Camera app shortcut without sudo."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HOME = Path.home()
DATA = Path(os.environ.get('XDG_DATA_HOME', HOME / '.local/share'))
APP = DATA / 'flip-camera'
ENTRY = DATA / 'applications/flip-camera.desktop'
BIN = HOME / '.local/bin/flip-camera'


def desktop_value(value):
    return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')


def quote_exec(value):
    # Exec quoting is parsed after the Desktop Entry string-escape layer.
    inner = str(value).replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$').replace('%', '%%')
    return '"' + desktop_value(inner) + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='100.115.117.61:5555')
    parser.add_argument('--browser')
    parser.add_argument('--uninstall', action='store_true')
    args = parser.parse_args()
    if args.uninstall:
        manifest = APP / 'installed-files.json'
        if manifest.exists():
            # Remove only paths this installer recorded, never browser data or viewer routes.
            for name in json.loads(manifest.read_text()):
                path = Path(name)
                if path == APP / 'viewer-link.py':
                    continue  # Existing user service may still need its executable after uninstall.
                if path.is_file() or path.is_symlink():
                    path.unlink()
            manifest.unlink(missing_ok=True)
            try:
                APP.rmdir()
            except OSError:
                pass
        print('Đã gỡ lối mở Flip Camera; giữ camera, video, profile trình duyệt và đường xem (helper viewer-link.py được giữ cho dịch vụ đang chạy).')
        return
    browser = args.browser or next((p for p in [shutil.which('brave-browser'), '/snap/bin/brave',
                        shutil.which('google-chrome'), shutil.which('chromium')] if p and Path(p).is_file()), None)
    if not browser:
        raise SystemExit('Cần Brave/Chrome/Chromium có sẵn; không tự cài trình duyệt hoặc tạo profile mới.')
    APP.mkdir(parents=True, exist_ok=True)
    ENTRY.parent.mkdir(parents=True, exist_ok=True)
    launch = APP / 'launch.py'
    icon = APP / 'flip-camera.svg'
    config = APP / 'config.json'
    helper = APP / 'viewer-link.py'
    helper_source = (ROOT / 'scripts/viewer-link.py').read_text()
    helper_source = helper_source.replace('KNOWN_HELPERS = {SCRIPT, INSTALLED_HELPER.resolve()}',
        'KNOWN_HELPERS = {SCRIPT, INSTALLED_HELPER.resolve(), Path(' + repr(str(ROOT / 'scripts/viewer-link.py')) + ').resolve()}')
    helper.write_text(helper_source)
    BIN.parent.mkdir(parents=True, exist_ok=True)
    BIN.write_text('#!' + sys.executable + '\nimport os\nos.execv(' + repr(sys.executable) + ', [' + repr(sys.executable) + ', ' + repr(str(launch)) + '])\n')
    BIN.chmod(0o755)
    shutil.copyfile(ROOT / 'scripts/flip-camera-app.py', launch)
    shutil.copyfile(ROOT / 'viewer/assets/favicon.svg', icon)
    config.write_text(json.dumps({'serial': args.serial, 'browser': browser,
                                 'helper': str(helper)}, indent=2))
    launch.chmod(0o755)
    config.chmod(0o600)
    entry = '\n'.join(['[Desktop Entry]', 'Version=1.0', 'Type=Application', 'Name=Flip Camera',
        'Comment=Xem camera siêu rộng trên Flip3', 'Exec=' + quote_exec(BIN),
        'Icon=' + desktop_value(icon), 'Terminal=false', 'Categories=AudioVideo;Video;',
        'StartupWMClass=FlipCamera', 'StartupNotify=true', ''])
    ENTRY.write_text(entry)
    ENTRY.chmod(0o755)
    installed = [launch, icon, config, helper, BIN, ENTRY]
    if shutil.which('xdg-user-dir'):
        desktop = Path(subprocess.check_output(['xdg-user-dir', 'DESKTOP'], text=True).strip())
        if desktop != HOME and desktop.is_dir():
            shortcut = desktop / ENTRY.name
            shortcut.write_text(entry)
            shortcut.chmod(0o755)
            installed.append(shortcut)
            if shutil.which('gio'):
                subprocess.run(['gio', 'set', str(shortcut), 'metadata::trusted', 'true'], capture_output=True)
    (APP / 'installed-files.json').write_text(json.dumps([str(p) for p in installed], indent=2))
    if shutil.which('update-desktop-database'):
        subprocess.run(['update-desktop-database', str(ENTRY.parent)], capture_output=True)
    print('Đã cài Flip Camera vào Applications và desktop nếu được hỗ trợ.')
    print('Manifest:', APP / 'installed-files.json')


if __name__ == '__main__':
    main()
