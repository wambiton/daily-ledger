"""Run the device suite against an isolated synthetic-account HTTP server."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import uuid
from wsgiref.simple_server import make_server, WSGIRequestHandler

ROOT = Path(__file__).resolve().parent.parent
private = ROOT / 'reports' / 'private'
private.mkdir(exist_ok=True)

def main():
    with tempfile.TemporaryDirectory(dir=private, prefix='account-test-') as folder:
        os.environ['LEDGER_DB'] = str(Path(folder) / 'test.sqlite3')
        os.environ['DJANGO_SETTINGS_MODULE'] = 'config.settings'
        sys.path.insert(0, str(ROOT / 'server'))
        import django
        django.setup()
        from django.core.management import call_command
        from django.core.wsgi import get_wsgi_application
        from django.db import connections
        from django.utils import timezone
        from ledger.models import User
        call_command('migrate', verbosity=0)
        email = 'device-' + uuid.uuid4().hex + '@qq.com'
        User.objects.create_user(username=uuid.uuid4().hex, email=email, password='device-test-password-123', verified_at=timezone.now())
        connections.close_all()
        class Handler(WSGIRequestHandler):
            def log_message(self, *args):
                pass
        application = get_wsgi_application()
        def wrapped(environ, start_response):
            try:
                yield from application(environ, start_response)
            finally:
                connections.close_all()
        server = make_server('127.0.0.1',8767,wrapped,handler_class=Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        adb = str(ROOT / '.tools/android-sdk/platform-tools/adb.exe')
        serial = '3B159R0037100000'
        try:
            subprocess.run([adb,'-s',serial,'reverse','tcp:8767','tcp:8767'],check=True,capture_output=True)
            result = subprocess.run([adb,'-s',serial,'shell','am','instrument','-w','-e','backup_email',email,
                'com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=240)
            output = result.stdout.decode('utf-8',errors='replace')
            print(output)
            if result.returncode or 'OK (11 tests)' not in output or 'FAILURES' in output:
                raise RuntimeError('Device suite failed')
        finally:
            subprocess.run([adb,'-s',serial,'reverse','--remove','tcp:8767'],capture_output=True)
            server.shutdown(); worker.join(); server.server_close(); connections.close_all()

if __name__ == '__main__':
    main()
