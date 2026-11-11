#!/usr/bin/env python3
"""Open Flip Camera in the existing browser profile; never control recording."""
import importlib.util
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import time
import urllib.request

URL = 'https://localhost:18443/?live=1'


def open_app(config):
    helper = Path(config['helper'])
    spec = importlib.util.spec_from_file_location('flip_viewer_link', helper)
    link = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(link)
    serial = config['serial']
    if not link.is_tailscale_target(serial):
        raise RuntimeError('Địa chỉ Flip không hợp lệ; cài lại lối mở với serial Tailscale đúng.')
    # Reconnect only the configured private target, using the existing ownership/model guard.
    state = link.ensure_link(serial, reconnect=True)
    if state == 'waiting':
        for _ in range(5):
            time.sleep(1)
            state = link.ensure_link(serial)
            if state != 'waiting':
                break
    if state != 'ready':
        raise RuntimeError(link.MESSAGES[state] + ' Kiểm tra Tailscale và quyền ADB trên Flip.')
    link.start(serial)  # Reuse the same helper/service, never replace a foreign route.
    cert = subprocess.check_output(['adb', '-s', serial, 'exec-out', 'run-as',
                                    'com.phamdu.flipcamera.debug', 'cat',
                                    'files/viewer-tls-certificate.der'], timeout=15)
    context = ssl.create_default_context(cadata=ssl.DER_cert_to_PEM_cert(cert))
    try:
        with urllib.request.urlopen(URL, context=context, timeout=12) as response:
            if response.status != 200:
                raise RuntimeError('Trang xem camera chưa sẵn sàng.')
    except Exception as error:
        raise RuntimeError('Chưa truy cập được HTTPS trên Flip. Trong app Flip hãy kết nối camera và bật viewer HTTPS. '
                           'Lối mở không tự bật ghi hoặc bỏ kiểm tra chứng chỉ.') from error
    browser = config['browser']
    if not Path(browser).is_file():
        raise RuntimeError('Không tìm thấy trình duyệt đã cấu hình. Hãy cài lại lối mở Flip Camera.')
    # No user-data-dir/incognito/certificate switches: reuse the owner's normal profile/session.
    process = subprocess.Popen([browser, '--app=' + URL, '--class=FlipCamera'],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        result = process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        return  # Browser stays running; do not close its app window.
    if result != 0:
        raise RuntimeError('Trình duyệt chưa mở được cửa sổ Flip Camera (mã lỗi ' + str(result) + '). Hãy mở Brave bình thường rồi thử lại.')


def main():
    try:
        config = json.loads(Path(__file__).with_name('config.json').read_text())
        open_app(config)
    except Exception as error:
        message = str(error) if isinstance(error, RuntimeError) else 'Không mở được Flip Camera. Kiểm tra ADB/Tailscale hoặc cài lại lối mở.'
        print(message, file=sys.stderr)
        if shutil.which('zenity'):
            subprocess.Popen(['zenity', '--error', '--title=Flip Camera', '--text=' + message],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif shutil.which('notify-send'):
            subprocess.run(['notify-send', 'Flip Camera', message], check=False)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
