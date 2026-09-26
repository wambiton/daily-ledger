"""Run the device suite against an isolated synthetic-account HTTP server."""
import os
import io
import json
from datetime import timedelta
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import re
import time
import xml.etree.ElementTree as ET
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
        from ledger.models import User, EmailChallenge
        from ledger.auth_rules import code_digest
        call_command('migrate', verbosity=0)
        email = 'device-' + uuid.uuid4().hex + '@qq.com'
        User.objects.create_user(username=uuid.uuid4().hex, email=email, password='12345678', verified_at=timezone.now())
        waiting_email = 'waiting-' + uuid.uuid4().hex + '@qq.com'
        User.objects.create_user(username=uuid.uuid4().hex, email=waiting_email, password='12345678', verified_at=timezone.now())
        old_email = 'legacy-' + uuid.uuid4().hex + '@qq.com'
        User.objects.create_user(username=uuid.uuid4().hex, email=old_email, password='12345678', verified_at=timezone.now())
        register_email = 'register-' + uuid.uuid4().hex + '@qq.com'
        register_code = '654321'
        reset_email = 'reset-' + uuid.uuid4().hex + '@qq.com'
        User.objects.create_user(username=uuid.uuid4().hex,email=reset_email,password='old-12345678',verified_at=timezone.now())
        EmailChallenge.objects.create(email=reset_email,purpose='reset',code_digest=code_digest(reset_email,'reset',register_code),
            sent_at=timezone.now(),expires_at=timezone.now()+timedelta(minutes=10))
        EmailChallenge.objects.create(email=register_email, purpose='register', code_digest=code_digest(register_email, 'register', register_code),
            sent_at=timezone.now(), expires_at=timezone.now()+timedelta(minutes=10))
        connections.close_all()
        class Handler(WSGIRequestHandler):
            def log_message(self, *args):
                pass
        application = get_wsgi_application()
        waiting_failures = [1]
        def wrapped(environ, start_response):
            if environ.get('PATH_INFO') == '/api/v1/auth/code/':
                start_response('503 Service Unavailable',[('Content-Type','application/json')])
                yield json.dumps({'error':{'message':'QQ邮箱认证被拒绝，请检查电脑已保存的授权码'}}).encode()
                return
            if environ.get('PATH_INFO') == '/api/v1/auth/login/':
                body = environ['wsgi.input'].read(int(environ.get('CONTENT_LENGTH') or 0))
                environ['wsgi.input'] = io.BytesIO(body)
                if json.loads(body).get('email') == waiting_email and waiting_failures[0]:
                    waiting_failures[0] -= 1
                    start_response('503 Service Unavailable', [('Content-Type', 'application/json')])
                    yield b'{"error":{"message":"fixture temporarily offline"}}'
                    return
            try:
                yield from application(environ, start_response)
            finally:
                connections.close_all()
        server = make_server('127.0.0.1',8767,wrapped,handler_class=Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        old_uploads = []
        def legacy(environ,start_response):
            if environ.get('PATH_INFO') == '/api/v1/health/':
                start_response('200 OK',[('Content-Type','application/json')])
                yield b'{"status":"ok","database":true,"service":"personal-ledger"}'
                return
            if environ.get('PATH_INFO') == '/api/v1/backup/':
                old_uploads.append(True)
                start_response('400 Bad Request',[('Content-Type','application/json')])
                yield b'{"error":{"message":"old format rejected"}}'
                return
            yield from wrapped(environ,start_response)
        legacy_server = make_server('127.0.0.1',8770,legacy,handler_class=Handler)
        legacy_worker = threading.Thread(target=legacy_server.serve_forever,daemon=True)
        legacy_worker.start()
        adb = str(ROOT / '.tools/android-sdk/platform-tools/adb.exe')
        serial = '3B159R0037100000'
        try:
            subprocess.run([adb,'-s',serial,'reverse','tcp:8767','tcp:8767'],check=True,capture_output=True)
            subprocess.run([adb,'-s',serial,'reverse','tcp:8770','tcp:8770'],check=True,capture_output=True)
            # Some realme builds deny shell permission grants; accept the app's
            # user-authorized notification prompt through its normal system UI.
            permission_stop = threading.Event()
            def allow_notification():
                while not permission_stop.wait(1):
                    try:
                        subprocess.run([adb,'-s',serial,'shell','uiautomator','dump','/data/local/tmp/ledger-permission.xml'],capture_output=True,timeout=8)
                        raw = subprocess.run([adb,'-s',serial,'shell','cat','/data/local/tmp/ledger-permission.xml'],capture_output=True,timeout=5).stdout
                        tree = ET.fromstring(raw)
                        text = ' '.join(n.get('text','') for n in tree.iter('node'))
                        if '个人记账' not in text or not any(word in text for word in ('通知','notification')):
                            continue
                        for node in tree.iter('node'):
                            if node.get('resource-id','').endswith(':id/permission_allow_button'):
                                bounds = list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                                if len(bounds)==4:
                                    subprocess.run([adb,'-s',serial,'shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2)],capture_output=True)
                                return
                    except Exception: pass
            threading.Thread(target=allow_notification,daemon=True).start()
            result = subprocess.run([adb,'-s',serial,'shell','am','instrument','-w','-e','notClass','com.personal.ledger.ExternalBackupFlowTest','-e','backup_email',email,'-e','waiting_email',waiting_email,'-e','register_email',register_email,'-e','register_code',register_code,
                '-e','old_email',old_email,'-e','reset_email',reset_email,'com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=240)
            permission_stop.set()
            output = result.stdout.decode('utf-8',errors='replace')
            print(output)
            if result.returncode or 'OK (29 tests)' not in output or 'FAILURES' in output:
                raise RuntimeError('Device suite failed')
            assert not old_uploads, 'Legacy service must be blocked before upload'
        finally:
            subprocess.run([adb,'-s',serial,'reverse','--remove','tcp:8767'],capture_output=True)
            subprocess.run([adb,'-s',serial,'reverse','--remove','tcp:8770'],capture_output=True)
            legacy_server.shutdown(); legacy_worker.join(); legacy_server.server_close()
            server.shutdown(); worker.join(); server.server_close(); connections.close_all()

if __name__ == '__main__':
    main()
