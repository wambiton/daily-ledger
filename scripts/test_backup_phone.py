import sqlite3
from contextlib import closing
import tempfile
from pathlib import Path
import unittest
from backup_phone import snapshot


class BackupTest(unittest.TestCase):
    def test_snapshot_includes_uncheckpointed_wal_and_is_independent(self):
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / 'ledger.db', Path(folder) / 'copy.sqlite3'
            with closing(sqlite3.connect(source)) as conn:
                conn.execute('PRAGMA journal_mode=WAL')
                conn.execute('PRAGMA wal_autocheckpoint=0')
                for table in ['entries', 'categories', 'outbox', 'sync_state']:
                    conn.execute(f'CREATE TABLE {table}(id INTEGER PRIMARY KEY)')
                conn.execute('INSERT INTO entries VALUES (7)')
                conn.commit()
                self.assertTrue(Path(str(source) + '-wal').exists())
                snapshot(source, target)
                conn.execute('DELETE FROM entries')
                conn.commit()
                with closing(sqlite3.connect(target)) as restored:
                    self.assertEqual(restored.execute('SELECT id FROM entries').fetchall(), [(7,)])

    def test_rejects_unrelated_database(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(RuntimeError):
                snapshot(Path(folder) / 'empty.db', Path(folder) / 'copy.db')


if __name__ == '__main__':
    unittest.main()
