import hmac
import secrets
import uuid
from datetime import timedelta
from django.conf import settings
from django.contrib.auth.hashers import make_password, check_password
from django.core.mail import send_mail
from django.db import transaction
from django.db.models import F
from django.http import JsonResponse, HttpResponse
from django.utils import timezone
from . import auth_rules as rules, sessions
from .api_common import endpoint, ApiError
from .models import User, EmailChallenge, RateBucket


def throttle(request, scope, identity, limit, seconds):
    # Do not trust client-supplied X-Forwarded-For. A local tunnel shares one
    # IP bucket; identity limits remain independent and persist across restarts.
    bucket = int(timezone.now().timestamp()) // seconds
    key = rules.token_digest(f'{scope}|{identity}|{bucket}')
    RateBucket.objects.get_or_create(key=key)
    allowed = RateBucket.objects.filter(key=key, count__lt=limit).update(count=F('count') + 1)
    if not allowed:
        raise ApiError('操作频繁，请稍后再试', 429, 'rate_limited')


def guard(request, scope, email, limit=10):
    throttle(request, scope + '-account', email, limit, 600)
    throttle(request, scope + '-ip', request.META.get('REMOTE_ADDR', ''), 100, 600)


def profile(user):
    return {'id': str(user.id), 'email': user.email, 'username': user.username}


@endpoint('POST')
def code(request, data):
    email = rules.qq_email(data.get('email'))
    purpose = data.get('purpose')
    if purpose not in ('register', 'reset'):
        raise ApiError('验证码用途错误')
    guard(request, 'code', email, 5)
    if not settings.LEDGER_MAIL_READY:
        raise ApiError('电脑尚未配置验证码发信服务', 503, 'mail_unavailable')
    now = timezone.now()
    with transaction.atomic():
        row, _ = EmailChallenge.objects.get_or_create(email=email, purpose=purpose,
            defaults={'code_digest': '', 'sent_at': now - timedelta(days=1), 'expires_at': now})
        # Conditional write locks the row before replacing the old challenge.
        if not EmailChallenge.objects.filter(pk=row.pk, sent_at__lte=now-timedelta(seconds=60)).update(sent_at=now):
            raise ApiError('请等待60秒后重新发送', 429, 'rate_limited')
        value = f'{secrets.randbelow(1000000):06d}'
        EmailChallenge.objects.filter(pk=row.pk).update(code_digest=rules.code_digest(email, purpose, value),
            expires_at=now+timedelta(minutes=10), attempts=0, consumed_at=None)
        try:
            sent = send_mail('个人记账验证码', f'你的{ "注册" if purpose == "register" else "密码重置" }验证码：{value}，10分钟有效。请勿转发给他人。',
                            settings.DEFAULT_FROM_EMAIL, [email], fail_silently=False)
            if sent != 1:
                raise RuntimeError('not accepted')
        except Exception:
            raise ApiError('验证码发送失败，请稍后重试', 503, 'mail_unavailable') from None
    return JsonResponse({'retry_after': 60}, status=202)


def consume(email, purpose, value):
    if not isinstance(value, str) or len(value) != 6 or not value.isascii() or not value.isdigit():
        value = ''
    now = timezone.now()
    valid = EmailChallenge.objects.filter(email=email, purpose=purpose, consumed_at__isnull=True,
                                         expires_at__gt=now, attempts__lt=5)
    if not valid.update(attempts=F('attempts')+1):
        return False
    row = EmailChallenge.objects.get(email=email, purpose=purpose)
    if not hmac.compare_digest(row.code_digest, rules.code_digest(email, purpose, value)):
        return False
    row.consumed_at = now
    row.save(update_fields=['consumed_at'])
    return True


@endpoint('POST')
def register(request, data):
    email, password = rules.qq_email(data.get('email')), rules.password(data.get('password'))
    guard(request, 'register', email)
    with transaction.atomic():
        if not consume(email, 'register', data.get('code')):
            # Return rather than raise so failed-attempt count is committed.
            return JsonResponse({'error': {'code': 'code_invalid', 'message': '验证码错误、过期或尝试过多'}}, status=400)
        if User.objects.filter(email=email).exists():
            raise ApiError('邮箱已注册，请登录或找回密码', 409)
        user = User.objects.create_user(username='u_' + uuid.uuid4().hex, email=email,
                                        password=password, verified_at=timezone.now())
        result = {'user': profile(user), **sessions.issue(user)}
    return JsonResponse(result, status=201)


@endpoint('POST')
def login(request, data):
    email, password = rules.qq_email(data.get('email')), rules.password(data.get('password'))
    guard(request, 'login', email)
    user = User.objects.filter(email=email).first()
    if user is None:
        make_password(password)
    if user is None or not user.check_password(password) or not user.is_active or user.verified_at is None:
        raise ApiError('邮箱或密码错误', 401, 'unauthorized')
    return {'user': profile(user), **sessions.issue(user)}


@endpoint('POST')
def reset(request, data):
    email, password = rules.qq_email(data.get('email')), rules.password(data.get('password'))
    guard(request, 'reset', email)
    with transaction.atomic():
        if not consume(email, 'reset', data.get('code')):
            return JsonResponse({'error': {'code': 'code_invalid', 'message': '验证码错误、过期或尝试过多'}}, status=400)
        user = User.objects.select_for_update().filter(email=email).first()
        if user:
            user.set_password(password)
            user.save(update_fields=['password'])
            sessions.revoke_all(user)
    return {'status': 'ok'}


@endpoint('POST')
def refresh(request, data):
    throttle(request, 'refresh', request.META.get('REMOTE_ADDR', ''), 200, 600)
    result = sessions.refresh(data.get('refresh_token'))
    if result is None:
        raise ApiError('登录已过期，请重新登录', 401, 'unauthorized')
    return result


@endpoint('POST')
def logout(request, data):
    sessions.logout(data.get('refresh_token'))
    return HttpResponse(status=204)


@endpoint('GET', authenticated=True)
def me(request, data):
    return {'user': profile(request.ledger_user)}
