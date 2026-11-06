"""Read-only comparison of a payment CSV and a mobile ledger export."""
import csv
import io
import json
import re
import time
import uuid
import zipfile
from typing import NamedTuple
from collections import Counter, defaultdict
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path


class PaymentRow(NamedTuple):
    stamp: str
    merchant: str
    direction: str
    amount: str
    source_id: str
    platform: str
    label: str


MISSING = '所选账本中未找到'


def supplement(rows, category):
    if not rows or not category:
        raise ValueError('请选择已核实的漏记记录和分类。')
    if any(not row.source_id or row.label != MISSING for row in rows):
        raise ValueError('仅可补记有订单号、且所选账本中未找到的记录。')
    if len({row.source_id for row in rows}) != len(rows):
        raise ValueError('选中了重复交易。')
    now = int(time.time() * 1000)
    entries = []
    for row in rows:
        kind = {'收入': 'income', '支出': 'expense'}[row.direction]
        if category['type'] != kind:
            raise ValueError('请选择收支方向相同的记录和分类。')
        day = datetime.fromisoformat(row.stamp).date()
        fen = int(Decimal(row.amount) * 100)
        if day.year < 1900 or day > date.today() or not 1 <= fen <= 99_999_999_999:
            raise ValueError('交易日期或金额超出手机支持的范围。')
        entries.append(dict(id=row.source_id, type=kind, category_id=category['id'],
                            amount_fen=fen, date=day.isoformat(), note=f'{row.platform} · {row.merchant}'[:500],
                            created_at=now, updated_at=now, deleted_at=None))
    return dict(format_version=2, categories=[category], entries=entries)


def read_file(path):
    with Path(path).open('rb') as stream:
        data = stream.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024:
        raise ValueError('文件超过20MB，请缩小导出日期范围。')
    return data


def index_entries(payload):
    if not isinstance(payload, dict) or payload.get('format_version') not in range(1, 6) or not isinstance(payload.get('entries'), list):
        raise ValueError('请选择手机“文件导入导出”生成的账本 JSON。')
    active, trash = Counter(), Counter()
    candidates = defaultdict(list)
    for entry in payload['entries']:
        if not isinstance(entry, dict) or type(entry.get('amount_fen')) is not int or entry.get('type') not in ('income', 'expense'):
            raise ValueError('账本记录格式有误，请重新导出。')
        day = date.fromisoformat(entry['date']).isoformat()
        index = active if entry.get('deleted_at') is None else trash
        key = day, entry['type'], entry['amount_fen']
        index[key] += 1
        candidates[key].append(entry)
    return active, trash, candidates


def statement_lines(data):
    if not data.startswith(b'PK'):
        try:
            text = data.decode('utf-8-sig')
        except UnicodeDecodeError:
            text = data.decode('gb18030')
        yield from csv.reader(io.StringIO(text, newline=''), strict=True)
        return

    from openpyxl import load_workbook
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members = archive.infolist()
        if len(members) > 1500 or sum(member.file_size for member in members) > 80 * 1024 * 1024:
            raise ValueError('工作簿过大，请缩小日期范围后导出。')
    book = load_workbook(io.BytesIO(data), read_only=True, data_only=False, keep_links=False)
    try:
        selected = None
        for sheet in book.worksheets:
            for cells in sheet.iter_rows(min_row=1,max_row=200,max_col=64,values_only=True):
                labels = {str(cell).strip() for cell in cells if cell is not None}
                if {'交易时间','交易对方','收/支'} <= labels:
                    if selected is not None:
                        raise ValueError('工作簿包含多张交易明细表，请单独导出。')
                    selected = sheet
                    break
        if selected is None:
            raise ValueError('工作簿中没有找到交易明细。')
        order_column = None
        selected.reset_dimensions()
        for index,cells in enumerate(selected.iter_rows(max_col=64)):
            if index >= 50200:
                raise ValueError('交易明细过多，请分日期范围导出。')
            fields = []
            for column,cell in enumerate(cells):
                value = cell.value
                if value is None or cell.data_type == 'f':
                    fields.append('')
                elif column == order_column and not isinstance(value,str):
                    # A numeric cell may already have lost significant digits in Excel.
                    fields.append('')
                else:
                    fields.append(str(value))
            if order_column is None:
                for column,field in enumerate(fields):
                    if field.strip() in ('交易订单号','交易单号'):
                        order_column = column
                        break
            yield fields
    finally:
        book.close()


