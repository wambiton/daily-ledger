"""USB backup for the current debuggable app; never overwrites phone data."""
import argparse
from contextlib import closing
from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import tempfile
import uuid

PACKAGE = 'com.personal.ledger'
ROOT = Path(__file__).resolve().parent.parent


def snapshot(source, destination):
    with closing(sqlite3.connect(source)) as src, closing(sqlite3.connect(destination)) as dst:
        src.backup(dst)
        if dst.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise RuntimeError('备份数据库完整性检查失败')
        if dst.execute('PRAGMA foreign_key_check').fetchall():
            raise RuntimeError('备份数据库关联检查失败')
        tables = {r[0] for r in dst.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {'entries', 'categories', 'outbox', 'sync_state'} <= tables:
            raise RuntimeError('不是有效的记账数据库')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(ROOT / '.tools/android-sdk/platform-tools/adb.exe'))
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()

    def adb(*parts):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError('ADB操作失败：' + result.stderr.decode('utf-8', errors='replace'))
        return result.stdout

    adb('get-state')
    adb('shell', 'run-as', PACKAGE, 'test', '-f', 'databases/ledger.db')
    folder = ROOT / '电脑账目备份'
    folder.mkdir(exist_ok=True)
    backup = folder / (datetime.now().strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:8])
    # Stop writes while copying DB and WAL together. The app is reopened afterwards.
    adb('shell', 'am', 'force-stop', PACKAGE)
    try:
        names = adb('shell', 'run-as', PACKAGE, 'ls', 'databases').decode().split()
        files = ['databases/' + name for name in ['ledger.db', 'ledger.db-wal', 'ledger.db-shm'] if name in names]
        if 'databases/ledger.db' not in files:
            raise RuntimeError('未找到手机账目数据库')
        archive = adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', *files)
        with tempfile.TemporaryDirectory(dir=folder, prefix='.working-') as temp:
            temp = Path(temp)
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                for member in tar.getmembers():
                    if member.name not in files or not member.isfile():
                        raise RuntimeError('备份文件列表异常')
                    (temp / Path(member.name).name).write_bytes(tar.extractfile(member).read())
            output = temp / 'ledger.sqlite3'
            snapshot(temp / 'ledger.db', output)
            metadata = {'format_version': 1, 'created_at': datetime.now().astimezone().isoformat(),
                        'package': PACKAGE, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
                        'integrity_check': 'ok', 'includes': ['categories', 'entries', 'outbox', 'sync_state']}
            # Publish only after SQLite has merged the WAL into a verified standalone file.
            backup.mkdir()
            output.replace(backup / 'ledger.sqlite3')
            (backup / 'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
        print('备份成功，完整性及关联检查通过：' + str(backup))
    finally:
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')


if __name__ == '__main__':
    main()
