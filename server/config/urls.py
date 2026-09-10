from django.urls import path
from ledger.views import health
from ledger import auth_api, backup_api
urlpatterns = [path('api/v1/health/', health)]
urlpatterns += [path(f'api/v1/auth/{name}/', getattr(auth_api, name)) for name in ['code', 'register', 'login', 'reset', 'refresh', 'logout', 'me']]
urlpatterns += [path('api/v1/backup/', backup_api.upload), path('api/v1/backup/latest/', backup_api.latest), path('api/v1/backup/download/', backup_api.download)]
