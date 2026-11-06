import json
import tempfile
import tkinter as tk
import unittest
from pathlib import Path
from tkinter import ttk
from unittest.mock import patch

from reconciliation import compare, open_preview

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
