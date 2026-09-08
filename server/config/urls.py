from django.urls import path
from ledger.views import health
urlpatterns = [path('api/v1/health/', health)]
