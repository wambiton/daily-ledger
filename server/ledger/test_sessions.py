from datetime import timedelta
from django.test import TestCase
from django.utils import timezone
from . import sessions
from .auth_rules import token_digest
from .models import AuthSession, User


class SessionTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(username='session_user', email='session@qq.com',
                                             verified_at=timezone.now())

    def test_issue_stores_only_fingerprints(self):
        result = sessions.issue(self.user)
        row = AuthSession.objects.get()
        self.assertEqual(row.access_digest, token_digest(result['access_token']))
        self.assertEqual(row.refresh_digest, token_digest(result['refresh_token']))
        self.assertEqual(sessions.authenticate(result['access_token']), self.user)
        self.assertIsNone(sessions.authenticate(result['refresh_token']))

    def test_refresh_rotates_both_tokens_and_rejects_replay(self):
        old = sessions.issue(self.user)
        new = sessions.refresh(old['refresh_token'])
        self.assertIsNotNone(new)
        self.assertIsNone(sessions.authenticate(old['access_token']))
        self.assertIsNone(sessions.refresh(old['refresh_token']))
        self.assertEqual(sessions.authenticate(new['access_token']), self.user)

    def test_expiry_and_logout(self):
        result = sessions.issue(self.user)
        AuthSession.objects.update(access_expires_at=timezone.now() - timedelta(seconds=1))
        self.assertIsNone(sessions.authenticate(result['access_token']))
        new = sessions.refresh(result['refresh_token'])
        sessions.logout(new['refresh_token'])
        sessions.logout(new['refresh_token'])
        self.assertIsNone(sessions.authenticate(new['access_token']))
        self.assertIsNone(sessions.refresh(new['refresh_token']))

    def test_refresh_expiry(self):
        result = sessions.issue(self.user)
        AuthSession.objects.update(refresh_expires_at=timezone.now() - timedelta(seconds=1))
        self.assertIsNone(sessions.refresh(result['refresh_token']))

    def test_revoke_all_is_user_scoped(self):
        other = User.objects.create_user(username='other', email='other@qq.com', verified_at=timezone.now())
        first, second, third = sessions.issue(self.user), sessions.issue(self.user), sessions.issue(other)
        sessions.revoke_all(self.user)
        self.assertIsNone(sessions.authenticate(first['access_token']))
        self.assertIsNone(sessions.authenticate(second['access_token']))
        self.assertEqual(sessions.authenticate(third['access_token']), other)

    def test_disabled_and_unverified_accounts(self):
        result = sessions.issue(self.user)
        self.user.is_active = False
        self.user.save()
        self.assertIsNone(sessions.authenticate(result['access_token']))
        self.assertIsNone(sessions.refresh(result['refresh_token']))
        with self.assertRaises(ValueError):
            sessions.issue(self.user)
        self.user.is_active = True
        self.user.verified_at = None
        self.user.save()
        with self.assertRaises(ValueError):
            sessions.issue(self.user)

    def test_malformed_tokens_are_rejected(self):
        for value in [None, 42, '', 'x' * 257]:
            self.assertIsNone(sessions.authenticate(value))
            self.assertIsNone(sessions.refresh(value))
            sessions.logout(value)
