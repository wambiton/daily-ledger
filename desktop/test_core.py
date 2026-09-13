import json
import tempfile
import unittest
from pathlib import Path
from core import identity, store_mail, mail_config, pairing

class ConfigurationTests(unittest.TestCase):
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
