"""Opaque bearer sessions. Refresh is single-use; logout revokes both tokens."""
from datetime import timedelta
from django.db import transaction
from django.utils import timezone
from .auth_rules import new_token, token_digest
from .models import AuthSession


def _tokens():
    access, refresh = new_token(), new_token()
    now = timezone.now()
    fields = dict(access_digest=token_digest(access), refresh_digest=token_digest(refresh),
                  access_expires_at=now + timedelta(minutes=15),
                  refresh_expires_at=now + timedelta(days=30))
    return fields, dict(access_token=access, refresh_token=refresh, expires_in=900)


def issue(user):
    if not user.is_active or user.verified_at is None:
        raise ValueError('账号不可用或邮箱尚未验证')
    fields, response = _tokens()
    AuthSession.objects.create(user=user, **fields)
    return response


def _valid_token(value):
    return isinstance(value, str) and 20 <= len(value) <= 256


def authenticate(value):
    if not _valid_token(value):
        return None
    session = AuthSession.objects.select_related('user').filter(
        access_digest=token_digest(value), revoked_at__isnull=True,
        access_expires_at__gt=timezone.now(), user__is_active=True,
        user__verified_at__isnull=False).first()
    return session.user if session else None


@transaction.atomic
def refresh(value):
    if not _valid_token(value):
        return None
    session = AuthSession.objects.select_for_update().filter(
        refresh_digest=token_digest(value), revoked_at__isnull=True,
        refresh_expires_at__gt=timezone.now()).first()
    if session is None or not session.user.is_active or session.user.verified_at is None:
        return None
    fields, response = _tokens()
    for key, val in fields.items():
        setattr(session, key, val)
    session.save(update_fields=list(fields))
    return response


def logout(value):
    if _valid_token(value):
        AuthSession.objects.filter(refresh_digest=token_digest(value), revoked_at__isnull=True).update(
            revoked_at=timezone.now())


def revoke_all(user):
    AuthSession.objects.filter(user=user, revoked_at__isnull=True).update(revoked_at=timezone.now())
