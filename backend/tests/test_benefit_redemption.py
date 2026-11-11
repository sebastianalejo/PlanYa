"""Canjes aislados: sin AWS, usuarios reales, descuentos ni credenciales."""

import copy
import importlib.util
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import threading
import unittest
import uuid

spec = importlib.util.spec_from_file_location("redemption_under_test", Path(__file__).resolve().parents[1] / "benefit_redemption.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class Cancelled(Exception):
    response = {"Error": {"Code": "TransactionCanceledException"}}


def decoded(values):
    return {key: int(value["N"]) if "N" in value else value.get("S", value.get("BOOL"))
            for key, value in values.items()}


class Store:
    name = "IsolatedBenefits"

    def __init__(self):
        self.lock = threading.RLock()
        self.items = {
            "operador#staff": {"id": "operador#staff", "entityType": "BENEFIT_OPERATOR",
                               "active": True, "merchantIds": ["test-local"]},
            "beneficio#coupon": {"id": "beneficio#coupon", "entityType": "BENEFIT", "userId": "visitor",
                                  "merchantId": "test-local", "status": "DISPONIBLE", "validUntil": 2000},
            "codigo#ABCDEF123456": {"id": "codigo#ABCDEF123456", "entityType": "BENEFIT_CODE",
                                    "benefitId": "beneficio#coupon", "userId": "visitor", "expiresAt": 1200},
        }
        self.writes = 0
        self.lose_response = False
        self.before_commit = None

    def get_item(self, Key, ConsistentRead):
        assert ConsistentRead
        with self.lock:
            item = self.items.get(Key["id"])
            return {"Item": copy.deepcopy(item)} if item else {}

    def transact_write_items(self, TransactItems):
        with self.lock:
            if self.before_commit:
                self.before_commit(self.items)
            operator_op, code_op, benefit_op, receipt_op = TransactItems
            operator_check = operator_op["ConditionCheck"]
            code_check = code_op["ConditionCheck"]
            update = benefit_op["Update"]
            receipt = receipt_op["Put"]
            # Check the production expression contract too, not only final mocks.
            assert operator_check["ConditionExpression"] == "entityType = :type AND active = :active AND contains(merchantIds, :merchant)"
            assert code_check["ConditionExpression"] == "entityType = :type AND benefitId = :benefit AND userId = :owner AND expiresAt > :now"
            assert update["ConditionExpression"] == "entityType = :type AND #status = :available AND merchantId = :merchant AND userId = :owner AND validUntil > :now"
            assert update["UpdateExpression"] == "SET #status = :used, usedAt = :now, usedBy = :operator, redemptionId = :request"
            assert receipt["ConditionExpression"] == "attribute_not_exists(id)"
            assert all(operation[next(iter(operation))]["TableName"] == self.name for operation in TransactItems)
            operator = self.items.get(operator_check["Key"]["id"]["S"], {})
            token = self.items.get(code_check["Key"]["id"]["S"], {})
            benefit = self.items.get(update["Key"]["id"]["S"], {})
            values = decoded(update["ExpressionAttributeValues"])
            new_receipt = decoded(receipt["Item"])
            if not (operator.get("entityType") == "BENEFIT_OPERATOR" and operator.get("active") is True
                    and values[":merchant"] in operator.get("merchantIds", [])
                    and token.get("entityType") == "BENEFIT_CODE"
                    and token.get("benefitId") == benefit.get("id")
                    and token.get("userId") == values[":owner"] and token.get("expiresAt", 0) > values[":now"]
                    and benefit.get("entityType") == "BENEFIT" and benefit.get("status") == "DISPONIBLE"
                    and benefit.get("merchantId") == values[":merchant"] and benefit.get("userId") == values[":owner"]
                    and benefit.get("validUntil", 0) > values[":now"] and new_receipt["id"] not in self.items):
                raise Cancelled()
            benefit.update(status="USADO", usedAt=values[":now"], usedBy=values[":operator"], redemptionId=values[":request"])
            self.items[new_receipt["id"]] = new_receipt
            self.writes += 1
            if self.lose_response:
                raise TimeoutError("simulated lost response")


class RedemptionTests(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.request_id = str(uuid.uuid4())

    def event(self, user="staff", code="ABCDEF123456", request_id=None):
        return {"requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id"}}}},
                "body": json.dumps({"code": code, "requestId": request_id or self.request_id})}

    def call(self, event=None, now=1000):
        return api.redeem(self.store, event or self.event(), now, self.store)

    def test_normal_user_and_claimed_role_cannot_redeem(self):
        event = self.event(user="visitor")
        event["requestContext"]["authorizer"]["jwt"]["claims"]["role"] = "operator"
        self.assertEqual(self.call(event)["statusCode"], 403)
        self.assertEqual(self.store.writes, 0)

    def test_malformed_merchant_permission_is_not_treated_as_substring_access(self):
        self.store.items["operador#staff"]["merchantIds"] = "test-local-other"
        self.assertEqual(self.call()["statusCode"], 403)
        self.assertEqual(self.store.writes, 0)

    def test_missing_auth_or_access_token_rejected(self):
        self.assertEqual(self.call({"body": "{}"})["statusCode"], 401)
        event = self.event()
        event["requestContext"]["authorizer"]["jwt"]["claims"]["token_use"] = "access"
        self.assertEqual(self.call(event)["statusCode"], 401)

    def test_inactive_wrong_merchant_and_self_redemption_rejected(self):
        operator = self.store.items["operador#staff"]
        operator["active"] = False
        self.assertEqual(self.call()["statusCode"], 403)
        operator.update(active=True, merchantIds=["other"])
        self.assertEqual(self.call()["statusCode"], 403)
        operator["merchantIds"] = ["test-local"]
        self.store.items["beneficio#coupon"]["userId"] = "staff"
        self.store.items["codigo#ABCDEF123456"]["userId"] = "staff"
        self.assertEqual(self.call()["statusCode"], 403)
        self.assertEqual(self.store.writes, 0)

    def test_strict_payload_validation(self):
        for raw in ("[]", "null", "{", json.dumps({"code": "bad", "requestId": self.request_id}),
                    json.dumps({"code": "ABCDEF123456", "requestId": "bad"}),
                    json.dumps({"code": "ABCDEF123456", "requestId": self.request_id, "merchantId": "test-local"})):
            event = self.event()
            event["body"] = raw
            self.assertEqual(self.call(event)["statusCode"], 400)

    def test_expiry_boundary_revoked_and_missing_rejected(self):
        self.assertEqual(self.call(now=1200)["statusCode"], 410)
        benefit = self.store.items["beneficio#coupon"]
        benefit["validUntil"] = 1000
        self.assertEqual(self.call()["statusCode"], 410)
        benefit.update(validUntil=2000, status="REVOCADO")
        self.assertEqual(self.call()["statusCode"], 409)
        self.assertEqual(self.call(self.event(code="000000000000"))["statusCode"], 404)
        self.assertEqual(self.store.writes, 0)

    def test_success_atomic_receipt_and_same_request_replay_after_code_expires(self):
        first = self.call()
        self.assertEqual(first["statusCode"], 200)
        self.assertEqual(json.loads(first["body"])["data"]["status"], "USADO")
        self.assertEqual(self.call(now=5000), first)
        self.assertEqual(self.store.writes, 1)
        receipts = [row for row in self.store.items.values() if row.get("entityType") == "BENEFIT_REDEMPTION"]
        self.assertEqual(len(receipts), 1)
        self.assertNotIn("ABCDEF123456", json.dumps(receipts))
        self.assertFalse("code" in receipts[0])

    def test_used_coupon_cannot_be_consumed_with_another_request(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call(self.event(request_id=str(uuid.uuid4())))["statusCode"], 409)
        self.assertEqual(self.store.writes, 1)

    def test_request_cannot_be_reused_for_another_code(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call(self.event(code="000000000000"))["statusCode"], 409)

    def test_lost_response_retry_recovers_same_receipt_without_second_consumption(self):
        self.store.lose_response = True
        self.assertEqual(self.call()["statusCode"], 503)
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.store.writes, 1)

    def test_concurrent_requests_only_consume_once(self):
        events = [self.event(request_id=str(uuid.uuid4())) for _ in range(8)]
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda event: self.call(event)["statusCode"], events))
        self.assertEqual(statuses.count(200), 1)
        self.assertEqual(statuses.count(409), 7)
        self.assertEqual(self.store.writes, 1)

    def test_permission_or_coupon_change_before_commit_rolls_back_everything(self):
        for change in (lambda rows: rows["operador#staff"].update(active=False),
                       lambda rows: rows["beneficio#coupon"].update(status="REVOCADO"),
                       lambda rows: rows["codigo#ABCDEF123456"].update(expiresAt=1)):
            self.store = Store()
            self.store.before_commit = change
            self.assertEqual(self.call()["statusCode"], 409)
            self.assertEqual(self.store.writes, 0)
            self.assertFalse(any(row.get("entityType") == "BENEFIT_REDEMPTION" for row in self.store.items.values()))

    def test_receipt_not_exposed_after_operator_revocation(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.store.items["operador#staff"]["active"] = False
        self.assertEqual(self.call()["statusCode"], 403)


if __name__ == "__main__":
    unittest.main()