def compare(csv_data, ledger_data):
    payload = json.loads(ledger_data.decode('utf-8-sig'))
    indexes = index_entries(payload)
    return compare_statement(csv_data, payload, indexes)


def compare_statement(csv_data, payload, indexes):
    active, trash, _ = indexes
    imported = set(payload.get('import_sources', []))
    reader = statement_lines(csv_data)
    required = {'交易时间', '交易对方', '收/支', '金额', '交易状态', '交易订单号'}
    for row in reader:
        names = [s.strip().replace('（元）', '').replace('(元)', '') for s in row]
        wechat = '当前状态' in names and '交易单号' in names
        if wechat:
            aliases = {'当前状态':'交易状态', '交易单号':'交易订单号'}
            names = [aliases.get(name, name) for name in names]
        if required.issubset(names):
            break
    else:
        raise ValueError('没有找到微信或支付宝交易明细表头，请先解压并选择 CSV 或 XLSX 文件。')
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
        if wechat:
            amount = amount.removeprefix('¥').removeprefix('￥').strip()
        kind = {'收入':'income','支出':'expense'}.get(direction)
        label = '请核对交易状态'
        platform = '微信' if wechat else '支付宝'
        source_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f'daily-ledger:bill:{platform}:{order}')) if order else ''
        if source_id and source_id in imported:
            label = '此笔已导入'
        elif order and ids[order] > 1:
            label = '账单内订单号重复'
        elif direction in ('不计收支', '不计入收支', '/') or state in ('交易关闭', '支付失败'):
            label = '不计入收支'
        elif kind and (state in ('交易成功','支付成功') or (wechat and kind == 'income' and state in ('已收钱','已存入零钱')) or (kind == 'income' and state == '退款成功')):
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
                    label = MISSING
            except ValueError:
                label = '请核对日期或金额'
        if label == MISSING and not source_id:
            label = '缺少可靠订单号，请手动核对'
        preview.append(PaymentRow(stamp, merchant, direction, amount, source_id, platform, label))
    return preview


