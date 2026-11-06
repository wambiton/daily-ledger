import json
import tempfile
import tkinter as tk
import unittest
from pathlib import Path
from tkinter import ttk
from unittest.mock import patch

from reconciliation import compare, open_preview, supplement

HEADER = '交易时间,交易对方,收/支,金额（元）,交易状态,交易订单号\n'
def bill(*rows):
    return ('支付宝交易明细\n' + HEADER + '\n'.join(rows)).encode('gb18030')

def entry(kind='expense', deleted=None):
    return dict(date='2026-11-01', type=kind, amount_fen=850, deleted_at=deleted)

def ledger(*entries):
    return json.dumps(dict(format_version=5, entries=list(entries))).encode()

class ReconciliationTests(unittest.TestCase):
    def test_equal_amount_is_only_a_clue(self):
        csv = bill('2026-11-01 07:32:00,"早餐,小店",支出,8.50,交易成功,123')
        result = compare(csv, ledger(entry(),entry(),entry('income')))
        self.assertEqual(result[0][1], '早餐,小店')
        self.assertEqual(result[0][-1], '同日同额 2 笔，待核对')
        self.assertEqual(compare(csv, ledger(entry(deleted=123)))[0][-1], '回收站有同日同额记录')
        self.assertEqual(compare(csv, ledger(entry('income')))[0][-1], '所选账本中未找到')

    def test_failed_excluded_and_refund_are_distinct(self):
        csv = bill('2026-11-01,退款,收入,8.50,退款成功,1','2026-11-01,卡,不计收支,8.50,交易成功,2','2026-11-01,店,支出,8.50,交易关闭,3','2026-11-01,店,支出,8.50,退款成功,4')
        self.assertEqual([r[-1] for r in compare(csv,ledger(entry('income')))], ['同日同额 1 笔，待核对','不计入收支','不计入收支','请核对交易状态'])

    def test_duplicate_and_bad_values_stay_visible(self):
        row = '2026-11-01,店,支出,8.50,交易成功,1'
        data = bill(row,row,'?,店,支出,8.50,交易成功,2','2026-11-01,店,支出,8.501,交易成功,3')
        self.assertEqual([r[-1] for r in compare(data,ledger())],['账单内订单号重复']*2+['请核对日期或金额']*2)
        with self.assertRaises(ValueError):
            compare(b'wrong,header', ledger())

    def test_wechat_aliases_currency_and_refund(self):
        header = '交易时间,交易对方,收/支,金额(元),当前状态,交易单号'
        rows = ['2026-11-01,早餐,支出,¥8.50,支付成功,wx1',
                '2026-11-01,早餐退款,收入,￥8.50,退款成功,wx2',
                '2026-11-01,转账,/,8.50,已存入零钱,wx3',
                '2026-11-01,早餐,支出,¥8.50,已全额退款,wx4']
        data = ('微信账单\n'+header+'\n'+'\n'.join(rows)).encode('utf-8-sig')
        labels = [r[-1] for r in compare(data, ledger(entry()))]
        self.assertEqual(labels,['同日同额 1 笔，待核对','所选账本中未找到','不计入收支','请核对交易状态'])

    def test_supplement_has_stable_ids_and_recognizes_prior_import(self):
        cat = dict(id='b3e70ec0-26fa-44a7-bd19-eaa82100a650',name='餐饮',type='expense',default_key=None)
        bill_data = bill('2026-11-01,餐厅,支出,8.50,交易成功,123')
        rows = compare(bill_data, ledger())
        first = supplement(rows,cat)
        self.assertEqual(first['format_version'],2)
        self.assertEqual(first['entries'][0]['amount_fen'],850)
        self.assertEqual(first['categories'],[cat])
        source = first['entries'][0]['id']
        self.assertEqual(source,supplement(compare(bill_data,ledger()),cat)['entries'][0]['id'])
        exported = json.dumps(dict(format_version=2,entries=[],import_sources=[source])).encode()
        self.assertEqual(compare(bill_data,exported)[0].label,'此笔已导入')

    def test_only_eligible_rows_and_matching_category_are_exportable(self):
        cat = dict(id='b3e70ec0-26fa-44a7-bd19-eaa82100a650',name='餐饮',type='expense',default_key=None)
        data = bill('2026-11-01,店,支出,8.50,交易成功,123')
        for rows,category in [(compare(data,ledger(entry())),cat),(compare(data,ledger()),dict(cat,type='income')),
                              (compare(data,ledger()),None),(compare(data,ledger())*2,cat),
                              (compare(bill('2026-11-01,店,支出,8.50,交易成功,'),ledger()),cat)]:
            with self.assertRaises(ValueError):
                supplement(rows,category)

    def test_excel_text_order_and_dates_are_retained_numeric_order_is_not_trusted(self):
        from datetime import datetime
        from io import BytesIO
        from openpyxl import Workbook
        book = Workbook()
        book.active.title = '说明'
        sheet = book.create_sheet('微信明细')
        sheet.append(['这是模拟微信账单'])
        sheet.append(['交易时间','交易对方','收/支','金额(元)','当前状态','交易单号'])
        for amount,order in [(8.50,'000012345678901234567890'),(9,12345678901234567890),('=SUM(8,1)','abc')]:
            sheet.append([datetime(2026,11,1,8,30),'早餐','支出',amount,'支付成功',order])
        stream = BytesIO()
        book.save(stream)
        rows = compare(stream.getvalue(),ledger())
        self.assertEqual(rows[0].label,'所选账本中未找到')
        csv_data = '交易时间,交易对方,收/支,金额,当前状态,交易单号\n2026-11-01 08:30:00,早餐,支出,8.50,支付成功,000012345678901234567890'.encode()
        self.assertEqual(compare(csv_data,ledger())[0].source_id,rows[0].source_id)
        self.assertEqual(rows[1].label,'缺少可靠订单号，请手动核对')
        self.assertEqual(rows[2].label,'请核对日期或金额')

    def test_export_matches_fixture_read_by_android(self):
        fixture = Path(__file__).resolve().parents[1] / 'android/app/src/test/resources/desktop-supplement.json'
        expected = json.loads(fixture.read_text(encoding='utf-8'))
        data = ('交易时间,交易对方,收/支,金额,当前状态,交易单号\n'
                '2025-01-01 08:00:00,模拟早餐,支出,8.50,支付成功,0000000000000000012345').encode()
        with patch('reconciliation.time.time',return_value=1735722000):
            actual = supplement(compare(data, ledger()),expected['categories'][0])
        self.assertEqual(actual,expected)

    def test_filter_then_export_targets_the_selected_original_transaction(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            a,b,dest = (directory/name for name in ('bill.csv','ledger.json','supplement.json'))
            category = dict(id='b3e70ec0-26fa-44a7-bd19-eaa82100a650',name='餐饮',type='expense',default_key=None)
            record = dict(entry(),title='早餐',note='模拟备注',category_id=category['id'])
            b.write_text(json.dumps(dict(format_version=4,entries=[record,dict(record,deleted_at=123)],categories=[category])),encoding='utf-8')
            a.write_bytes(bill('2026-11-01,店一,支出,8.50,交易成功,A','2026-11-01,店二,支出,9.20,交易成功,B'))
            root=tk.Tk();root.withdraw()
            def walk(widget):
                for child in widget.winfo_children():
                    yield child
                    yield from walk(child)
            try:
                with patch('tkinter.filedialog.askopenfilename',side_effect=[str(a),str(b)]):
                    open_preview(root)
                win=next(w for w in root.winfo_children() if isinstance(w,tk.Toplevel))
                grid=next(w for w in walk(win) if isinstance(w,ttk.Treeview))
                grid.selection_set('0')
                next(w for w in walk(win) if isinstance(w,ttk.Button) and w.cget('text')=='查看同日同额候选').invoke()
                detail=next(w for w in walk(win) if isinstance(w,tk.Toplevel))
                candidates=next(w for w in walk(detail) if isinstance(w,ttk.Treeview))
                values=[candidates.item(i,'values') for i in candidates.get_children()]
                self.assertEqual(values,[('正常','餐饮','早餐','模拟备注'),('回收站','餐饮','早餐','模拟备注')])
                detail.destroy()
                next(w for w in walk(win) if isinstance(w,ttk.Checkbutton)).invoke()
                self.assertEqual(grid.get_children(),('1',))
                grid.selection_set('1')
                next(w for w in walk(win) if isinstance(w,ttk.Combobox)).current(0)
                export=next(w for w in walk(win) if isinstance(w,ttk.Button) and w.cget('text')=='导出已核实的选中记录')
                with patch('tkinter.filedialog.asksaveasfilename',return_value=str(dest)),patch('tkinter.messagebox.showinfo'):
                    export.invoke()
                result=json.loads(dest.read_text(encoding='utf-8'))['entries']
                self.assertEqual(len(result),1)
                self.assertEqual(result[0]['amount_fen'],920)
                self.assertEqual(result[0]['note'],'支付宝 · 店二')
            finally:
                root.destroy()

    def test_refund_receipt_and_original_charge_can_share_an_order(self):
        data = ('交易时间,交易类型,交易对方,收/支,金额,当前状态,交易单号\n'
                '2025-01-01,商户消费,餐厅,支出,15,支付成功,111\n'
                '2025-01-02,商户消费退款,餐厅,/,5,退款成功,111').encode()
        charge,refund=compare(data,ledger())
        self.assertEqual(charge.label,refund.label)
        self.assertNotEqual(charge.source_id,refund.source_id)
        self.assertEqual((charge.direction,refund.direction),('支出','收入'))
        cat=dict(id='b3e70ec0-26fa-44a7-bd19-eaa82100a650',name='退款',type='income',default_key=None)
        self.assertEqual(supplement([refund],cat)['entries'][0]['amount_fen'],500)
        payload=json.dumps(dict(format_version=2,entries=[],import_sources=[charge.source_id])).encode()
        self.assertEqual([r.label for r in compare(data,payload)],['此笔已导入','所选账本中未找到'])

    def test_rounded_csv_order_is_for_manual_review(self):
        row=compare(bill('2025-01-01,店,支出,1,交易成功,1.234E+18'),ledger())[0]
        self.assertFalse(row.source_id)
        self.assertEqual(row.label,'缺少可靠订单号，请手动核对')

    def test_preview_from_picker_preserves_files(self):
        with tempfile.TemporaryDirectory() as temp:
            a,b=Path(temp)/'bill.csv',Path(temp)/'ledger.json'
            original=(bill('2026-11-01,店,支出,8.50,交易成功,1'),ledger())
            a.write_bytes(original[0]); b.write_bytes(original[1])
            root=tk.Tk();root.withdraw()
            try:
                with patch('tkinter.filedialog.askopenfilename',side_effect=[str(a),str(b)]):
                    open_preview(root)
                win=next(w for w in root.winfo_children() if isinstance(w,tk.Toplevel))
                table=next(w for w in win.winfo_children() if isinstance(w,ttk.Treeview))
                self.assertEqual(table.item(table.get_children()[0],'values')[-1],'所选账本中未找到')
                self.assertEqual((a.read_bytes(),b.read_bytes()),original)
            finally:
                root.destroy()
