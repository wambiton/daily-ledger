"""Validation and one-way credential fingerprints shared by auth endpoints."""
import hashlib
import hmac
import re
import secrets

from django.conf import settings
from django.core.exceptions import ValidationError
from django.core.validators import validate_email


def username(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-zA-Z0-9_]{4,32}', value):
        raise ValueError('用户名须为4至32位字母、数字或下划线')
    return value.lower()


def qq_email(value):
    if not isinstance(value, str):
        raise ValueError('请输入QQ邮箱')
    value = value.strip().lower()
    try:
        validate_email(value)
    except ValidationError:
        raise ValueError('请输入有效的QQ邮箱') from None
    if value.rsplit('@', 1)[-1] != 'qq.com':
        raise ValueError('第一代仅支持QQ邮箱')
    return value


def password(value):
    # Preserve spaces and Unicode; never silently trim a password.
    if not isinstance(value, str) or not 12 <= len(value) <= 128:
        raise ValueError('密码须为12至128个字符')
    return value


def new_token():
    return secrets.token_urlsafe(32)


def token_digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def code_digest(email, purpose, code):
    # A keyed fingerprint prevents offline guessing of all six-digit codes
    # after a database-only leak; bind codes to both email and purpose.
    message = f'{email}\0{purpose}\0{code}'.encode('utf-8')
    return hmac.new(settings.SECRET_KEY.encode('utf-8'), message, hashlib.sha256).hexdigest()
