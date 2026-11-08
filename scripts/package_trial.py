"""Assemble a data-free portable trial with notices and rebuildable companion sources."""
import hashlib
from datetime import datetime
import json
import re
from importlib.metadata import distributions
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    version=re.search(r'versionName = "([^"]+)"',(ROOT/'android/app/build.gradle.kts').read_text(encoding='utf-8')).group(1).split('-')[0]
    output = ROOT / '交付包' / (f'个人记账-{version}-联调试用-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    output.mkdir(parents=True, exist_ok=False)
    bundle = ROOT / 'desktop/dist/LedgerBackup'
    required = [bundle/'LedgerBackup.exe', ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',
                ROOT/'.tools/distribution-sources/cloudflared-LICENSE',
                ROOT/'.tools/distribution-sources/pystray-0.19.5-source.zip']
    gui_required = [bundle/'_internal/_tcl_data/init.tcl', bundle/'_internal/_tk_data/tk.tcl',
                    bundle/'_internal/tcl86t.dll', bundle/'_internal/tk86t.dll']
    for path in required + gui_required:
        if not path.is_file():
            raise RuntimeError('Missing distribution input: '+str(path.relative_to(ROOT)))
    with tempfile.TemporaryDirectory(dir=output, prefix='staging-') as temp:
        stage = Path(temp)/'LedgerBackup'
        shutil.copytree(bundle, stage)
        shutil.copy2(ROOT/'docs/电脑端外网备份使用说明.md',stage/'使用说明.md')
        shutil.copy2(ROOT/'docs/联调试用包说明.md',stage/'联调试用包说明.md')
        shutil.copy2(ROOT/'docs/备份历史与独立恢复.md',stage/'备份历史与独立恢复.md')
        shutil.copy2(ROOT/'docs/银行短信与支付宝自动记账.md',stage/'自动记账与账单核对.md')
        notices = stage/'ThirdPartyNotices'
        notices.mkdir()
        for distribution in distributions():
            name = distribution.metadata['Name']
            if name.lower() in ('pip','psycopg','psycopg-binary'):
                continue
            for file in distribution.files or []:
                if '.dist-info/' in str(file) and any(word in str(file).lower() for word in ('license','copying','authors')):
                    target = notices/name/Path(str(file)).name
                    # Preserve separate same-named notices from subpackages.
                    target = target.with_name(str(file).replace('/','_').replace('\\','_'))
                    target.parent.mkdir(parents=True,exist_ok=True)
                    shutil.copy2(distribution.locate_file(file),target)
        python_license = Path(sys.base_prefix)/'LICENSE.txt'
        if not python_license.is_file():
            raise RuntimeError('Python runtime license missing')
        shutil.copy2(python_license,notices/'Python-LICENSE.txt')
        shutil.copy2(required[2],notices/'cloudflared-LICENSE.txt')
        sources = stage/'Source';sources.mkdir()
        shutil.copy2(required[3],sources/'pystray-0.19.5-source.zip')
        tracked = subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'ls-files','-z'],cwd=ROOT).decode().split('\0')
        for relative in tracked:
            path = Path(relative)
            if path.parts and path.parts[0] in ('desktop','server') and path.suffix in ('.py','.txt'):
                destination = sources/path
                destination.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(ROOT/path,destination)
        (sources/'scripts').mkdir(exist_ok=True)
        shutil.copy2(ROOT/'scripts/build_desktop.py',sources/'scripts/build_desktop.py')
        (sources/'README.txt').write_text(
            'Companion source and pystray source are included for rebuilding/replacing components.\n'
            'Use Python 3.13 on Windows; create a venv and pip install -r desktop/requirements.txt.\n'
            'Download cloudflared 2026.9.1 windows-amd64.exe into .tools/cloudflared.exe.\n'
            'Run python scripts/build_desktop.py; the script checks the executable SHA256.\n'
            'Replace a dependency with your modified version before rebuilding.\n'
            'Third-party components retain their own licenses in ThirdPartyNotices.\n',encoding='utf-8')
        for path in stage.rglob('*'):
            tcl_encoding = path.parent == stage/'_internal/_tcl_data/encoding'
            if path.is_file() and (path.suffix.lower() in ('.db','.sqlite3','.log','.keystore','.jks') or (path.suffix.lower() == '.enc' and not tcl_encoding) or path.name in ('computer.json','smtp.json','mail-config.json','server-secret.txt')):
                raise RuntimeError('Runtime or private data found: '+str(path.relative_to(stage)))
        archive = output/f'个人记账电脑端-{version}-试用.zip'
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as zipped:
            for path in stage.rglob('*'):
                if path.is_file():zipped.write(path,path.relative_to(stage.parent))
        with zipfile.ZipFile(archive) as zipped:
            if zipped.testzip() is not None:raise RuntimeError('Archive integrity failed')
    apk=output/f'个人记账-{version}-试用.apk'
    shutil.copy2(required[1],apk)
    shutil.copy2(ROOT/'docs/电脑端外网备份使用说明.md',output/'使用说明.md')
    shutil.copy2(ROOT/'docs/联调试用包说明.md', output/'先读我.md')
    shutil.copy2(ROOT/'docs/备份历史与独立恢复.md', output/'备份历史与独立恢复.md')
    shutil.copy2(ROOT/'docs/银行短信与支付宝自动记账.md', output/'自动记账与账单核对.md')
    (output/'验收状态.txt').write_text(
        '本次为0.7.14试用构建，不代表正式版全场景验收完成。\n'
        '电脑实际EXE：v2/v5历史、非法规则拒绝、重启登录及改密测试通过；41项服务端回归通过。\n'
        '手机到实际EXE：两版v5上传、历史独立恢复、规则/确认状态及账号隔离专项1项通过。\n'
        '2026-11-08手机115项JVM与Lint通过，导入汇总2项真机通过；比较预算搜索主题通知13项通过。\n'
        '当前手机到实际EXE的v5历史恢复与账号隔离1项、自动记账流程5项已重新通过。\n'
        '电脑支持微信/支付宝CSV与XLSX核对及补记JSON导出，手机支持预览、追加和重复选择；真实平台样本仍待验证。\n'
        '联调使用USB本机转发和隔离模拟账号，不代表公网、扫码UI、真实SMTP或多机型重新验收。\n'
        '手机与电脑统一为0.7.14，电脑支持备份格式1至5。\n', encoding='utf-8')
    revision = subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    manifest = {'source_revision': revision, 'phone_version': version,
                'desktop_internal_version': '0.7.14', 'backup_formats': [1,2,3,4,5],
                'desktop_exe_sha256': hashlib.sha256(required[0].read_bytes()).hexdigest(),
                'distribution': 'local-integration-trial', 'built_at': datetime.now().isoformat()}
    (output/'构建信息.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    files = sorted(p for p in output.iterdir() if p.is_file())
    (output/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in files)+'\n',encoding='utf-8')
    print('Output: '+str(output))
    print('Trial ZIP and APK prepared; archive integrity and private-data checks passed.')


if __name__ == '__main__':
    main()
