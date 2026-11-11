import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

from test_benefit_redemption import api
from test_benefit_issuance import IssuanceStore, PLAN_ID

spec = importlib.util.spec_from_file_location("operator_panel_test", Path(__file__).resolve().parents[1] / "operator_panel.py")
panel = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"benefit_redemption": api}):
    spec.loader.exec_module(panel)


class OperatorPanelTests(unittest.TestCase):
    def setUp(self):
        self.store = IssuanceStore()
        self.table = Mock()
        self.table.get_item.side_effect = self.store.get_item
        self.table.scan.return_value = {"Items": [self.store.items["oferta#pilot"]]}
        self.cursor_items = {}
        self.cursors = Mock()
        self.cursors.put_item.side_effect = lambda Item: self.cursor_items.update({Item["planId"]: Item})
        self.cursors.get_item.side_effect = lambda Key, ConsistentRead: {"Item": self.cursor_items.get(Key["planId"], {})}

    def event(self, route="GET /operador/ofertas", user="staff", query=None):
        return {"routeKey": route, "requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id"}}}},
                "queryStringParameters": query, "pathParameters": {"id": "pilot", "planId": PLAN_ID}}

    def call(self, event=None, now=1000):
        return panel.read(self.table, self.cursors, event or self.event(), now)

    def test_normal_account_and_missing_auth_cannot_read_operator_data(self):
        self.assertEqual(self.call(self.event(user="visitor"))["statusCode"], 403)
        self.assertEqual(self.call({"requestContext": {}})["statusCode"], 401)
        self.table.scan.assert_not_called()

    def test_minimal_catalog_own_merchants_only_even_if_scan_ignores_filter(self):
        foreign = dict(self.store.items["oferta#pilot"], id="oferta#foreign", merchantId="other")
        self.table.scan.return_value["Items"] += [foreign, {"id": "codigo#PRIVATE", "entityType": "BENEFIT_CODE"}]
        response = self.call()
        self.assertEqual(response["statusCode"], 200)
        data = json.loads(response["body"])["data"]
        self.assertEqual(data["userId"], "staff")
        self.assertEqual(len(data["items"]), 1)
        self.assertEqual(data["items"][0]["planIds"], [PLAN_ID])
        self.assertNotIn("PRIVATE", response["body"])
        self.assertNotIn("revision", response["body"])
        self.assertTrue(self.table.scan.call_args.kwargs["ConsistentRead"])

    def test_opaque_cursor_hides_auxiliary_key_and_resumes_empty_page(self):
        self.table.scan.return_value = {"Items": [], "LastEvaluatedKey": {"id": "codigo#PRIVATE"}}
        first = self.call()
        cursor = json.loads(first["body"])["data"]["nextKey"]
        self.assertNotIn("PRIVATE", first["body"])
        self.assertEqual(len(cursor), 36)
        self.assertEqual(self.call(self.event(query={"nextKey": cursor}))["statusCode"], 200)
        self.assertEqual(self.table.scan.call_args.kwargs["ExclusiveStartKey"], {"id": "codigo#PRIVATE"})

    def test_cursor_bound_to_user_merchants_and_expiry(self):
        self.table.scan.return_value["LastEvaluatedKey"] = {"id": "private-key"}
        cursor = json.loads(self.call()["body"])["data"]["nextKey"]
        event = self.event(query={"nextKey": cursor})
        self.assertEqual(self.call(event, now=1900)["statusCode"], 409)
        self.store.items["operador#staff"]["merchantIds"] = ["other"]
        self.assertEqual(self.call(event)["statusCode"], 400)
        self.assertEqual(self.call(self.event(query={"nextKey": "private-key"}))["statusCode"], 400)

    def test_participants_minimal_no_emails_addresses_or_operator_self(self):
        self.store.items[PLAN_ID].update(authorName="Host", attendees=["Visitor", "Staff"], attendeeIds=["visitor", "staff"],
                                         meetingPlace="private-address", email="private@example.com")
        response = self.call(self.event("GET /operador/ofertas/{id}/planes/{planId}"))
        data = json.loads(response["body"])["data"]
        self.assertEqual([member["userId"] for member in data["members"]], ["host", "visitor"])
        self.assertTrue(data["members"][0]["isHost"])
        self.assertEqual(data["members"][1]["name"], "Visitor")
        self.assertNotIn("private", response["body"])

    def test_plan_local_offer_window_and_cancellation_checked(self):
        event = self.event("GET /operador/ofertas/{id}/planes/{planId}")
        self.store.items["oferta#pilot"]["planIds"] = []
        self.assertEqual(self.call(event)["statusCode"], 403)
        self.store.items["oferta#pilot"]["planIds"] = [PLAN_ID]
        self.store.items[PLAN_ID]["status"] = "CANCELLED"
        self.assertEqual(self.call(event)["statusCode"], 409)
        del self.store.items[PLAN_ID]["status"]
        self.assertEqual(self.call(event, now=899)["statusCode"], 410)

    def test_revoked_operator_cannot_read_previously_authorized_plan(self):
        self.store.items["operador#staff"]["active"] = False
        self.assertEqual(self.call(self.event("GET /operador/ofertas/{id}/planes/{planId}"))["statusCode"], 403)

    def test_incomplete_oversized_catalog_is_not_returned(self):
        self.store.items["oferta#pilot"]["planIds"] = [PLAN_ID] * 33
        data = json.loads(self.call()["body"])["data"]
        self.assertEqual(data["items"], [])

    def test_invalid_plan_or_offer_path_rejected(self):
        event = self.event("GET /operador/ofertas/{id}/planes/{planId}")
        event["pathParameters"]["planId"] = "not-uuid"
        self.assertEqual(self.call(event)["statusCode"], 400)


if __name__ == "__main__":
    unittest.main()
