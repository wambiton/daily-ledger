"""Local-only server. Public HTTPS exposure is a separate deployment step."""
import os
import secrets
import sys
from pathlib import Path
from wsgiref.simple_server import make_server

ROOT = Path(__file__).resolve().parent.parent
folder = ROOT / '电脑账目备份' / '账号服务'
folder.mkdir(parents=True, exist_ok=True)
secret = folder / 'server-secret.txt'
if not secret.exists():
    with secret.open('x', encoding='utf-8') as stream:
        stream.write(secrets.token_urlsafe(48))
os.environ['DJANGO_SECRET_KEY'] = secret.read_text(encoding='utf-8').strip()
os.environ['LEDGER_ENV'] = 'local'
os.environ['LEDGER_DB'] = str(folder / 'accounts.sqlite3')
os.environ['DJANGO_SETTINGS_MODULE'] = 'config.settings'
sys.path.insert(0, str(ROOT / 'server'))
import django
django.setup()
from django.core.management import call_command
from django.core.wsgi import get_wsgi_application
from django.conf import settings
call_command('migrate', verbosity=0)
with make_server('127.0.0.1', 8765, get_wsgi_application()) as server:
    print('Local backup server: http://127.0.0.1:8765', flush=True)
    print('SMTP configured: ' + str(settings.LEDGER_MAIL_READY), flush=True)
    print('Keep this process running while using USB account backup. Ctrl+C stops it.', flush=True)
    server.serve_forever()
