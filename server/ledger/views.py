from django.db import connection
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_GET

@require_GET
def health(request):
    with connection.cursor() as cursor:
        cursor.execute('SELECT 1')
        ready = cursor.fetchone()[0] == 1
    return JsonResponse({'status':'ok' if ready else 'error','database':ready,
                         'service': 'personal-ledger', 'mail_configured': settings.LEDGER_MAIL_READY,
                         'app_version': '0.6.1', 'backup_formats': [1, 2]})
