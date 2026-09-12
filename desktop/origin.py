"""Private loopback origin for the packaged Windows companion."""
import os
import sys
from pathlib import Path
from core import identity, mail_config


def run(folder, port):
    folder = Path(folder).resolve()
    computer = identity(folder)
    email, password = mail_config(folder)
    os.environ.update(LEDGER_ENV='companion', LEDGER_DB=str(folder/'accounts.sqlite3'),
        DJANGO_SECRET_KEY=computer['secret'], LEDGER_COMPUTER_ID=computer['computer_id'],
        LEDGER_PAIR_KEY=computer['pair_key'], LEDGER_SMTP_USER=email, LEDGER_SMTP_PASSWORD=password,
        LEDGER_ALLOWED_HOSTS='localhost,127.0.0.1', DJANGO_SETTINGS_MODULE='config.settings')
    root = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parent.parent))
    sys.path.insert(0, str(root/'server'))
    import django
    django.setup()
    from django.core.management import call_command
    from django.core.wsgi import get_wsgi_application
    from waitress import serve
    call_command('migrate', verbosity=0)
    serve(get_wsgi_application(), host='127.0.0.1', port=port, threads=4,
          max_request_body_size=20*1024*1024, channel_timeout=60, clear_untrusted_proxy_headers=True)
