import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DEBUG = os.environ.get('LEDGER_ENV', 'development') == 'development'
SECRET_KEY = os.environ.get('DJANGO_SECRET_KEY', 'development-only-not-for-deployment')
if not DEBUG and SECRET_KEY == 'development-only-not-for-deployment':
    raise RuntimeError('DJANGO_SECRET_KEY required in production')
ALLOWED_HOSTS = ['127.0.0.1', 'localhost', 'testserver']
INSTALLED_APPS = ['django.contrib.auth', 'django.contrib.contenttypes', 'ledger']
MIDDLEWARE = ['django.middleware.security.SecurityMiddleware', 'django.middleware.common.CommonMiddleware']
ROOT_URLCONF = 'config.urls'
USE_TZ = True
TIME_ZONE = 'Asia/Shanghai'
DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'
AUTH_USER_MODEL = 'ledger.User'
DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': BASE_DIR / 'db.sqlite3'}}
if os.environ.get('PGDATABASE'):
    DATABASES['default'] = {'ENGINE': 'django.db.backends.postgresql', 'NAME': os.environ['PGDATABASE'],
        'USER': os.environ.get('PGUSER', 'ledger'), 'PASSWORD': os.environ.get('PGPASSWORD', ''),
        'HOST': os.environ.get('PGHOST', '127.0.0.1'), 'PORT': os.environ.get('PGPORT', '5432')}
if not DEBUG and DATABASES['default']['ENGINE'].endswith('sqlite3'):
    raise RuntimeError('Production requires PostgreSQL')
EMAIL_BACKEND = 'django.core.mail.backends.locmem.EmailBackend'
