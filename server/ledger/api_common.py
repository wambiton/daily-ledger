import json
from functools import wraps
from django.db import IntegrityError, OperationalError
from django.http import JsonResponse, HttpResponse
from django.core.exceptions import RequestDataTooBig
from django.views.decorators.csrf import csrf_exempt
from . import sessions
from .auth_rules import AuthInputError


class ApiError(Exception):
    def __init__(self, message, status=400, code='invalid'):
        self.message, self.status, self.code = message, status, code


def endpoint(method, authenticated=False):
    def decorate(fn):
        @csrf_exempt
        @wraps(fn)
        def view(request):
            try:
                if request.method != method:
                    raise ApiError('请求方法不支持', 405)
                if authenticated:
                    header = request.headers.get('Authorization', '')
                    user = sessions.authenticate(header[7:] if header.startswith('Bearer ') else '')
                    if user is None:
                        raise ApiError('请重新登录', 401, 'unauthorized')
                    request.ledger_user = user
                data = {}
                if method == 'POST':
                    if request.content_type != 'application/json':
                        raise ApiError('请使用JSON请求')
                    data = json.loads(request.body)
                    if not isinstance(data, dict):
                        raise ApiError('请求格式错误')
                result = fn(request, data)
                response = result if isinstance(result, HttpResponse) else JsonResponse(result)
            except AuthInputError as error:
                response = JsonResponse({'error': {'code': 'invalid', 'message': str(error)}}, status=400)
            except ApiError as error:
                response = JsonResponse({'error': {'code': error.code, 'message': error.message}}, status=error.status)
            except (ValueError, TypeError, KeyError, RequestDataTooBig):
                response = JsonResponse({'error': {'code': 'invalid', 'message': '填写内容或备份格式不正确'}}, status=400)
            except IntegrityError:
                response = JsonResponse({'error': {'code': 'conflict', 'message': '资料已存在或请求冲突，请重试'}}, status=409)
            except OperationalError:
                response = JsonResponse({'error': {'code': 'busy', 'message': '电脑服务繁忙，请稍后重试'}}, status=503)
            response['Cache-Control'] = 'no-store'
            return response
        return view
    return decorate
