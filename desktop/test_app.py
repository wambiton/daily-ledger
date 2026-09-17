import tempfile
import unittest
from unittest.mock import patch
import tkinter as tk
from tkinter import ttk
from core import mail_config
import app


class SaveFlowTests(unittest.TestCase):
    def test_actual_save_button_starts_connection_and_reopening_displays_saved_state(self):
        with tempfile.TemporaryDirectory() as folder:
            for reopening in (False,True):
                with patch('sys.argv',['app.py','--data-dir',folder,'--smoke-test']), patch('threading.Thread') as threads:
                    def exercise(root):
                        def walk(widget):
                            yield widget
                            for child in widget.winfo_children():yield from walk(child)
                        widgets=list(walk(root))
                        entries=[w for w in widgets if isinstance(w,ttk.Entry)]
                        labels=lambda:' '.join(str(w.cget('text')) if not w.cget('textvariable') else str(root.getvar(w.cget('textvariable'))) for w in widgets if isinstance(w,ttk.Label))
                        if reopening:
                            self.assertIn('已保存',labels())
                            self.assertEqual(entries[1].get(),'')
                        else:
                            entries[0].insert(0,'fixture@qq.com');entries[1].insert(0,'synthetic-code')
                        button=next(w for w in widgets if isinstance(w,ttk.Button) and w.cget('text')=='保存设置')
                        button.invoke()
                        self.assertEqual(mail_config(folder),('fixture@qq.com','synthetic-code'))
                        self.assertEqual(entries[1].get(),'')
                        self.assertIn('已保存',labels());self.assertIn('正在启动',labels())
                        self.assertEqual(sum(c.kwargs.get('target').__name__=='connect' for c in threads.call_args_list),1)
                        root.destroy()
                    with patch.object(tk.Tk,'mainloop',exercise), patch.object(tk.Tk,'after'):app.main()


if __name__=='__main__':unittest.main()
