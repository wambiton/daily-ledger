"""Run registration/import and password-reset UI against retained synthetic accounts.

Verification challenges are seeded fixtures; this script never sends email.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import uuid
from datetime import timedelta
from wsgiref.simple_server import make_server, WSGIRequestHandler

ROOT = Path(__file__).resolve().parent.parent


def main():
    folder = Path(tempfile.mkdtemp(prefix='auth-device-', dir=ROOT / 'reports/private'))
    os.environ.update(DJANGO_SETTINGS_MODULE='config.settings', LEDGER_DB=str(folder / 'accounts.sqlite3'),
                      LEDGER_SMTP_USER='', LEDGER_SMTP_PASSWORD='', LEDGER_COMPUTER_ID='', LEDGER_PAIR_KEY='')
    sys.path.insert(0, str(ROOT / 'server'))
    import django
    django.setup()
    from django.core.management import call_command
    from django.core.wsgi import get_wsgi_application
    from django.db import connections
    from django.utils import timezone
    from ledger.models import User, EmailChallenge
    from ledger.auth_rules import code_digest
    call_command('migrate', verbosity=0)
    register_email = f'register-{uuid.uuid4().hex}@qq.com'
    reset_email = f'reset-{uuid.uuid4().hex}@qq.com'
    reset_user = User.objects.create_user(username=uuid.uuid4().hex, email=reset_email,
        password='old-12345678', verified_at=timezone.now())
    for email, purpose in [(register_email, 'register'), (reset_email, 'reset')]:
        EmailChallenge.objects.create(email=email, purpose=purpose,
            code_digest=code_digest(email, purpose, '654321'), sent_at=timezone.now(),
            expires_at=timezone.now()+timedelta(minutes=10))
    connections.close_all()

    class QuietHandler(WSGIRequestHandler):
        def log_message(self, *unused):
            pass

    server = make_server('127.0.0.1', 8767, get_wsgi_application(), handler_class=QuietHandler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    adb = [str(ROOT / '.tools/android-sdk/platform-tools/adb.exe'), '-s', '3B159R0037100000']
    tests = ','.join('com.personal.ledger.AccountBackupFlowTest#'+name for name in [
        'resetRequiresMatchingPasswordsAndNewPasswordLogsIn',
        'registrationStaysEmptyUntilPreviewSelectionAndDuplicateChoice'])
    try:
        subprocess.run(adb+['reverse', 'tcp:8767', 'tcp:8767'], check=True, capture_output=True)
        result = subprocess.run(adb+['shell','am','instrument','-w','-e','class',tests,
            '-e','register_email',register_email,'-e','register_code','654321','-e','reset_email',reset_email,
            'com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=240)
        output = result.stdout.decode('utf-8', errors='replace')
        (folder/'device-result.txt').write_text(output, encoding='utf-8')
        print(output)
        if result.returncode or 'OK (2 tests)' not in output or 'FAILURES' in output:
            raise RuntimeError('Auth device acceptance failed; fixtures retained')
        assert User.objects.count() == 2
        reset_user.refresh_from_db()
        assert reset_user.check_password('new-12345678') and not reset_user.check_password('old-12345678')
        assert User.objects.get(email=register_email).check_password('12345678')
        assert EmailChallenge.objects.filter(consumed_at__isnull=False).count() == 2
        print('AUTH_UI_AND_PERSISTED_STATE_OK', folder.relative_to(ROOT))
    finally:
        subprocess.run(adb+['reverse','--remove','tcp:8767'], capture_output=True)
        server.shutdown()
        worker.join()
        server.server_close()
        connections.close_all()


if __name__ == '__main__':
    main()
