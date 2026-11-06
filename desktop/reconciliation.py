"""Read-only comparison of an Alipay CSV and a mobile ledger export."""
import csv
import io
import json
import re
from collections import Counter
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path


def read_file(path):
    with Path(path).open('rb') as stream:
        data = stream.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024:
        raise ValueError('文件超过20MB，请缩小导出日期范围。')
    return data


def entry_counts(data):
    payload = json.loads(data.decode('utf-8-sig'))
    if not isinstance(payload, dict) or payload.get('format_version') not in range(1, 6) or not isinstance(payload.get('entries'), list):
        raise ValueError('请选择手机“文件导入导出”生成的账本 JSON。')
    active, trash = Counter(), Counter()
    for entry in payload['entries']:
        if not isinstance(entry, dict) or type(entry.get('amount_fen')) is not int or entry.get('type') not in ('income', 'expense'):
            raise ValueError('账本记录格式有误，请重新导出。')
        day = date.fromisoformat(entry['date']).isoformat()
        index = active if entry.get('deleted_at') is None else trash
        index[day, entry['type'], entry['amount_fen']] += 1
    return active, trash


def compare(csv_data, ledger_data):
    active, trash = entry_counts(ledger_data)
    try:
        text = csv_data.decode('utf-8-sig')
    except UnicodeDecodeError:
        text = csv_data.decode('gb18030')
    reader = csv.reader(io.StringIO(text, newline=''), strict=True)
    required = {'交易时间', '交易对方', '收/支', '金额', '交易状态', '交易订单号'}
    for row in reader:
        names = [s.strip().replace('（元）', '').replace('(元)', '') for s in row]
        if required.issubset(names):
            break
    else:
        raise ValueError('没有找到支付宝交易明细表头，请先解压并选择 CSV 文件。')
    if any(names.count(name) != 1 for name in required):
        raise ValueError('账单列名重复，请重新导出。')
    rows = []
    for row in reader:
        if not any(cell.strip() for cell in row):
            continue
        fields = dict(zip(names, (cell.strip() for cell in row)))
        rows.append(fields)
    ids = Counter(r.get('交易订单号') for r in rows if r.get('交易订单号'))
    preview = []
    for r in rows:
        stamp, merchant, direction, amount, state, order = (r.get(k, '') for k in ('交易时间','交易对方','收/支','金额','交易状态','交易订单号'))
        kind = {'收入':'income','支出':'expense'}.get(direction)
        label = '请核对交易状态'
        if order and ids[order] > 1:
            label = '账单内订单号重复'
        elif direction in ('不计收支', '不计入收支') or state in ('交易关闭', '支付失败'):
            label = '不计入收支'
        elif kind and (state in ('交易成功','支付成功') or (kind == 'income' and state == '退款成功')):
            try:
                day = datetime.fromisoformat(stamp).date().isoformat()
                if not re.fullmatch(r'[0-9]{1,10}(?:\.[0-9]{1,2})?', amount) or Decimal(amount) <= 0:
                    raise ValueError
                key = day, kind, int(Decimal(amount) * 100)
                if active[key]:
                    label = f'同日同额 {active[key]} 笔，待核对'
                elif trash[key]:
                    label = '回收站有同日同额记录'
                else:
                    label = '所选账本中未找到'
            except ValueError:
                label = '请核对日期或金额'
        preview.append((stamp, merchant, direction, amount, label))
    return preview


def open_preview(parent):
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    bill = filedialog.askopenfilename(parent=parent, title='选择已解压的支付宝交易明细 CSV', filetypes=[('支付宝 CSV', '*.csv')])
    if not bill:
        return
    ledger = filedialog.askopenfilename(parent=parent, title='选择同一日期范围的手机账本 JSON', filetypes=[('手机账本 JSON', '*.json')])
    if not ledger:
        return
    try:
        rows = compare(read_file(bill), read_file(ledger))
    except (OSError, ValueError, KeyError, TypeError, csv.Error):
        messagebox.showerror('无法核对', '请检查文件是否为已解压的支付宝交易明细 CSV 和手机导出的账本 JSON，两份文件均需在20MB以内。', parent=parent)
        return
    window = tk.Toplevel(parent)
    window.title('支付宝账单核对')
    ttk.Label(window, text='同日同额只能作为线索。请核对导出日期范围，再决定是否到手机补记。').pack(padx=10,pady=6)
    cols = ('交易时间','交易对方','收/支','金额（元）','核对结果')
    table = ttk.Treeview(window, columns=cols, show='headings', height=15)
    for col, width in zip(cols,(150,180,65,85,220)):
        table.heading(col,text=col)
        table.column(col,width=width)
    bar = ttk.Scrollbar(window,command=table.yview)
    table.configure(yscrollcommand=bar.set)
    controls = ttk.Frame(window)
    controls.pack(side='bottom',fill='x')
    bar.pack(side='right',fill='y')
    table.pack(fill='both',expand=True)
    page = 0
    caption = tk.StringVar()
    def render(offset):
        nonlocal page
        page += offset
        table.delete(*table.get_children())
        for values in rows[page*100:(page+1)*100]:
            table.insert('', 'end',values=values)
        caption.set(f'共 {len(rows)} 条 · 第 {page+1}/{max(1,(len(rows)+99)//100)} 页')
        prev.configure(state='normal' if page else 'disabled')
        nxt.configure(state='normal' if (page+1)*100<len(rows) else 'disabled')
    prev=ttk.Button(controls,text='上一页',command=lambda:render(-1))
    nxt=ttk.Button(controls,text='下一页',command=lambda:render(1))
    prev.pack(side='left'); nxt.pack(side='right')
    ttk.Label(controls,textvariable=caption).pack()
    render(0)
