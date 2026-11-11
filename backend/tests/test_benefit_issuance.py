"""Piloto local de asistencia y emision; no llama a AWS."""

import copy
import json
import importlib.util
from pathlib import Path
import sys
import types
from concurrent.futures import ThreadPoolExecutor
import unittest
import uuid
from unittest.mock import Mock, patch

from test_benefit_redemption import api, Store, Cancelled, decoded


PLAN_ID = "4cd6a6a2-642b-46fe-82e4-b7691eec3800"


class IssuanceStore(Store):
    def __init__(self):
        super().__init__()
        self.items.pop("beneficio#coupon")
        self.items.pop("codigo#ABCDEF123456")
        self.items[PLAN_ID] = {"id": PLAN_ID, "entityType": "PLAN", "revision": 2,
                               "ownerId": "host", "attendeeIds": ["visitor"],
                               "startsAt": 900, "expiresAt": 1100}
        self.items["oferta#pilot"] = {"id": "oferta#pilot", "entityType": "BENEFIT_OFFER",
            "active": True, "revision": 1, "merchantId": "test-local", "planIds": [PLAN_ID],
            "validFrom": 800, "validUntil": 5000, "couponDurationSeconds": 3600,
            "merchantName": "Local aislado", "title": "Beneficio de prueba", "discount": "Regla de prueba",
            "conditions": "Solo prueba automatizada", "category": "CAFE", "limits": "Un uso"}

    def transact_write_items(self, TransactItems):
        if len(TransactItems) != 6:
            return super().transact_write_items(TransactItems)
        with self.lock:
            if self.before_commit:
                self.before_commit(self.items)
            operator_check, offer_check, plan_check = [operation["ConditionCheck"] for operation in TransactItems[:3]]
            offer_values = decoded(offer_check["ExpressionAttributeValues"])
            plan_values = decoded(plan_check["ExpressionAttributeValues"])
            operator = self.items[operator_check["Key"]["id"]["S"]]
            offer = self.items[offer_check["Key"]["id"]["S"]]
            plan = self.items[plan_check["Key"]["id"]["S"]]
            assert "contains(merchantIds, :merchant)" in operator_check["ConditionExpression"]
            assert "revision = :revision" in offer_check["ConditionExpression"]
            assert "contains(planIds, :plan)" in offer_check["ConditionExpression"]
            assert "validUntil > :now" in offer_check["ConditionExpression"]
            assert "contains(attendeeIds, :user)" in plan_check["ConditionExpression"]
            assert "expiresAt > :cutoff" in plan_check["ConditionExpression"]
            assert "startsAt <= :now" in plan_check["ConditionExpression"]
            assert "revision = :revision" in plan_check["ConditionExpression"]
            puts = []
            for operation in TransactItems[3:]:
                put = operation["Put"]
                assert put["ConditionExpression"] == "attribute_not_exists(id)"
                puts.append(decoded(put["Item"]))
            if not (operator.get("entityType") == "BENEFIT_OPERATOR" and operator.get("active") is True
                    and offer_values[":merchant"] in operator.get("merchantIds", [])
                    and offer.get("entityType") == "BENEFIT_OFFER" and offer.get("active") is True
                    and offer.get("revision") == offer_values[":revision"]
                    and offer.get("merchantId") == offer_values[":merchant"]
                    and offer_values[":plan"] in offer.get("planIds", [])
                    and offer["validFrom"] <= offer_values[":now"] < offer["validUntil"]
                    and plan.get("entityType") in (None, "PLAN") and plan.get("revision") == plan_values[":revision"]
                    and plan.get("status") != "CANCELLED"
                    and (plan.get("ownerId") == plan_values[":user"] or plan_values[":user"] in plan.get("attendeeIds", []))
                    and plan["startsAt"] <= plan_values[":now"] and plan["expiresAt"] > plan_values[":cutoff"]
                    and all(item["id"] not in self.items for item in puts)):
                raise Cancelled()
            for item in puts:
                self.items[item["id"]] = item
            self.writes += 1
            if self.lose_response:
                raise TimeoutError("simulated lost response")


