from contextlib import closing
import json
import tempfile
import unittest
from pathlib import Path
from core import identity, store_mail, mail_config, pairing, resolve_data_folder, remember_data_folder
import sqlite3

class ConfigurationTests(unittest.TestCase):
    def test_packaged_entry_reuses_legacy_accounts_without_replacing_either_database(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);home=root/'appdata';legacy=root/'project/电脑账目备份/账号服务'
            executable=root/'project/用户体验/LedgerBackup/LedgerBackup.exe'
            executable.parent.mkdir(parents=True);legacy.mkdir(parents=True);home.mkdir()
            (root/'project/启动电脑备份服务.cmd').touch()
            for folder, count in [(legacy,1),(home,0)]:
                with closing(sqlite3.connect(folder/'accounts.sqlite3')) as db, db:
                    db.execute('CREATE TABLE ledger_user (id INTEGER PRIMARY KEY)')
                    for _ in range(count):db.execute('INSERT INTO ledger_user DEFAULT VALUES')
            before={p:p.read_bytes() for p in (legacy/'accounts.sqlite3',home/'accounts.sqlite3')}
            self.assertEqual(resolve_data_folder(executable=executable,home=home),legacy.resolve())
            remember_data_folder(legacy,home)
            self.assertEqual(resolve_data_folder(executable=root/'moved/app.exe',home=home),legacy.resolve())
            self.assertEqual(before,{p:p.read_bytes() for p in before})
            self.assertEqual(resolve_data_folder(explicit=home,executable=executable,home=home),home.resolve())

    def test_populated_default_is_not_silently_replaced_and_missing_saved_folder_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp)
            with closing(sqlite3.connect(home/'accounts.sqlite3')) as db, db:
                db.execute('CREATE TABLE ledger_user (id INTEGER)');db.execute('INSERT INTO ledger_user VALUES (1)')
            self.assertEqual(resolve_data_folder(home=home),home.resolve())
            remember_data_folder(home/'unavailable',home)
            with self.assertRaises(ValueError):resolve_data_folder(home=home)

    def test_persistent_identity_and_encrypted_mail(self):
        with tempfile.TemporaryDirectory() as folder:
            before = identity(folder)
            self.assertEqual(before, identity(folder))
            store_mail(folder, 'fixture@qq.com','synthetic-smtp-secret')
            self.assertEqual(mail_config(folder), ('fixture@qq.com','synthetic-smtp-secret'))
            self.assertNotIn('synthetic-smtp-secret',(Path(folder)/'smtp.json').read_text(encoding='utf-8'))
            self.assertNotIn(before['pair_key'], (Path(folder)/'computer.json').read_text(encoding='utf-8'))
            qr = json.loads(pairing('https://example.com',before))
            self.assertEqual(qr['computer_id'],before['computer_id'])
            with self.assertRaises(ValueError):pairing('http://example.com',before)

    def test_existing_windows_mail_settings_can_be_reused(self):
        import win32crypt
        with tempfile.TemporaryDirectory() as folder:
            encrypted = win32crypt.CryptProtectData('synthetic-legacy'.encode('utf-16-le'),None,None,None,None,0).hex()
            (Path(folder)/'mail-config.json').write_text(json.dumps({'email':'legacy@qq.com','protectedPassword':encrypted}),encoding='utf-8-sig')
            self.assertEqual(mail_config(folder),('legacy@qq.com','synthetic-legacy'))

if __name__ == '__main__':unittest.main()
