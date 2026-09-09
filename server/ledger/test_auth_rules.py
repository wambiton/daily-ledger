from django.test import SimpleTestCase
from . import auth_rules as rules


class AuthRulesTests(SimpleTestCase):
    def test_username_normalization_and_boundaries(self):
        self.assertEqual(rules.username('Test_User'), 'test_user')
        for value in ['abc', 'a' * 33, 'user name', '用户1234', None, 'test\n']:
            with self.assertRaises(ValueError):
                rules.username(value)

    def test_only_exact_qq_domain(self):
        self.assertEqual(rules.qq_email(' 12345@QQ.COM '), '12345@qq.com')
        for value in ['x@qq.com.evil.test', 'x@foxmail.com', 'x@@qq.com', None]:
            with self.assertRaises(ValueError):
                rules.qq_email(value)

    def test_password_is_not_modified(self):
        value = '  密码pass123456  '
        self.assertEqual(rules.password(value), value)
        for value in ['x' * 11, 'x' * 129, None]:
            with self.assertRaises(ValueError):
                rules.password(value)

    def test_credentials_are_not_stored_as_plaintext(self):
        token = rules.new_token()
        self.assertNotEqual(token, rules.new_token())
        self.assertEqual(len(rules.token_digest(token)), 64)
        original = rules.code_digest('123@qq.com', 'register', '123456')
        self.assertNotEqual(original, '123456')
        self.assertNotEqual(original, rules.code_digest('124@qq.com', 'register', '123456'))
        self.assertNotEqual(original, rules.code_digest('123@qq.com', 'reset', '123456'))
        with self.settings(SECRET_KEY='different-test-key'):
            self.assertNotEqual(original, rules.code_digest('123@qq.com', 'register', '123456'))
