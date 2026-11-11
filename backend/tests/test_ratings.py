import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import Mock
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location("ratings_test", Path(__file__).resolve().parents[1] / "ratings.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
PLAN = "52b13d85-3994-5eb5-aa67-1e412dbc9528"


class Conflict(Exception):
    response = {"Error": {"Code": "TransactionCanceledException"}}


class RatingsTests(unittest.TestCase):
    def setUp(self):
        self.rows = {PLAN: {"id": PLAN, "title": "Encuentro", "expiresAt": 900, "revision": 1,
                            "ownerId": "author", "authorName": "Ana", "attendeeIds": ["target"], "attendees": ["Luis"]}}
        for user in ("author", "target"):
            key = api.attendance_key(PLAN, user)
            self.rows[key] = {"id": key, "entityType": "ATTENDANCE", "planId": PLAN, "userId": user}
        self.table = Mock()
        self.table.name = "test"
        self.table.get_item.side_effect = lambda Key, ConsistentRead: {"Item": self.rows.get(Key["id"])}
        self.table.scan.side_effect = lambda **kwargs: {"Items": list(self.rows.values())}
        self.client = Mock()
        self.lock = threading.Lock()
        self.writes = 0
        self.lose_response = False
        self.client.transact_write_items.side_effect = self.transact

    def transact(self, TransactItems):
        with self.lock:
            self.assertEqual(len(TransactItems), 5)
            put = TransactItems[3]["Put"]
            item = {key: next(iter(value.values())) for key, value in put["Item"].items()}
            if item["id"] in self.rows:
                raise Conflict()
            self.assertEqual(put["ConditionExpression"], "attribute_not_exists(id)")
            self.assertIn("revision", TransactItems[2]["ConditionCheck"]["ConditionExpression"])
            update = TransactItems[4]["Update"]
            self.assertIn("ADD ratingCount :one, ratingSum :score, revision :one", update["UpdateExpression"])
            self.rows[item["id"]] = {**item, "score": int(item["score"])}
            profile = self.rows.setdefault("perfil#target", {"entityType": "PROFILE", "userId": "target", "name": "Luis", "revision": 4})
            profile["ratingCount"] = profile.get("ratingCount", 0) + 1
            profile["ratingSum"] = profile.get("ratingSum", 0) + int(item["score"])
            profile["revision"] += 1
            self.writes += 1
            if self.lose_response:
                raise RuntimeError("response lost")

    def event(self, route="POST /valoraciones", user="author", body=None):
        return {"routeKey": route, "requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id"}}}},
                "body": json.dumps(body if body is not None else {"planId": PLAN, "targetUserId": "target", "score": 5})}

    def call(self, event=None):
        return api.handle(self.table, event or self.event(), 1000, self.client)

    def test_auth_required(self):
        self.assertEqual(self.call({"requestContext": {}})["statusCode"], 401)
        self.table.scan.assert_not_called()
        self.client.transact_write_items.assert_not_called()

    def test_candidates_only_shared_finished_accredited_and_not_self(self):
        data = json.loads(self.call(self.event("GET /valoraciones"))["body"])["data"]
        self.assertEqual(data["items"], [{"planId": PLAN, "targetUserId": "target", "planTitle": "Encuentro", "name": "Luis"}])
        self.assertEqual(json.loads(self.call(self.event("GET /valoraciones", user="stranger"))["body"])["data"]["items"], [])
        self.rows[PLAN]["expiresAt"] = 1100
        self.assertEqual(json.loads(self.call(self.event("GET /valoraciones"))["body"])["data"]["items"], [])

    def test_rating_and_aggregate_atomic_and_retry_does_not_duplicate(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.writes, 1)
        self.assertEqual(self.rows["perfil#target"]["ratingCount"], 1)
        self.assertEqual(self.rows["perfil#target"]["ratingSum"], 5)
        self.assertEqual(self.rows["perfil#target"]["name"], "Luis")
        self.assertEqual(self.rows["perfil#target"]["revision"], 5)
        self.assertEqual(json.loads(self.call(self.event("GET /valoraciones"))["body"])["data"]["items"], [])

    def test_self_vote_and_missing_attendance_denied(self):
        self.assertEqual(self.call(self.event(user="target"))["statusCode"], 403)
        del self.rows[api.attendance_key(PLAN, "target")]
        self.assertEqual(self.call()["statusCode"], 403)
        self.assertEqual(self.writes, 0)

    def test_cancelled_or_future_plan_cannot_be_rated(self):
        self.rows[PLAN]["status"] = "CANCELLED"
        self.assertEqual(self.call()["statusCode"], 409)
        del self.rows[PLAN]["status"]
        self.rows[PLAN]["expiresAt"] = 1001
        self.assertEqual(self.call()["statusCode"], 409)
        self.assertEqual(self.writes, 0)

    def test_invalid_scores_ids_and_privileged_fields_rejected(self):
        for score in (0, 6, True, "5", 4.5):
            self.assertEqual(self.call(self.event(body={"planId": PLAN, "targetUserId": "target", "score": score}))["statusCode"], 400)
        self.assertEqual(self.call(self.event(body={"planId": PLAN, "targetUserId": "target", "score": 5, "ratingSum": 999}))["statusCode"], 400)
        self.assertEqual(self.writes, 0)

    def test_cannot_change_a_saved_score(self):
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call(self.event(body={"planId": PLAN, "targetUserId": "target", "score": 3}))["statusCode"], 409)
        self.assertEqual(self.writes, 1)

    def test_lost_response_recovers_same_vote(self):
        self.lose_response = True
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.call()["statusCode"], 200)
        self.assertEqual(self.writes, 1)

    def test_concurrent_votes_only_increment_once(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(lambda _: self.call(), range(8)))
        self.assertTrue(all(reply["statusCode"] == 200 for reply in replies))
        self.assertEqual(self.writes, 1)

    def test_uncertain_write_does_not_report_success(self):
        self.client.transact_write_items.side_effect = RuntimeError("offline")
        self.assertEqual(self.call()["statusCode"], 503)
        self.assertEqual(self.writes, 0)

    def test_scan_continuation_is_followed(self):
        self.table.scan.side_effect = [{"Items": [], "LastEvaluatedKey": {"id": "next"}}, {"Items": list(self.rows.values())}]
        self.assertEqual(len(json.loads(self.call(self.event("GET /valoraciones"))["body"])["data"]["items"]), 1)
        self.assertEqual(self.table.scan.call_args.kwargs["ExclusiveStartKey"], {"id": "next"})

    def test_transaction_conflict_is_not_success(self):
        self.client.transact_write_items.side_effect = Conflict()
        self.assertEqual(self.call()["statusCode"], 409)
        self.assertEqual(self.writes, 0)
