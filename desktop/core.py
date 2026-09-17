"""Windows-only companion configuration and stable identity; never log credentials."""
from contextlib import closing
import json
import os
import secrets
import sqlite3
import uuid
from pathlib import Path
import win32crypt


def default_home():
    return Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'PersonalLedgerBackup'


def has_accounts(folder):
    path = Path(folder) / 'accounts.sqlite3'
    if not path.exists():
        return False
    # Failure to read an existing database must never be treated as an empty one.
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        return db.execute('SELECT EXISTS(SELECT 1 FROM ledger_user)').fetchone()[0] == 1


def resolve_data_folder(explicit=None, executable=None, home=None):
    if explicit:
        return Path(explicit).resolve()
    home = Path(home or default_home()).resolve()
    selected = home / 'data-location.json'
    if selected.exists():
        folder = Path(json.loads(selected.read_text(encoding='utf-8'))['folder'])
        if not (folder / 'accounts.sqlite3').is_file():
            raise ValueError('原数据目录无法访问，请恢复目录后重试；未创建空账号库')
        return folder.resolve()
    if has_accounts(home):
        return home
    if executable:
        for parent in Path(executable).resolve().parents:
            legacy = parent / '电脑账目备份' / '账号服务'
            if (parent / '启动电脑备份服务.cmd').is_file() and has_accounts(legacy):
                return legacy.resolve()
    return home


def remember_data_folder(folder, home=None):
    save(Path(home or default_home()) / 'data-location.json', {'folder': str(Path(folder).resolve())})


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
                    'secret': protect((Path(folder)/'server-secret.txt').read_text(encoding='utf-8').strip() if (Path(folder)/'server-secret.txt').exists() else secrets.token_urlsafe(48))})
    data = json.loads(path.read_text(encoding='utf-8'))
    return {'computer_id': data['computer_id'], 'pair_key': unprotect(data['pair_key']), 'secret': unprotect(data['secret'])}


def mail_config(folder):
    path = Path(folder) / 'smtp.json'
    if not path.exists():
        legacy = Path(folder) / 'mail-config.json'
        if legacy.exists():
            data = json.loads(legacy.read_text(encoding='utf-8-sig'))
            value = win32crypt.CryptUnprotectData(bytes.fromhex(data['protectedPassword']), None, None, None, 0)[1].decode('utf-16-le')
            return data['email'], value
        return '', ''
    data = json.loads(path.read_text(encoding='utf-8'))
    return data['email'], unprotect(data['protected'])


def store_mail(folder, email, password):
    import re
    email = email.strip().lower()
    if not re.fullmatch(r'[^@\s]+@qq\.com', email) or not password.strip():
        raise ValueError('请填写QQ邮箱和SMTP授权码')
    save(Path(folder) / 'smtp.json', {'email': email, 'protected': protect(password.strip())})


def saved_mail_status(folder):
    try:
        email, password = mail_config(folder)
        return '授权码已保存，无需重复输入；尚未验证发信配置。' if email and password else '尚未配置授权码。'
    except Exception:
        return '已保存的发信配置无法读取，请重新填写并保存。'


def verify_mail(folder):
    import smtplib
    import ssl
    try:
        email, password = mail_config(folder)
        if not email or not password:return '尚未配置授权码，请先保存设置。'
        with smtplib.SMTP_SSL('smtp.qq.com',465,timeout=15,context=ssl.create_default_context()) as smtp:
            smtp.login(email,password)
        return '已保存；QQ邮箱认证通过。验证码是否收取成功仍以实际发送为准。'
    except smtplib.SMTPAuthenticationError:
        return '已保存，但QQ邮箱认证失败；请确认SMTP已开启，并更新授权码。'
    except (OSError,smtplib.SMTPException):
        return '已保存，但无法连接QQ邮箱服务器，请检查电脑网络后重试验证。'
    except Exception:
        return '发信配置验证失败，请重新检查已保存的设置。'


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
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=2)) as db:
        return db.execute('''SELECT u.email, s.created_at, s.revision, s.payload
            FROM ledger_user u LEFT JOIN ledger_backupsnapshot s ON s.id =
            (SELECT id FROM ledger_backupsnapshot WHERE user_id=u.id ORDER BY revision DESC LIMIT 1)
            ORDER BY u.email''').fetchall()
