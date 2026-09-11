import copy
import json
import re
import uuid
import smtplib
from datetime import timedelta
from unittest.mock import patch
from django.core import mail
from django.test import TestCase, override_settings
from django.utils import timezone
from .models import User, EmailChallenge, BackupSnapshot, BackupHead
from . import sessions


@override_settings(EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend', LEDGER_MAIL_READY=True,
                   DEFAULT_FROM_EMAIL='test@qq.com')
class AccountBackupTests(TestCase):
    password = 'test-password-123'

    def post(self, path, data, token=None):
        return self.client.post('/api/v1/' + path, json.dumps(data), content_type='application/json',
                                **({'HTTP_AUTHORIZATION': 'Bearer ' + token} if token else {}))

    def code(self, email='123456@qq.com', purpose='register'):
        result = self.post('auth/code/', {'email': email, 'purpose': purpose})
        self.assertEqual(result.status_code, 202)
        self.assertNotIn('code', result.json())
        return re.search(r'\d{6}', mail.outbox[-1].body)[0]

    def account(self, email='123456@qq.com'):
        result = self.post('auth/register/', {'email': email, 'password': self.password, 'code': self.code(email)})
        self.assertEqual(result.status_code, 201)
        return result.json()

    def payload(self):
        category = str(uuid.uuid4())
        return {'format_version': 1, 'categories': [{'id': category, 'type': 'expense', 'name': '餐饮', 'default_key': None}],
                'entries': [{'id': str(uuid.uuid4()), 'type': 'expense', 'category_id': category, 'amount_fen': 1234,
                             'date': '2025-01-02', 'note': 'synthetic', 'created_at': 1, 'updated_at': 2}]}

    def test_register_login_refresh_logout_and_me(self):
        registered = self.account()
        login = self.post('auth/login/', {'email': '123456@QQ.COM', 'password': self.password})
        self.assertEqual(login.status_code, 200)
        token = login.json()['access_token']
        me = self.client.get('/api/v1/auth/me/', HTTP_AUTHORIZATION='Bearer ' + token)
        self.assertEqual(me.json()['user']['id'], registered['user']['id'])
        fresh = self.post('auth/refresh/', {'refresh_token': login.json()['refresh_token']})
        self.assertEqual(fresh.status_code, 200)
        self.assertIsNone(sessions.authenticate(token))
        self.assertEqual(self.post('auth/logout/', {'refresh_token': fresh.json()['refresh_token']}).status_code, 204)
        self.assertIsNone(sessions.authenticate(fresh.json()['access_token']))

    def test_wrong_codes_are_counted_and_locked_after_five(self):
        value = self.code()
        wrong = '000000' if value != '000000' else '111111'
        for _ in range(5):
            self.assertEqual(self.post('auth/register/', {'email': '123456@qq.com', 'password': self.password, 'code': wrong}).status_code, 400)
        row = EmailChallenge.objects.get()
        self.assertEqual(row.attempts, 5)
        self.assertEqual(self.post('auth/register/', {'email': row.email, 'password': self.password, 'code': value}).status_code, 400)
        self.assertFalse(User.objects.exists())

    def test_cooldown_expiry_and_purpose(self):
        value = self.code()
        self.assertEqual(self.post('auth/code/', {'email': '123456@qq.com', 'purpose': 'register'}).status_code, 429)
        self.assertEqual(self.post('auth/reset/', {'email': '123456@qq.com', 'password': self.password, 'code': value}).status_code, 400)
        EmailChallenge.objects.update(expires_at=timezone.now()-timedelta(seconds=1))
        self.assertEqual(self.post('auth/register/', {'email': '123456@qq.com', 'password': self.password, 'code': value}).status_code, 400)

    def test_mail_failure_never_claims_success(self):
        with patch('ledger.auth_api.send_mail', side_effect=OSError('test failure')):
            self.assertEqual(self.post('auth/code/', {'email': '123456@qq.com', 'purpose': 'register'}).status_code, 503)
        self.assertFalse(EmailChallenge.objects.exists())
        with self.settings(LEDGER_MAIL_READY=False):
            self.assertEqual(self.post('auth/code/', {'email': '123456@qq.com', 'purpose': 'register'}).status_code, 503)

    def test_mail_errors_are_actionable_and_do_not_expose_server_details(self):
        for error, expected in [(smtplib.SMTPAuthenticationError(535, b'sensitive-server-detail'), 'mail_auth_failed'),
                                (smtplib.SMTPServerDisconnected('sensitive-server-detail'), 'mail_disconnected'),
                                (TimeoutError('sensitive-server-detail'), 'mail_connection_failed')]:
            with patch('ledger.auth_api.send_mail', side_effect=error):
                result = self.post('auth/code/', {'email': 'diagnostic@qq.com', 'purpose': 'register'})
                self.assertEqual(result.status_code, 503)
                self.assertEqual(result.json()['error']['code'], expected)
                self.assertNotIn('sensitive-server-detail', result.content.decode())
                self.assertFalse(EmailChallenge.objects.filter(email='diagnostic@qq.com').exists())
    def test_reset_revokes_existing_sessions(self):
        account = self.account()
        code = self.code(purpose='reset')
        result = self.post('auth/reset/', {'email': '123456@qq.com', 'password': 'new-password-456', 'code': code})
        self.assertEqual(result.status_code, 200)
        self.assertIsNone(sessions.authenticate(account['access_token']))
        self.assertIsNone(sessions.refresh(account['refresh_token']))
        self.assertEqual(self.post('auth/login/', {'email': '123456@qq.com', 'password': self.password}).status_code, 401)

    def test_login_is_rate_limited(self):
        for _ in range(10):
            self.assertEqual(self.post('auth/login/', {'email': 'notfound@qq.com', 'password': self.password}).status_code, 401)
        self.assertEqual(self.post('auth/login/', {'email': 'notfound@qq.com', 'password': self.password}).status_code, 429)

    def test_upload_restore_and_replay(self):
        token = self.account()['access_token']
        body = {'operation_id': str(uuid.uuid4()), 'base_revision': 0, 'payload': self.payload()}
        self.assertEqual(self.post('backup/', body, token).json()['revision'], 1)
        self.assertEqual(self.post('backup/', body, token).json()['revision'], 1)
        self.assertEqual(BackupSnapshot.objects.count(), 1)
        downloaded = self.client.get('/api/v1/backup/download/', HTTP_AUTHORIZATION='Bearer ' + token)
        self.assertEqual(downloaded.json()['payload'], body['payload'])
        self.assertEqual(BackupHead.objects.get().revision, 1)

    def test_stale_device_cannot_overwrite_and_history_remains(self):
        token = self.account()['access_token']
        body = {'operation_id': str(uuid.uuid4()), 'base_revision': 0, 'payload': self.payload()}
        self.post('backup/', body, token)
        body['operation_id'] = str(uuid.uuid4())
        self.assertEqual(self.post('backup/', body, token).status_code, 409)
        body['base_revision'] = 1
        body['payload']['entries'] = []
        self.assertEqual(self.post('backup/', body, token).json()['revision'], 2)
        self.assertEqual(BackupSnapshot.objects.count(), 2)
        self.assertEqual(len(BackupSnapshot.objects.get(revision=1).payload['entries']), 1)

    def test_accounts_and_unauthenticated_requests_are_isolated(self):
        token = self.account()['access_token']
        other = self.account('999999@qq.com')['access_token']
        body = {'operation_id': str(uuid.uuid4()), 'base_revision': 0, 'payload': self.payload()}
        self.assertEqual(self.post('backup/', body).status_code, 401)
        self.post('backup/', body, token)
        self.assertEqual(self.client.get('/api/v1/backup/download/', HTTP_AUTHORIZATION='Bearer ' + other).status_code, 404)
        self.assertEqual(self.client.get('/api/v1/backup/latest/', HTTP_AUTHORIZATION='Bearer ' + other).json()['revision'], 0)

    def test_invalid_data_and_reused_operation_do_not_change_backup(self):
        token = self.account()['access_token']
        body = {'operation_id': str(uuid.uuid4()), 'base_revision': 0, 'payload': self.payload()}
        invalid = copy.deepcopy(body)
        invalid['payload']['entries'][0]['amount_fen'] = True
        self.assertEqual(self.post('backup/', invalid, token).status_code, 400)
        invalid['payload']['entries'][0]['amount_fen'] = 1234
        invalid['payload']['entries'][0]['category_id'] = str(uuid.uuid4())
        self.assertEqual(self.post('backup/', invalid, token).status_code, 400)
        self.post('backup/', body, token)
        body['payload']['entries'] = []
        self.assertEqual(self.post('backup/', body, token).status_code, 409)
        self.assertEqual(BackupHead.objects.get().revision, 1)

    def test_non_qq_email_and_malformed_json(self):
        self.assertEqual(self.post('auth/code/', {'email': 'test@example.com', 'purpose': 'register'}).status_code, 400)
        response = self.client.post('/api/v1/auth/login/', '[', content_type='application/json')
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.headers['Cache-Control'], 'no-store')
