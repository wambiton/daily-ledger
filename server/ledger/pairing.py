"""Require the QR capability before reaching any public companion endpoint."""
import hmac
from django.conf import settings
from django.http import JsonResponse

class PairingMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        key = settings.LEDGER_PAIR_KEY
        if key and not hmac.compare_digest(request.headers.get('X-Ledger-Pair', '').encode(), key.encode()):
            response = JsonResponse({'error': {'code': 'pair_required', 'message': '请扫描电脑端二维码重新配对'}}, status=403)
        elif key and request.path == '/api/v1/computer/' and request.method == 'GET':
            response = JsonResponse({'computer_id': settings.LEDGER_COMPUTER_ID, 'protocol': 1})
        else:
            response = self.get_response(request)
        response['Cache-Control'] = 'no-store'
        response['X-Content-Type-Options'] = 'nosniff'
        return response
