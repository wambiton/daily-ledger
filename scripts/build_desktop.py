"""Build a redistributable companion without bundling any runtime account data."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
CLOUDFLARED_SHA256 = '2837888cc0f5d58f15b6dc478376de90b4d3ba5241c7947455d1e0a0df429712'

def main():
    binary = ROOT / '.tools/cloudflared.exe'
    if hashlib.sha256(binary.read_bytes()).hexdigest() != CLOUDFLARED_SHA256:
        raise RuntimeError('cloudflared 2026.9.1 checksum mismatch')
    env = os.environ.copy()
    env['PYTHONPATH'] = str(ROOT/'server')
    subprocess.run([sys.executable,'-m','PyInstaller','--noconfirm','--windowed','--onedir','--name','LedgerBackup',
        '--distpath',str(ROOT/'desktop/dist'),'--workpath',str(ROOT/'desktop/build'),'--specpath',str(ROOT/'.tools'),
        '--paths',str(ROOT/'desktop'),'--paths',str(ROOT/'server'),'--add-binary',str(binary)+';.',
        '--collect-all','django','--collect-submodules','ledger','--collect-submodules','config',
        '--exclude-module','psycopg','--exclude-module','psycopg_binary',str(ROOT/'desktop/app.py')],
        cwd=ROOT,env=env,check=True)
    for path in (ROOT/'desktop/dist/LedgerBackup').rglob('*'):
        if path.is_file() and (path.suffix in ('.db','.sqlite3','.log') or path.name in ('computer.json','smtp.json','mail-config.json','server-secret.txt')):
            raise RuntimeError('Runtime data found in distribution: '+path.name)
    print('Distribution checked: no account databases or local credentials bundled.')

if __name__ == '__main__':main()
