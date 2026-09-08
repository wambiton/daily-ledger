"""Provision an isolated headless Android emulator in the project."""
from bootstrap import TOOLS, fetch
import concurrent.futures
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

def install(base, manifest, key, target_dir):
    tree = ET.fromstring(urllib.request.urlopen(base+manifest,timeout=45).read())
    package = next(p for p in tree.findall('remotePackage') if p.attrib['path']==key and p.find('channelRef').attrib['ref']=='channel-0')
    archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in (None,'windows'))
    complete = archive.find('complete')
    url = complete.findtext('url')
    target = TOOLS/url
    fetch(base+url,target,complete.findtext('checksum'),'sha1')
    temp = TOOLS/('extract-'+target.stem)
    with zipfile.ZipFile(target) as z:
        z.extractall(temp)
    children = list(temp.iterdir())
    source = children[0] if len(children)==1 and children[0].is_dir() else temp
    shutil.copytree(source,TOOLS/'android-sdk'/target_dir,dirs_exist_ok=True)
    print('Installed',key,flush=True)

if __name__=='__main__':
    jobs = [
        ('https://dl.google.com/android/repository/','repository2-1.xml','emulator','emulator'),
        ('https://dl.google.com/android/repository/sys-img/android/','sys-img2-1.xml','system-images;android-30;default;x86_64','system-images/android-30/default/x86_64')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(lambda args:install(*args),jobs):
            pass
