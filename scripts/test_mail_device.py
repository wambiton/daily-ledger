"""Explicitly opted-in real SMTP acceptance from isolated phone account UI.

Sends two verification emails. Run only with the recipient's permission.
Retains the isolated database; never reads or changes production account rows.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from wsgiref.simple_server import make_server, WSGIRequestHandler

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recipient', required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(ROOT / 'desktop'))
    from core import resolve_data_folder, mail_config
    email, password = mail_config(resolve_data_folder(executable=ROOT / 'desktop/dist/LedgerBackup/LedgerBackup.exe'))
    if args.recipient != email or not password:
        raise RuntimeError('This acceptance only supports the configured sender as recipient')
    folder = Path(tempfile.mkdtemp(prefix='mail-device-', dir=ROOT / 'reports/private'))
    os.environ.update(DJANGO_SETTINGS_MODULE='config.settings', LEDGER_DB=str(folder / 'accounts.sqlite3'),
                      LEDGER_SMTP_USER=email, LEDGER_SMTP_PASSWORD=password)
    sys.path.insert(0, str(ROOT / 'server'))
    import django
    django.setup()
    from django.core.management import call_command
    from django.core.wsgi import get_wsgi_application
    from django.db import connections
    from ledger.models import EmailChallenge, User
    call_command('migrate', verbosity=0)
    connections.close_all()

    class QuietHandler(WSGIRequestHandler):
        def log_message(self, *unused):
            pass

    server = make_server('127.0.0.1', 0, get_wsgi_application(), handler_class=QuietHandler)
    port = server.server_port
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    adb = [str(ROOT / '.tools/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
    try:
        subprocess.run(adb + ['reverse', f'tcp:{port}', f'tcp:{port}'], check=True, capture_output=True)
        result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
            'com.personal.ledger.AccountBackupFlowTest#realMailRequestsFromRegisterAndResetScreens',
            '-e', 'mail_recipient', email, '-e', 'mail_server', f'http://127.0.0.1:{port}',
            'com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=180)
        output = result.stdout.decode('utf-8', errors='replace')
        (folder / 'device-result.txt').write_text(output, encoding='utf-8')
        print(output)
        if result.returncode or 'OK (1 test)' not in output or 'FAILURES' in output:
            raise RuntimeError('Phone mail acceptance failed; do not automatically resend')
        assert set(EmailChallenge.objects.values_list('purpose', flat=True)) == {'register', 'reset'}
        assert User.objects.count() == 0, 'Acceptance must not create or reset an account'
        print('PHONE_SMTP_TWO_REQUESTS_ACCEPTED; actual inbox receipt still requires confirmation')
    finally:
        subprocess.run(adb + ['reverse', '--remove', f'tcp:{port}'], capture_output=True)
        server.shutdown()
        worker.join()
        server.server_close()
        connections.close_all()


if __name__ == '__main__':
    main()
