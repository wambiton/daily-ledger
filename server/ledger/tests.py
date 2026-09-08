from django.db import IntegrityError, transaction
from django.test import TestCase
from .models import User, Category, Entry

class FoundationTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(username='test_user',email='test@qq.com',password='testing-only-123')
        self.category = Category.objects.create(user=self.user,type='expense',name='餐饮')

    def test_health(self):
        result = self.client.get('/api/v1/health/')
        self.assertEqual(result.status_code,200)
        self.assertTrue(result.json()['database'])

    def test_password_hashed(self):
        self.assertNotEqual(self.user.password,'testing-only-123')
        self.assertTrue(self.user.check_password('testing-only-123'))

    def test_amount_constraint(self):
        for amount in [0,-1,100000000000]:
            with self.assertRaises(IntegrityError), transaction.atomic():
                Entry.objects.create(user=self.user,category=self.category,type='expense',amount_fen=amount,entry_date='2026-09-07')

    def test_unique_category_scoped_to_user(self):
        with self.assertRaises(IntegrityError), transaction.atomic():
            Category.objects.create(user=self.user,type='expense',name='餐饮')
        other = User.objects.create_user(username='other',email='other@qq.com')
        Category.objects.create(user=other,type='expense',name='餐饮')

    def test_transaction_rollback(self):
        try:
            with transaction.atomic():
                Entry.objects.create(user=self.user,category=self.category,type='expense',amount_fen=1850,entry_date='2026-09-07')
                raise RuntimeError('failed queue write')
        except RuntimeError:
            pass
        self.assertEqual(Entry.objects.count(),0)
