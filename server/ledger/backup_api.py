import hashlib
import json
import uuid
from datetime import date
from zoneinfo import ZoneInfo
from django.db import transaction
from django.db.models import F
from django.utils import timezone
from .api_common import endpoint, ApiError
from .auth_api import throttle
from .models import BackupHead, BackupSnapshot


def integer(value, minimum=0, maximum=9999999999999):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError('invalid integer')
    return value


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('invalid id')
    return value


def validate(payload):
    if not isinstance(payload, dict) or type(payload.get('format_version')) is not int or payload['format_version'] not in (1, 2, 3):
        raise ValueError('invalid snapshot')
    fields = {'format_version', 'categories', 'entries'}
    if payload['format_version'] == 3:
        fields.add('budgets')
    if set(payload) not in (fields, fields | {'import_sources'}):
        raise ValueError('invalid snapshot fields')
    sources = payload.get('import_sources', [])
    if not isinstance(sources, list) or len(sources) > 50000:
        raise ValueError('invalid import sources')
    for source in sources:
        identifier(source)
    categories, entries = payload['categories'], payload['entries']
    if not isinstance(categories, list) or not isinstance(entries, list) or len(categories) > 2000 or len(entries) > 50000:
        raise ValueError('too many records')
    by_id, names, ids = {}, set(), set()
    for item in categories:
        if not isinstance(item, dict) or set(item) != {'id', 'type', 'name', 'default_key'}:
            raise ValueError('invalid category')
        cid = identifier(item['id'])
        if item['type'] not in ('expense', 'income') or not isinstance(item['name'], str) or not 1 <= len(item['name'].strip()) <= 20:
            raise ValueError('invalid category')
        key = (item['type'], item['name'])
        if cid in by_id or key in names:
            raise ValueError('duplicate category')
        default = item['default_key']
        if default is not None and default not in [f"{item['type']}-{i}" for i in range(7 if item['type'] == 'expense' else 5)]:
            raise ValueError('invalid default')
        by_id[cid], _ = item, names.add(key)
    today = timezone.now().astimezone(ZoneInfo('Asia/Shanghai')).date()
    for item in entries:
        fields = {'id', 'type', 'category_id', 'amount_fen', 'date', 'note', 'created_at', 'updated_at'}
        if payload['format_version'] >= 2:
            fields.add('deleted_at')
        if not isinstance(item, dict) or set(item) != fields:
            raise ValueError('invalid entry')
        eid = identifier(item['id'])
        category = by_id.get(item['category_id'])
        if eid in ids or category is None or item['type'] != category['type']:
            raise ValueError('invalid reference')
        ids.add(eid)
        integer(item['amount_fen'], 1, 99999999999)
        day = date.fromisoformat(item['date'])
        if day.isoformat() != item['date'] or day > today or day.year < 1900:
            raise ValueError('invalid date')
        if not isinstance(item['note'], str) or len(item['note']) > 500:
            raise ValueError('invalid note')
        integer(item['created_at'])
        integer(item['updated_at'], item['created_at'])
        if item.get('deleted_at') is not None:
            integer(item['deleted_at'], item['created_at'], item['updated_at'])
    if payload['format_version'] == 3:
        validate_budgets(payload['budgets'], by_id)
    return payload


def info(snapshot):
    if snapshot is None:
        return {'revision': 0, 'created_at': None, 'entry_count': 0}
    return {'revision': snapshot.revision, 'created_at': snapshot.created_at.isoformat(),
            'entry_count': sum(e.get('deleted_at') is None for e in snapshot.payload['entries']),
            'trash_count': sum(e.get('deleted_at') is not None for e in snapshot.payload['entries']), 'sha256': snapshot.digest}


@endpoint('POST', authenticated=True)
def upload(request, data):
    user = request.ledger_user
    throttle(request, 'backup', str(user.id), 60, 3600)
    operation = identifier(data['operation_id'])
    base = integer(data['base_revision'])
    payload = validate(data['payload'])
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
    with transaction.atomic():
        BackupHead.objects.get_or_create(user=user)
        # CAS serializes writes even on SQLite. Replay is checked under the same
        # write lock so a lost HTTP response can be retried without duplication.
        changed = BackupHead.objects.filter(user=user, revision=base).update(revision=F('revision')+1)
        previous = BackupSnapshot.objects.filter(user=user, operation_id=operation).first()
        if previous:
            if previous.digest != digest:
                raise ApiError('此上传请求已被使用，请重新发起备份', 409, 'operation_conflict')
            if changed:
                BackupHead.objects.filter(user=user).update(revision=base)
            return info(previous)
        if not changed:
            raise ApiError('电脑上已有更新的备份，请先恢复后再上传，避免覆盖其他手机的记录', 409, 'backup_conflict')
        snapshot = BackupSnapshot.objects.create(user=user, operation_id=operation, revision=base+1, digest=digest, payload=payload)
    return info(snapshot)


@endpoint('GET', authenticated=True)
def latest(request, data):
    snapshot = BackupSnapshot.objects.filter(user=request.ledger_user).order_by('-revision').first()
    return info(snapshot)


@endpoint('GET', authenticated=True)
def download(request, data):
    snapshot = BackupSnapshot.objects.filter(user=request.ledger_user).order_by('-revision').first()
    if snapshot is None:
        raise ApiError('这个账号还没有电脑备份', 404, 'no_backup')
    return {**info(snapshot), 'payload': snapshot.payload}


def validate_budgets(plans, categories):
    import re
    def month(value):
        if not isinstance(value, str) or not re.fullmatch(r'[0-9]{4}-[0-9]{2}', value):
            raise ValueError('invalid budget month')
        year, mon = map(int, value.split('-'))
        if not 1900 <= year <= 9999 or not 1 <= mon <= 12:
            raise ValueError('invalid budget month')
        return year * 12 + mon
    if not isinstance(plans, list) or len(plans) > 1000:
        raise ValueError('invalid budgets')
    ids, ranges = set(), []
    for p in plans:
        if not isinstance(p, dict) or set(p) != {'id', 'name', 'start_month', 'end_month', 'total_fen', 'allocations', 'renewal_dismissed'}:
            raise ValueError('invalid budget fields')
        pid = identifier(p['id'])
        if pid in ids or not isinstance(p['name'], str) or not p['name'].strip() or len(p['name']) > 40 or type(p['renewal_dismissed']) is not bool:
            raise ValueError('invalid budget')
        ids.add(pid)
        start, end = month(p['start_month']), month(p['end_month'])
        if not 0 <= end-start <= 119 or any(a <= end and b >= start for a,b in ranges):
            raise ValueError('overlapping budget months')
        ranges.append((start,end))
        total = integer(p['total_fen'],1,99999999999)
        if not isinstance(p['allocations'], dict):
            raise ValueError('invalid allocations')
        allocated = 0
        for m, values in p['allocations'].items():
            if not start <= month(m) <= end or not isinstance(values, dict):
                raise ValueError('invalid allocation month')
            for cid, amount in values.items():
                if cid not in categories or categories[cid]['type'] != 'expense':
                    raise ValueError('invalid allocation category')
                allocated += integer(amount,1,99999999999)
        if allocated > total:
            raise ValueError('allocations exceed budget')
