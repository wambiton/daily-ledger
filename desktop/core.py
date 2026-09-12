"""Windows-only companion configuration and stable identity; never log credentials."""
import json
import os
import secrets
import sqlite3
import uuid
from pathlib import Path
import win32crypt


def protect(value):
    return win32crypt.CryptProtectData(value.encode('utf-8'), None, None, None, None, 0).hex()


def unprotect(value):
    return win32crypt.CryptUnprotectData(bytes.fromhex(value), None, None, None, 0)[1].decode('utf-8')


def save(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.pending')
    temporary.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, path)


def identity(folder):
    path = Path(folder) / 'computer.json'
    if not path.exists():
        save(path, {'computer_id': str(uuid.uuid4()), 'pair_key': protect(secrets.token_urlsafe(32)),
                    'secret': protect(secrets.token_urlsafe(48))})
    data = json.loads(path.read_text(encoding='utf-8'))
    return {'computer_id': data['computer_id'], 'pair_key': unprotect(data['pair_key']), 'secret': unprotect(data['secret'])}


def mail_config(folder):
    path = Path(folder) / 'smtp.json'
    if not path.exists():
        return '', ''
    data = json.loads(path.read_text(encoding='utf-8'))
    return data['email'], unprotect(data['protected'])


def store_mail(folder, email, password):
    import re
    email = email.strip().lower()
    if not re.fullmatch(r'[^@\s]+@qq\.com', email) or not password.strip():
        raise ValueError('请填写QQ邮箱和SMTP授权码')
    save(Path(folder) / 'smtp.json', {'email': email, 'protected': protect(password.strip())})


def pairing(url, computer):
    from urllib.parse import urlparse
    parsed = urlparse(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.path not in ('', '/') or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('外网地址无效')
    return json.dumps({'type': 'personal-ledger-pair', 'version': 1, 'server': url.rstrip('/'),
                       'computer_id': computer['computer_id'], 'pair_key': computer['pair_key']}, separators=(',', ':'))


def summaries(folder):
    path = Path(folder) / 'accounts.sqlite3'
    if not path.exists():
        return []
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=2) as db:
        return db.execute('''SELECT u.email, s.created_at, s.revision, s.payload
            FROM ledger_user u LEFT JOIN ledger_backupsnapshot s ON s.id =
            (SELECT id FROM ledger_backupsnapshot WHERE user_id=u.id ORDER BY revision DESC LIMIT 1)
            ORDER BY u.email''').fetchall()
