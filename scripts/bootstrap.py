"""Download verified build dependencies into this workspace only."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(url, target, checksum=None, algorithm='sha256'):
    if target.exists() and (not checksum or hashlib.new(algorithm, target.read_bytes()).hexdigest() == checksum):
        return
    print('Downloading', url, flush=True)
    with urllib.request.urlopen(url, timeout=120) as src, target.open('wb') as dst:
        shutil.copyfileobj(src, dst)
    if checksum and hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + str(target))

def sdk():
    base = 'https://dl.google.com/android/repository/'
    manifest = urllib.request.urlopen(base + 'repository2-1.xml', timeout=45).read()
    (TOOLS / 'android-repository.xml').write_bytes(manifest)
    tree = ET.fromstring(manifest)
    wanted = {'platforms;android-35': 'platforms/android-35', 'build-tools;35.0.0': 'build-tools/35.0.0', 'platform-tools': 'platform-tools'}
    for package in tree.findall('remotePackage'):
        key = package.attrib['path']
        if key not in wanted:
            continue
        if '-ext' in package.findtext('display-name', ''):
            continue
        destination = TOOLS / 'android-sdk' / wanted[key]
        properties = destination / 'source.properties'
        if properties.exists() and 'AndroidVersion.IsBaseSdk=false' not in properties.read_text():
            continue
        archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in (None, 'windows'))
        complete = archive.find('complete')
        url = complete.findtext('url')
        checksum = complete.findtext('checksum')
        target = TOOLS / Path(url).name
        fetch(base + url, target, checksum, 'sha1')
        if target.stat().st_size != int(complete.findtext('size')):
            raise RuntimeError('Archive size mismatch')
        temp = TOOLS / ('extract-' + target.stem)
        with zipfile.ZipFile(target) as z:
            z.extractall(temp)
        children = list(temp.iterdir())
        content = children[0] if len(children) == 1 and children[0].is_dir() else temp
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(content, destination, dirs_exist_ok=True)
        generated_metadata = destination / 'package.xml'
        if generated_metadata.exists():
            generated_metadata.unlink()
        print('Installed', key, flush=True)

def gradle():
    base = 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip'
    sha = urllib.request.urlopen(base + '.sha256', timeout=45).read().decode().strip()
    target = TOOLS / 'gradle-8.11.1-bin.zip'
    fetch(base, target, sha)
    if not (TOOLS / 'gradle-8.11.1/bin/gradle.bat').exists():
        with zipfile.ZipFile(target) as z:
            z.extractall(TOOLS)
    print('Installed Gradle 8.11.1', flush=True)

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(lambda f: f(), [sdk, gradle]):
            pass
    print('Bootstrap complete', flush=True)
