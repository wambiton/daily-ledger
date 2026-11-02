"""USB-loopback mobile -> packaged EXE v5 integration. Retains isolated fixtures."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from test_external_device import ROOT, seed, identity


def main():
    folder=Path(tempfile.mkdtemp(dir=ROOT/'reports/private',prefix='packaged-rules-device-'))
    email='rules-'+uuid.uuid4().hex+'@qq.com'
    other='other-'+uuid.uuid4().hex+'@qq.com'
    seed(folder,email,False)
    computer=identity(folder)
    program="""
import os,sys
from pathlib import Path
sys.path.insert(0,str(Path.cwd()/'desktop'));from core import identity
folder=Path(sys.argv[1]);c=identity(folder)
os.environ.update(LEDGER_DB=str(folder/'accounts.sqlite3'),DJANGO_SECRET_KEY=c['secret'],DJANGO_SETTINGS_MODULE='config.settings')
sys.path.insert(0,str(Path.cwd()/'server'))
import django;django.setup()
from django.utils import timezone
from ledger.models import User
User.objects.create_user(username='isolated-other',email=sys.argv[2],password='12345678',verified_at=timezone.now())
"""
    subprocess.run([sys.executable,'-c',program,str(folder),other],cwd=ROOT,check=True,capture_output=True)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    exe=ROOT/'desktop/dist/LedgerBackup/LedgerBackup.exe'
    process=subprocess.Popen([str(exe),'--serve','--data-dir',str(folder),'--port',str(port)],cwd=ROOT,creationflags=subprocess.CREATE_NO_WINDOW,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    adb=str(ROOT/'.tools/android-sdk/platform-tools/adb.exe')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    forwarded=False
    try:
        for _ in range(60):
            if process.poll() is not None:raise RuntimeError('Packaged service exited')
            try:
                req=urllib.request.Request(f'http://127.0.0.1:{port}/api/v1/health/',headers={'X-Ledger-Pair':computer['pair_key']})
                with opener.open(req,timeout=2) as response:health=json.load(response)
                assert 5 in health['backup_formats'];break
            except OSError:time.sleep(.25)
        else:raise RuntimeError('Packaged service readiness timeout')
        subprocess.run([adb,'reverse',f'tcp:{port}',f'tcp:{port}'],check=True,capture_output=True);forwarded=True
        command=[adb,'shell','am','instrument','-w','-e','class','com.personal.ledger.PackagedRulesBackupTest',
            '-e','packaged_server',f'http://127.0.0.1:{port}','-e','packaged_pair',computer['pair_key'],
            '-e','packaged_email',email,'-e','packaged_other_email',other,
            'com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner']
        result=subprocess.run(command,capture_output=True,timeout=180)
        output=result.stdout.decode('utf-8',errors='replace')
        (folder/'device-result.txt').write_text(output,encoding='utf-8')
        print(output)
        if result.returncode or 'OK (1 test)' not in output or 'FAILURES' in output:raise RuntimeError('Packaged rules device test failed')
        print('PHONE_TO_PACKAGED_V5_HISTORY_TRUST_AND_ACCOUNT_ISOLATION_OK')
        print('Retained fixture:',folder.relative_to(ROOT))
    finally:
        if forwarded:subprocess.run([adb,'reverse','--remove',f'tcp:{port}'],capture_output=True)
        if process.poll() is None:process.terminate()
        process.wait(timeout=10)


if __name__=='__main__':main()
