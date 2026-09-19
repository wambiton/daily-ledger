"""Assemble a data-free portable trial with notices and rebuildable companion sources."""
import hashlib
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
    output = ROOT / '交付包'
    output.mkdir(exist_ok=True)
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
    (output/'验收状态.txt').write_text(
        '0.6功能验证：26项JVM、20项真机回归、34项服务端、6项电脑界面及配置测试通过（2026-09-18）。\n'
        '新增30天回收站、Excel和JSON文件导出、追加导入重复选择、按账本显示备份状态。\n'
        'JSON及电脑备份包含回收站，保留原删除时间；Excel只含正常账目。手机和电脑端应一起升级。\n'
        '保存授权码后自动启动连接，显示保存/认证状态；实际保存配置的QQ邮箱认证通过，未代发真实验证码。\n'
        '修复账号目录误连、密码重置虚假成功、扫码后手机账本不可见；现有手机账本逐行核对保留。\n'
        '流量配对、登录、后台重试上传已实际成功；完整外网换手机/换电脑专项尚未通过，免费通道连接不稳定。\n'
        '本包仅供试用，不代表正式发布验收完成。\n',encoding='utf-8')
    (output/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in (archive,apk))+'\n',encoding='utf-8')
    print('Trial ZIP and APK prepared; archive integrity and private-data checks passed.')


if __name__ == '__main__':
    main()