def open_preview(parent):
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    bill = filedialog.askopenfilename(parent=parent, title='选择已解压的微信或支付宝交易明细', filetypes=[('交易明细 CSV 或 XLSX', '*.csv *.xlsx')])
    if not bill:
        return
    ledger = filedialog.askopenfilename(parent=parent, title='选择同一日期范围的手机账本 JSON', filetypes=[('手机账本 JSON', '*.json')])
    if not ledger:
        return
    try:
        ledger_data = read_file(ledger)
        payload = json.loads(ledger_data.decode('utf-8-sig'))
        indexes = index_entries(payload)
        rows = compare_statement(read_file(bill), payload, indexes)
        categories = payload.get('categories', [])
    except (OSError, ValueError, KeyError, TypeError, csv.Error, zipfile.BadZipFile, SyntaxError):
        messagebox.showerror('无法核对', '请检查文件是否为已解压的微信或支付宝交易明细 CSV/XLSX 和手机导出的账本 JSON，两份文件均需在20MB以内。', parent=parent)
        return
    window = tk.Toplevel(parent)
    window.title('支付账单核对')
    ttk.Label(window, text='同日同额只能作为线索。请核对导出日期范围，按住 Ctrl 可选择本页多笔漏记，指定分类后导出。').pack(padx=10,pady=6)
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
    top = ttk.Frame(window)
    top.pack(before=table,fill='x')
    missing_only = tk.BooleanVar(master=window)
    visible = list(range(len(rows)))
    def change_filter():
        nonlocal visible,page
        visible = [i for i,row in enumerate(rows) if row.label == MISSING] if missing_only.get() else list(range(len(rows)))
        page = 0
        render(0)
    ttk.Checkbutton(top,text='仅看可补记的缺失候选',variable=missing_only,command=change_filter).pack(side='left')
    def show_candidates():
        selected = table.selection()
        if len(selected) != 1:
            messagebox.showinfo('查看候选','请先选中一条交易。',parent=window)
            return
        row = rows[int(selected[0])]
        matches = []
        if row.label.startswith('同日同额 ') or row.label == '回收站有同日同额记录':
            key = (datetime.fromisoformat(row.stamp).date().isoformat(),
                   {'收入':'income','支出':'expense'}[row.direction], int(Decimal(row.amount)*100))
            matches = indexes[2].get(key, [])
        if not matches:
            messagebox.showinfo('查看候选','此行在所选账本中没有可展示的同日同额候选。',parent=window)
            return
        detail = tk.Toplevel(window)
        detail.title('所选账本中的同日同额记录')
        ttk.Label(detail,text=f'{row.stamp} · {row.merchant} · {row.direction} {row.amount} 元').pack(anchor='w',padx=8,pady=5)
        section = ttk.Frame(detail)
        section.pack(fill='both',expand=True)
        grid = ttk.Treeview(section,columns=('状态','分类','名称','备注'),show='headings',height=min(12,len(matches)))
        for col,width in [('状态',85),('分类',90),('名称',140),('备注',300)]:
            grid.heading(col,text=col)
            grid.column(col,width=width)
        scroll = ttk.Scrollbar(section,command=grid.yview)
        grid.configure(yscrollcommand=scroll.set)
        scroll.pack(side='right',fill='y')
        grid.pack(fill='both',expand=True)
        category_names = {c['id']:c['name'] for c in options}
        for entry in matches:
            grid.insert('', 'end',values=('正常' if entry.get('deleted_at') is None else '回收站',
                category_names.get(entry.get('category_id'),'分类未知'),entry.get('title',''),entry.get('note','')))
    ttk.Button(top,text='查看同日同额候选',command=show_candidates).pack(side='left')
    table.bind('<Double-1>',lambda event:show_candidates())
    options = [c for c in categories if isinstance(c, dict) and c.get('type') in ('expense','income')
               and isinstance(c.get('id'),str) and isinstance(c.get('name'),str)]
    selection = ttk.Combobox(controls,state='readonly',values=[
        ('支出' if c['type']=='expense' else '收入')+' · '+c['name'] for c in options])
    selection.pack(side='left')
    def export_selected():
        try:
            index = selection.current()
            chosen = [rows[int(item)] for item in table.selection()]
            payload = supplement(chosen, options[index] if index >= 0 else None)
            dest = filedialog.asksaveasfilename(parent=window,title='保存为新的补记文件',defaultextension='.json',
                                                initialfile='核实漏记.json',filetypes=[('手机补记 JSON','*.json')])
            if not dest:
                return
            with Path(dest).open('x',encoding='utf-8') as stream:
                json.dump(payload,stream,ensure_ascii=False)
        except FileExistsError:
            messagebox.showerror('请选择新文件名','这个文件已存在，请另存为新的补记文件。',parent=window)
            return
        except (ValueError,OSError) as error:
            messagebox.showerror('无法导出',str(error),parent=window)
            return
        messagebox.showinfo('补记文件已保存',f'共 {len(chosen)} 笔。请在手机“文件导入导出”中选择此 JSON，预览分类和重复提示后确认追加。',parent=window)
    ttk.Button(controls,text='导出已核实的选中记录',command=export_selected).pack(side='left')
    page = 0
    caption = tk.StringVar()
    def render(offset):
        nonlocal page
        page += offset
        table.delete(*table.get_children())
        for index in visible[page*100:(page+1)*100]:
            row = rows[index]
            table.insert('', 'end',iid=str(index),values=(row.stamp,row.merchant,row.direction,row.amount,row.label))
        caption.set(f'显示 {len(visible)} / {len(rows)} 条 · 第 {page+1}/{max(1,(len(visible)+99)//100)} 页')
        prev.configure(state='normal' if page else 'disabled')
        nxt.configure(state='normal' if (page+1)*100<len(visible) else 'disabled')
    prev=ttk.Button(controls,text='上一页',command=lambda:render(-1))
    nxt=ttk.Button(controls,text='下一页',command=lambda:render(1))
    prev.pack(side='left'); nxt.pack(side='right')
    ttk.Label(controls,textvariable=caption).pack()
    render(0)
