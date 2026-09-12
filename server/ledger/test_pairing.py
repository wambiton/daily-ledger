from django.test import TestCase, override_settings

@override_settings(LEDGER_PAIR_KEY='synthetic-private-pair-key', LEDGER_COMPUTER_ID='test-computer')
class PairingTests(TestCase):
    def test_public_endpoints_require_pairing(self):
        for path in ['health/', 'computer/', 'auth/code/', 'backup/latest/']:
            self.assertEqual(self.client.get('/api/v1/'+path).status_code, 403)
        self.assertEqual(self.client.get('/api/v1/computer/', HTTP_X_LEDGER_PAIR='wrong').status_code,403)

    def test_pairing_identifies_computer_but_still_requires_account(self):
        headers = {'HTTP_X_LEDGER_PAIR': 'synthetic-private-pair-key'}
        result = self.client.get('/api/v1/computer/', **headers)
        self.assertEqual(result.json(), {'computer_id':'test-computer','protocol':1})
        self.assertEqual(self.client.get('/api/v1/backup/latest/', **headers).status_code,401)
        self.assertNotIn('synthetic-private-pair-key',result.content.decode())
