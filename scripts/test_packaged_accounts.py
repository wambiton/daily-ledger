"""Verify packaged account/password persistence across a real process restart.

Uses a new synthetic database and keeps its records intact throughout assertions.
"""
import hashlib
import hmac
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timedelta, timezone
from test_external_device import seed, identity, ROOT


def main():
    executable=ROOT/'desktop/dist/LedgerBackup/LedgerBackup.exe'
    with tempfile.TemporaryDirectory(dir=ROOT/'reports/private',prefix='persistence-') as temp:
        folder=Path(temp);email='persistence-'+uuid.uuid4().hex+'@qq.com'
        seed(folder,email,False)
        computer=identity(folder)
        digest=hmac.new(computer['secret'].encode(),(email+'\0reset\0'+'654321').encode(),hashlib.sha256).hexdigest()
        now=datetime.now(timezone.utc)
        db=sqlite3.connect(folder/'accounts.sqlite3')
        try:
            db.execute('INSERT INTO ledger_emailchallenge(email,purpose,code_digest,expires_at,sent_at,attempts,consumed_at) VALUES (?,?,?,?,?,0,NULL)',
                (email,'reset',digest,(now+timedelta(minutes=10)).isoformat(),now.isoformat()))
            db.commit()
        finally:db.close()
        def call(path,body=None,token=None):
            headers={'X-Ledger-Pair':computer['pair_key'],'Content-Type':'application/json'}
            if token:headers['Authorization']='Bearer '+token
            request=urllib.request.Request('http://127.0.0.1:18779/api/v1/'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
            try:
                with urllib.request.urlopen(request,timeout=5) as response:return response.status,json.load(response)
            except urllib.error.HTTPError as error:return error.code,json.load(error)
        def start():
            process=subprocess.Popen([str(executable),'--serve','--data-dir',str(folder),'--port','18779'],creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                for _ in range(40):
                    if process.poll() is not None:raise RuntimeError('Packaged server exited')
                    try:
                        if call('computer/')[0]==200:return process
                    except OSError:pass
                    time.sleep(.25)
                raise RuntimeError('Packaged server not ready')
            except Exception:process.terminate();process.wait(timeout=10);raise
        process=start()
        try:
            status,account=call('auth/login/',{'email':email,'password':'12345678'});assert status==200
            category=str(uuid.uuid4());entry=str(uuid.uuid4())
            payload={'format_version':2,'categories':[{'id':category,'type':'expense','name':'餐饮','default_key':None}],
                'entries':[{'id':entry,'type':'expense','category_id':category,'amount_fen':1234,'date':'2026-09-15','note':'synthetic','created_at':1,'updated_at':2,'deleted_at':None}]}
            payload['entries'].append({**payload['entries'][0], 'id':str(uuid.uuid4()), 'note':'synthetic trash', 'deleted_at':2})
            assert call('backup/',{'operation_id':str(uuid.uuid4()),'base_revision':0,'payload':payload},account['access_token'])[0]==200
            status,result=call('auth/reset/',{'email':email,'password':'abcdefgh','code':'654321'})
            assert status==200 and result['status']=='password_reset'
        finally:process.terminate();process.wait(timeout=10)
        process=start()
        try:
            assert call('auth/login/',{'email':email,'password':'12345678'})[0]==401
            status,reopened=call('auth/login/',{'email':email,'password':'abcdefgh'});assert status==200
            assert reopened['user']['id']==account['user']['id']
            status,snapshot=call('backup/download/',token=reopened['access_token'])
            assert status==200 and snapshot['payload']==payload
            assert snapshot['entry_count']==1 and snapshot['trash_count']==1
        finally:process.terminate();process.wait(timeout=10)
        subprocess.run([str(executable),'--data-dir',str(folder),'--smoke-test'],check=True,timeout=20,creationflags=subprocess.CREATE_NO_WINDOW)
    print('PACKAGED_RESET_RESTART_LOGIN_AND_UNCHANGED_BACKUP_OK; GUI_SMOKE_OK')


if __name__=='__main__':main()
