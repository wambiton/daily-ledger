"""Start the real local HTTP server, probe health, then shut it down."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
reports = root / 'reports'
reports.mkdir(exist_ok=True)
with (reports / 'P1-server.log').open('w',encoding='utf-8') as log:
    process = subprocess.Popen([sys.executable,str(root/'server/manage.py'),'runserver','127.0.0.1:8766','--noreload'],stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(30):
            if process.poll() is not None:
                raise RuntimeError('Server exited before startup')
            try:
                with opener.open('http://127.0.0.1:8766/api/v1/health/',timeout=1) as response:
                    data = json.load(response)
                assert data == {'status':'ok','database':True}, data
                (reports/'P1-server-smoke.json').write_text(json.dumps(data),encoding='utf-8')
                print('PASS: real HTTP server returned healthy database status')
                break
            except OSError:
                time.sleep(0.2)
        else:
            raise RuntimeError('Server startup timeout')
    finally:
        process.terminate()
        process.wait(timeout=10)
