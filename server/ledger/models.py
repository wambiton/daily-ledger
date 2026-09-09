import uuid
from django.contrib.auth.models import AbstractUser
from django.db import models

class User(AbstractUser):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    verified_at = models.DateTimeField(null=True)

class Category(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    type = models.CharField(max_length=7, choices=[('expense','支出'),('income','收入')])
    name = models.CharField(max_length=20)
    default_key = models.CharField(max_length=40, null=True)
    version = models.PositiveBigIntegerField(default=1)
    class Meta:
        constraints = [models.UniqueConstraint(fields=['user','type','name'],name='unique_category_name'),
            models.CheckConstraint(condition=models.Q(type__in=['expense','income']),name='category_valid_type')]

class Entry(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    category = models.ForeignKey(Category, on_delete=models.PROTECT)
    type = models.CharField(max_length=7)
    amount_fen = models.BigIntegerField()
    entry_date = models.DateField()
    note = models.CharField(max_length=500, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    version = models.PositiveBigIntegerField(default=1)
    deleted_at = models.DateTimeField(null=True)
    class Meta:
        indexes = [models.Index(fields=['user','entry_date'])]
        constraints = [models.CheckConstraint(condition=models.Q(amount_fen__gte=1,amount_fen__lte=99999999999),name='valid_amount'),
            models.CheckConstraint(condition=models.Q(type__in=['expense','income']),name='entry_valid_type')]

class Change(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    entity_type = models.CharField(max_length=10)
    entity_id = models.UUIDField()
    version = models.PositiveBigIntegerField()
    payload = models.JSONField()
    class Meta:
        indexes = [models.Index(fields=['user','id'])]

class AppliedOperation(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    operation_id = models.UUIDField()
    digest = models.CharField(max_length=64)
    result = models.JSONField()
    class Meta:
        constraints = [models.UniqueConstraint(fields=['user','operation_id'],name='unique_operation')]

class EmailChallenge(models.Model):
    email = models.EmailField()
    purpose = models.CharField(max_length=8, choices=[('register', '注册'), ('reset', '重置')])
    code_digest = models.CharField(max_length=64)
    expires_at = models.DateTimeField()
    sent_at = models.DateTimeField()
    attempts = models.PositiveSmallIntegerField(default=0)
    consumed_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['email', 'purpose'], name='unique_email_challenge')]

class AuthSession(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    access_digest = models.CharField(max_length=64, unique=True)
    refresh_digest = models.CharField(max_length=64, unique=True)
    access_expires_at = models.DateTimeField()
    refresh_expires_at = models.DateTimeField()
    created_at = models.DateTimeField(auto_now_add=True)
    revoked_at = models.DateTimeField(null=True)