class IssuanceTests(unittest.TestCase):
    def setUp(self):
        self.store = IssuanceStore()
        self.request_id = str(uuid.uuid4())

    def event(self, operator="staff", **changes):
        body = {"planId": PLAN_ID, "userId": "visitor", "offerId": "pilot", "requestId": self.request_id}
        body.update(changes)
        return {"requestContext": {"authorizer": {"jwt": {"claims": {"sub": operator, "token_use": "id"}}}},
                "body": json.dumps(body)}

    def call(self, event=None, now=1000):
        return api.confirm_attendance(self.store, event or self.event(), now, self.store)

    def rows(self, kind):
        return [row for row in self.store.items.values() if row.get("entityType") == kind]

    def test_user_cannot_self_award_or_claim_operator_role(self):
        event = self.event(operator="visitor")
        event["requestContext"]["authorizer"]["jwt"]["claims"]["role"] = "operator"
        self.assertEqual(self.call(event)["statusCode"], 403)
        self.assertEqual(self.call(self.event(userId="staff"))["statusCode"], 403)
        self.assertFalse(self.rows("BENEFIT"))

    def test_auth_and_strict_payload(self):
        self.assertEqual(self.call({"body": "{}"})["statusCode"], 401)
        for changes in ({"discount": "100%"}, {"userId": []}, {"planId": "bad"}, {"requestId": "bad"}, {"offerId": "../bad"}):
            self.assertEqual(self.call(self.event(**changes))["statusCode"], 400)
        event = self.event()
        event["requestContext"]["authorizer"]["jwt"]["claims"]["token_use"] = "access"
        self.assertEqual(self.call(event)["statusCode"], 401)

    def test_authorized_atomic_confirmation_creates_single_attendance_and_coupon(self):
        response = self.call()
        self.assertEqual(response["statusCode"], 200)
        benefit = self.rows("BENEFIT")[0]
        offer = self.store.items["oferta#pilot"]
        for field in ("merchantName", "discount", "conditions", "category", "limits"):
            self.assertEqual(benefit[field], offer[field])
        self.assertEqual(benefit["validUntil"], 4600)
        self.assertEqual(benefit["userId"], "visitor")
        self.assertEqual(len(self.rows("ATTENDANCE")), 1)
        self.assertEqual(len(self.rows("BENEFIT_ISSUANCE")), 1)
        self.assertEqual(self.rows("ATTENDANCE")[0]["verifiedBy"], "staff")
        self.assertNotIn("userId", json.loads(response["body"])["data"])

    def test_coupon_never_outlives_offer(self):
        self.store.items["oferta#pilot"]["validUntil"] = 1100
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.rows("BENEFIT")[0]["validUntil"], 1100)

    def test_membership_local_and_registered_plan_required(self):
        self.assertEqual(self.call(self.event(userId="intruder"))["statusCode"], 403)
        self.store.items["oferta#pilot"]["planIds"] = []
        self.assertEqual(self.call()["statusCode"], 403)
        self.store.items["oferta#pilot"]["planIds"] = [PLAN_ID]
        self.store.items["operador#staff"]["merchantIds"] = ["other"]
        self.assertEqual(self.call()["statusCode"], 403)

    def test_future_expired_cancelled_and_invalid_configuration(self):
        self.assertEqual(self.call(now=899)["statusCode"], 410)
        self.store.items["oferta#pilot"]["validUntil"] = 20000
        self.assertEqual(self.call(now=8300)["statusCode"], 410)
        self.store.items[PLAN_ID]["status"] = "CANCELLED"
        self.assertEqual(self.call()["statusCode"], 409)
        del self.store.items[PLAN_ID]["status"]
        self.store.items["oferta#pilot"]["active"] = False
        self.assertEqual(self.call()["statusCode"], 410)
        self.store.items["oferta#pilot"].update(active=True, couponDurationSeconds=0)
        self.assertEqual(self.call()["statusCode"], 409)

    def test_host_allowed_but_not_operator_self(self):
        self.assertEqual(self.call(self.event(userId="host"))["statusCode"], 200)
        self.assertEqual(self.rows("ATTENDANCE")[0]["userId"], "host")

    def test_same_request_replays_after_offer_expiry_and_loss_of_membership(self):
        first = self.call()
        self.store.items[PLAN_ID]["attendeeIds"] = []
        self.assertEqual(self.call(now=30000), first)
        self.assertEqual(self.store.writes, 1)
        self.assertEqual(self.call(self.event(userId="host"))["statusCode"], 409)

    def test_different_request_does_not_award_same_attendance_twice(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call(self.event(requestId=str(uuid.uuid4())))["statusCode"], 409)
        self.assertEqual(len(self.rows("ATTENDANCE")), 1)
        self.assertEqual(len(self.rows("BENEFIT")), 1)

    def test_second_offer_cannot_award_same_person_and_plan_again(self):
        self.assertEqual(self.call()["statusCode"], 200)
        offer = copy.deepcopy(self.store.items["oferta#pilot"])
        offer["id"] = "oferta#second"
        self.store.items[offer["id"]] = offer
        self.assertEqual(self.call(self.event(offerId="second", requestId=str(uuid.uuid4())))["statusCode"], 409)
        self.assertEqual(len(self.rows("BENEFIT")), 1)

    def test_offer_expiry_and_plan_window_exact_boundaries(self):
        self.assertEqual(self.call(now=5000)["statusCode"], 410)
        self.store.items["oferta#pilot"]["validUntil"] = 10000
        self.assertEqual(self.call(now=8300)["statusCode"], 410)
        self.assertEqual(self.call(now=8299)["statusCode"], 200)

    def test_concurrent_operators_and_requests_emit_once(self):
        self.store.items["operador#staff2"] = {"id": "operador#staff2", "entityType": "BENEFIT_OPERATOR",
                                              "active": True, "merchantIds": ["test-local"]}
        events = [self.event(operator="staff" if n % 2 else "staff2", requestId=str(uuid.uuid4())) for n in range(8)]
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda event: self.call(event)["statusCode"], events))
        self.assertEqual(statuses.count(200), 1)
        self.assertEqual(statuses.count(409), 7)
        self.assertEqual(len(self.rows("ATTENDANCE")), 1)
        self.assertEqual(len(self.rows("BENEFIT")), 1)

    def test_lost_response_recovers_without_double_issuance(self):
        self.store.lose_response = True
        self.assertEqual(self.call()["statusCode"], 503)
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.store.writes, 1)

    def test_transaction_rechecks_plan_offer_permissions_and_rolls_back(self):
        changes = (lambda rows: rows[PLAN_ID].update(status="CANCELLED"),
                   lambda rows: rows[PLAN_ID].update(attendeeIds=[]),
                   lambda rows: rows[PLAN_ID].update(revision=3),
                   lambda rows: rows["oferta#pilot"].update(revision=2),
                   lambda rows: rows["oferta#pilot"].update(active=False),
                   lambda rows: rows["oferta#pilot"].update(planIds=[]),
                   lambda rows: rows["operador#staff"].update(active=False))
        for change in changes:
            self.store = IssuanceStore()
            self.store.before_commit = change
            self.assertEqual(self.call()["statusCode"], 409)
            self.assertFalse(self.rows("ATTENDANCE"))
            self.assertFalse(self.rows("BENEFIT"))
            self.assertFalse(self.rows("BENEFIT_ISSUANCE"))

    def test_revoked_operator_cannot_replay_receipt(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.store.items["operador#staff"]["active"] = False
        self.assertEqual(self.call()["statusCode"], 403)

    def test_issued_coupon_can_be_consumed_once_by_authorized_operator(self):
        self.assertEqual(self.call()["statusCode"], 200)
        coupon = self.rows("BENEFIT")[0]
        self.store.items["codigo#ABCDEF123456"] = {"id": "codigo#ABCDEF123456", "entityType": "BENEFIT_CODE",
            "benefitId": coupon["id"], "userId": "visitor", "expiresAt": 1200}
        event = self.event()
        event["body"] = json.dumps({"code": "ABCDEF123456", "requestId": str(uuid.uuid4())})
        result = api.redeem(self.store, event, 1001, self.store)
        self.assertEqual(result["statusCode"], 200)
        self.assertEqual(coupon["status"], "USADO")
        self.assertEqual(api.redeem(self.store, event, 1002, self.store), result)

    def test_integrated_api_issues_reads_codes_redeems_and_counts_attendance(self):
        fake_boto = types.ModuleType("boto3")
        fake_boto.resource = Mock()
        lambda_spec = importlib.util.spec_from_file_location("issuance_integrated_lambda", Path(__file__).resolve().parents[1] / "lambda_function.py")
        integrated = importlib.util.module_from_spec(lambda_spec)
        with patch.dict(sys.modules, {"boto3": fake_boto}):
            lambda_spec.loader.exec_module(integrated)
        now = int(integrated.datetime.now(integrated.timezone.utc).timestamp())
        self.store.items[PLAN_ID].update(startsAt=now - 60, expiresAt=now + 600)
        self.store.items["oferta#pilot"].update(validFrom=now - 60, validUntil=now + 7200)
        response = self.call(now=now)
        self.assertEqual(response["statusCode"], 200)
        benefit_id = json.loads(response["body"])["data"]["benefitId"]
        table = Mock()
        table.get_item.side_effect = self.store.get_item
        table.put_item.side_effect = lambda Item: self.store.items.update({Item["id"]: copy.deepcopy(Item)})
        visitor = self.event(operator="visitor")
        visitor["pathParameters"] = {"id": benefit_id}
        result = integrated.get_benefit(table, visitor)
        self.assertEqual(json.loads(result["body"])["data"]["status"], "DISPONIBLE")
        issued_code = integrated.generate_benefit_code(table, visitor)
        self.assertEqual(issued_code["statusCode"], 200)
        code = json.loads(issued_code["body"])["data"]["code"]
        event = self.event()
        event["body"] = json.dumps({"code": code, "requestId": str(uuid.uuid4())})
        self.assertEqual(api.redeem(self.store, event, now, self.store)["statusCode"], 200)
        result = integrated.get_benefit(table, visitor)
        self.assertEqual(json.loads(result["body"])["data"]["status"], "USADO")
        self.assertEqual(integrated.generate_benefit_code(table, visitor)["statusCode"], 409)
        table.scan.return_value = {"Items": list(self.store.items.values())}
        activity = integrated.activity_request(table, visitor)
        self.assertEqual(json.loads(activity["body"])["data"]["attended"], 1)
        for item in self.store.items.values():
            if item.get("entityType") != "PLAN":
                self.assertFalse(integrated.is_plan_item(item))


if __name__ == "__main__":
    unittest.main()
