import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DEBUG = os.environ.get('LEDGER_ENV', 'development') == 'development'
SECRET_KEY = os.environ.get('DJANGO_SECRET_KEY', 'development-only-not-for-deployment')
if not DEBUG and SECRET_KEY == 'development-only-not-for-deployment':
    raise RuntimeError('DJANGO_SECRET_KEY required in production')
ALLOWED_HOSTS = os.environ.get('LEDGER_ALLOWED_HOSTS', '127.0.0.1,localhost,testserver').split(',')
INSTALLED_APPS = ['django.contrib.auth', 'django.contrib.contenttypes', 'ledger']
MIDDLEWARE = ['django.middleware.security.SecurityMiddleware', 'django.middleware.common.CommonMiddleware']
ROOT_URLCONF = 'config.urls'
USE_TZ = True
TIME_ZONE = 'Asia/Shanghai'
DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'
AUTH_USER_MODEL = 'ledger.User'
DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': os.environ.get('LEDGER_DB', BASE_DIR / 'db.sqlite3'), 'OPTIONS': {'timeout': 20}}}
if os.environ.get('PGDATABASE'):
    DATABASES['default'] = {'ENGINE': 'django.db.backends.postgresql', 'NAME': os.environ['PGDATABASE'],
        'USER': os.environ.get('PGUSER', 'ledger'), 'PASSWORD': os.environ.get('PGPASSWORD', ''),
        'HOST': os.environ.get('PGHOST', '127.0.0.1'), 'PORT': os.environ.get('PGPORT', '5432')}
# Manual, versioned snapshots use conditional writes and work on a small local
# SQLite server. The earlier automatic multi-device sync design required PG.
EMAIL_BACKEND = 'django.core.mail.backends.smtp.EmailBackend'
EMAIL_HOST = os.environ.get('LEDGER_SMTP_HOST', 'smtp.qq.com')
EMAIL_PORT = int(os.environ.get('LEDGER_SMTP_PORT', '465'))
EMAIL_HOST_USER = os.environ.get('LEDGER_SMTP_USER', '')
EMAIL_HOST_PASSWORD = os.environ.get('LEDGER_SMTP_PASSWORD', '')
EMAIL_USE_SSL = True
EMAIL_TIMEOUT = 15
DEFAULT_FROM_EMAIL = EMAIL_HOST_USER
LEDGER_MAIL_READY = bool(EMAIL_HOST_USER and EMAIL_HOST_PASSWORD)
DATA_UPLOAD_MAX_MEMORY_SIZE = 20 * 1024 * 1024
