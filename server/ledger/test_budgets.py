import copy
import uuid
from django.test import SimpleTestCase
from .backup_api import validate

class BudgetSnapshotTests(SimpleTestCase):
    def payload(self):
        cat=str(uuid.uuid4())
        return {'format_version':3,'categories':[{'id':cat,'type':'expense','name':'餐饮','default_key':None}], 'entries':[],
                'budgets':[{'id':str(uuid.uuid4()),'name':'季度','start_month':'2026-01','end_month':'2026-03','total_fen':450000,
                            'allocations':{'2026-01':{cat:170000},'2026-02':{cat:130000}},'renewal_dismissed':False}]}

    def test_valid_future_plans_and_invalid_totals_overlap_or_reference(self):
        payload=self.payload()
        self.assertEqual(validate(payload),payload)
        for change in ('overlap','total','category','float','outside'):
            p=copy.deepcopy(payload); b=p['budgets'][0]
            if change=='overlap':
                p['budgets'].append(dict(b,id=str(uuid.uuid4())))
            elif change=='total': b['total_fen']=1
            elif change=='category': b['allocations']={'2026-01':{str(uuid.uuid4()):100}}
            elif change=='float': b['total_fen']=450000.0
            else: b['allocations']={'2027-01':{p['categories'][0]['id']:100}}
            with self.subTest(change=change),self.assertRaises(ValueError): validate(p)

    def test_legacy_payload_cannot_hide_unvalidated_budgets(self):
        p=self.payload();p['format_version']=2
        with self.assertRaises(ValueError):validate(p)
