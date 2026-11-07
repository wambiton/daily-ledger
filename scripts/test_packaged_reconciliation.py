"""Open a synthetic Excel bill in the shipped Windows process and observe its preview."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time

import win32gui
import win32process
from openpyxl import Workbook

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--executable', type=Path, default=ROOT/'desktop/dist/LedgerBackup/LedgerBackup.exe')
    exe = parser.parse_args().executable.resolve(strict=True)
    private = ROOT/'reports/private'
    private.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(dir=private, prefix='对账窗口验收-'))
    statement = folder/'模拟微信账单.xlsx'
    ledger = folder/'模拟手机账本.json'
    book = Workbook()
    sheet = book.active
    sheet.append(['交易时间','交易对方','收/支','金额(元)','当前状态','交易单号'])
    sheet.append(['2025-01-01 08:30:00','模拟早餐','支出','¥8.50','支付成功','000012345678901234567890'])
    book.save(statement)
    ledger.write_text(json.dumps({'format_version':2,'entries':[],'categories':[]}),encoding='utf-8')
    def hashes():
        return [hashlib.sha256(path.read_bytes()).hexdigest() for path in (statement,ledger)]
    before = hashes()
    proc = subprocess.Popen([str(exe),'--smoke-test','--data-dir',str(folder/'test-account'),
                             str(statement),'--ledger-json',str(ledger)],
                            creationflags=subprocess.CREATE_NO_WINDOW)
    observed = set()
    def inspect(hwnd, unused):
        if win32process.GetWindowThreadProcessId(hwnd)[1] == proc.pid:
            observed.add(win32gui.GetWindowText(hwnd))
        return True
    deadline = time.monotonic() + 25
    try:
        while proc.poll() is None and time.monotonic() < deadline:
            win32gui.EnumWindows(inspect,None)
            time.sleep(.04)
        if proc.poll() is None:
            raise RuntimeError('Test instance failed to close')
        assert proc.returncode == 0, 'Test instance failed'
        assert '支付账单核对' in observed, 'Packaged Excel preview was never displayed'
        assert before == hashes(), 'Preview changed a source file'
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=10)
    print('PACKAGED_EXCEL_PREVIEW_AND_UNCHANGED_INPUTS_OK')
    print('Synthetic inputs retained:',folder.relative_to(ROOT))


if __name__ == '__main__':
    main()
