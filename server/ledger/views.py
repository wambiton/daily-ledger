from django.db import connection
from django.http import JsonResponse
from django.views.decorators.http import require_GET

@require_GET
def health(request):
    with connection.cursor() as cursor:
        cursor.execute('SELECT 1')
        ready = cursor.fetchone()[0] == 1
    return JsonResponse({'status':'ok' if ready else 'error','database':ready})
