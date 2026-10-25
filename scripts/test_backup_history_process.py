"""Exercise backup history across a real companion-origin restart, using synthetic data.

The isolated database is retained in reports/private for inspection. No phone,
external tunnel, SMTP message, or existing account directory is used.
"""
import copy
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from test_external_device import ROOT, seed, identity


def main():
    private = ROOT / 'reports' / 'private'
    private.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(dir=private, prefix='history-process-'))
    email = 'history-' + uuid.uuid4().hex + '@qq.com'
    seed(folder, email, False)
    computer = identity(folder)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None, token=None, paired=True):
        headers = {'Content-Type': 'application/json'}
        if paired:
            headers['X-Ledger-Pair'] = computer['pair_key']
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(f'http://127.0.0.1:{port}/api/v1/{path}',
            data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with opener.open(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def start():
        process = subprocess.Popen([sys.executable, str(ROOT / 'desktop/app.py'), '--serve',
            '--data-dir', str(folder), '--port', str(port)], cwd=ROOT,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            for _ in range(60):
                if process.poll() is not None:
                    raise RuntimeError('Synthetic companion exited before readiness')
                try:
                    status, result = call('computer/')
                    if status == 200 and result['computer_id'] == computer['computer_id']:
                        return process
                except OSError:
                    pass
                time.sleep(.25)
            raise RuntimeError('Synthetic companion readiness timeout')
        except Exception:
            process.terminate(); process.wait(timeout=10)
            raise

    def persisted():
        with sqlite3.connect(folder / 'accounts.sqlite3') as db:
            return (db.execute('SELECT user_id,revision FROM ledger_backuphead ORDER BY user_id').fetchall(),
                    db.execute('SELECT user_id,revision,digest,payload FROM ledger_backupsnapshot ORDER BY user_id,revision').fetchall())

    category = str(uuid.uuid4())
    original = {'format_version': 2, 'categories': [dict(id=category, type='expense', name='餐饮', default_key=None)],
        'entries': [dict(id=str(uuid.uuid4()), type='expense', category_id=category, amount_fen=1234,
                         date='2025-01-02', note='synthetic history', created_at=1, updated_at=2, deleted_at=None)]}
    newer = copy.deepcopy(original)
    newer['entries'][0]['amount_fen'] = 2468
    process = start()
    try:
        status, account = call('auth/login/', {'email': email, 'password': '12345678'})
        assert status == 200
        token = account['access_token']
        assert call('backup/history/', token=token, paired=False)[0] == 403
        assert call('backup/history/')[0] == 401
        for base, payload in enumerate([original, newer]):
            status, result = call('backup/', dict(operation_id=str(uuid.uuid4()), base_revision=base, payload=payload), token)
            assert status == 200 and result['revision'] == base + 1
        before = persisted()
    finally:
        process.terminate(); process.wait(timeout=10)
    process = start()
    try:
        status, account = call('auth/login/', {'email': email, 'password': '12345678'})
        assert status == 200
        token = account['access_token']
        status, page = call('backup/history/?limit=1', token=token)
        assert status == 200 and page['items'][0]['revision'] == 2 and page['next_before'] == 2
        status, page = call('backup/history/?before=2&limit=1', token=token)
        assert status == 200 and page['items'][0]['revision'] == 1 and page['next_before'] is None
        assert call('backup/download/?revision=1', token=token)[1]['payload'] == original
        assert call('backup/download/', token=token)[1]['payload'] == newer
        assert call('backup/download/?revision=999', token=token)[0] == 404
        assert before == persisted(), 'Reading history changed saved snapshots or head'
    finally:
        process.terminate(); process.wait(timeout=10)
    print('HISTORY_PROCESS_RESTART_PAGINATION_AND_NONMUTATING_READ_OK')
    print('Synthetic database retained:', folder.relative_to(ROOT))


if __name__ == '__main__':
    main()
