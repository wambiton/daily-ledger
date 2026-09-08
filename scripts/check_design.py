from pathlib import Path
import json
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
reports = root / 'reports'
reports.mkdir(exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)
    page = browser.new_page(viewport={'width':1230, 'height':1700}, device_scale_factor=1)
    page.goto((root / 'docs/P0-页面视觉稿.html').as_uri())
    page.screenshot(path=str(reports / 'P0-视觉稿.png'), full_page=True)
    result = page.locator('.phone').evaluate_all('(els)=>els.map(e=>({width:e.clientWidth,overflow:e.scrollWidth>e.clientWidth,contentBottom:e.querySelector(".body").getBoundingClientRect().bottom-e.getBoundingClientRect().top,height:e.clientHeight}))')
    assert len(result) == 6
    assert all(not x['overflow'] and x['contentBottom'] <= x['height'] for x in result), result
    (reports / 'P0-视觉检查.json').write_text(json.dumps({'passed':True,'screens':result},ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS: 6 screens rendered, no horizontal overflow or content beyond frame')
    browser.close()
