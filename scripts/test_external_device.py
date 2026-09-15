"""Cellular HTTPS acceptance against two disposable, independently paired computers."""
import base64
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'desktop'))
from core import identity, pairing


def seed(folder, email, register):
    program = '''
import os,sys
from pathlib import Path
from datetime import timedelta
sys.path.insert(0,str(Path.cwd()/'desktop'));from core import identity
folder=Path(sys.argv[1]);computer=identity(folder)
os.environ.update(LEDGER_DB=str(folder/'accounts.sqlite3'),DJANGO_SECRET_KEY=computer['secret'],DJANGO_SETTINGS_MODULE='config.settings')
sys.path.insert(0,str(Path.cwd()/'server'))
import django;django.setup()
from django.core.management import call_command
from django.utils import timezone
from ledger.models import User,EmailChallenge
from ledger.auth_rules import code_digest
call_command('migrate',verbosity=0)
if sys.argv[3]=='register':
 EmailChallenge.objects.create(email=sys.argv[2],purpose='register',code_digest=code_digest(sys.argv[2],'register','654321'),sent_at=timezone.now(),expires_at=timezone.now()+timedelta(minutes=30))
else:User.objects.create_user(username='synthetic',email=sys.argv[2],password='12345678',verified_at=timezone.now())
'''
    subprocess.run([sys.executable,'-c',program,str(folder),email,'register' if register else 'login'],cwd=ROOT,check=True,capture_output=True)


def main():
    processes=[]; proxy=None
    def spawn(args):
        p=subprocess.Popen(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        processes.append(p);return p
    with tempfile.TemporaryDirectory(dir=ROOT/'reports/private',prefix='external-') as temp:
        temp=Path(temp);a=temp/'computer-a';b=temp/'computer-b';a.mkdir();b.mkdir()
        email='external-'+uuid.uuid4().hex+'@qq.com'
        seed(a,email,False);seed(b,email,True)
        def origin(folder,port):
            computer=identity(folder)
            p=spawn([str(ROOT/'desktop/dist/LedgerBackup/LedgerBackup.exe'),'--serve','--data-dir',str(folder),'--port',str(port),'--diagnostic-file',str(folder/'startup.log')])
            for _ in range(40):
                if p.poll() is not None:raise RuntimeError('Packaged origin failed')
                try:
                    request=urllib.request.Request(f'http://127.0.0.1:{port}/api/v1/computer/',headers={'X-Ledger-Pair':computer['pair_key']})
                    if json.load(urllib.request.urlopen(request,timeout=1))['computer_id']==computer['computer_id']:return computer
                except Exception:time.sleep(.5)
            raise RuntimeError('Origin timeout')
        def tunnel(port):
            p=spawn([str(ROOT/'.tools/cloudflared.exe'),'tunnel','--no-autoupdate','--protocol','http2','--http-host-header','localhost','--url',f'http://127.0.0.1:{port}'])
            lines=queue.Queue()
            def drain():
                for line in p.stdout:lines.put(line.decode(errors='replace'))
            threading.Thread(target=drain,daemon=True).start()
            end=time.time()+90;url=None;errors=[]
            while time.time()<end:
                try:line=lines.get(timeout=1)
                except queue.Empty:continue
                m=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',line)
                if m:url=m.group()
                if any(word in line.lower() for word in ('error','err ','429','limit')):errors.append(re.sub(r'https://[^\s]+','[url]',line.strip()))
                if url and 'Registered tunnel connection' in line:
                    print('Temporary HTTPS tunnel connected',flush=True);return url
            raise RuntimeError('Temporary HTTPS tunnel unavailable: '+'; '.join(errors[-3:]))
        try:
            ca=origin(a,18770);cb=origin(b,18771)
            failed=[False]
            class Proxy(BaseHTTPRequestHandler):
                def handle_request(self):
                    size=int(self.headers.get('Content-Length',0));body=self.rfile.read(size)
                    if self.command=='POST' and self.path=='/api/v1/backup/' and not failed[0]:
                        failed[0]=True;result=b'{"error":{"message":"synthetic offline"}}'
                        self.send_response(503);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(result)));self.end_headers();self.wfile.write(result);return
                    destination=18771 if self.headers.get('X-Ledger-Pair')==cb['pair_key'] else 18770
                    connection=http.client.HTTPConnection('127.0.0.1',destination,timeout=30)
                    try:
                        headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','transfer-encoding')}
                        headers['Host']='localhost'
                        connection.request(self.command,self.path,body,headers)
                        response=connection.getresponse();result=response.read()
                        self.send_response(response.status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(result)));self.end_headers();self.wfile.write(result)
                    finally:connection.close()
                do_GET=handle_request
                do_POST=handle_request
                def log_message(self,*args):pass
            proxy=ThreadingHTTPServer(('127.0.0.1',18772),Proxy)
            threading.Thread(target=proxy.serve_forever,daemon=True).start()
            first=tunnel(18772);updated=first+':443';second=first
            # One test relay routes two isolated origins by their pairing capabilities.
            # Explicit HTTPS :443 exercises address-string changes without requiring
            # several simultaneous free tunnels. Different hostnames are unit-tested.
            # Validate public TLS routes before handing temporary capabilities to the test app.
            for address,computer in [(first,ca),(updated,ca),(second,cb)]:
                for attempt in range(10):
                    try:
                        request=urllib.request.Request(address+'/api/v1/computer/',headers={'X-Ledger-Pair':computer['pair_key']})
                        assert json.load(urllib.request.urlopen(request,timeout=10))['computer_id']==computer['computer_id'];break
                    except Exception:
                        if attempt==9:raise
                        time.sleep(2)
            command=[str(ROOT/'.tools/android-sdk/platform-tools/adb.exe'),'-s','3B159R0037100000','shell','am','instrument','-w','-e','class','com.personal.ledger.ExternalBackupFlowTest']
            for key,value in [('external_pair',pairing(first,ca)),('external_pair_updated',pairing(updated,ca)),('external_pair_next',pairing(second,cb))]:
                command+=['-e',key,base64.b64encode(value.encode()).decode()]
            command+=['-e','external_email',email,'-e','external_code','654321','com.personal.ledger.test/androidx.test.runner.AndroidJUnitRunner']
            print('Public TLS routes verified; starting cellular device acceptance',flush=True)
            result=subprocess.run(command,capture_output=True,timeout=420)
            output=result.stdout.decode('utf-8',errors='replace');print(output)
            if result.returncode or 'OK (1 test)' not in output or 'FAILURES' in output:raise RuntimeError('External device acceptance failed')
            from core import summaries
            for folder in [a,b]:
                rows=summaries(folder)
                assert len(rows)==1 and len(json.loads(rows[0][3])['entries'])==1
            print('TWO_COMPUTERS_ISOLATED_BACKUPS_OK')
        finally:
            for p in reversed(processes):
                if p.poll() is None:p.terminate()
                try:p.wait(timeout=5)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            if proxy:proxy.shutdown();proxy.server_close()

if __name__=='__main__':main()
