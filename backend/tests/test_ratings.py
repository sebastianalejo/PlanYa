import importlib.util
import json
import os
from pathlib import Path
import threading
import unittest
from unittest.mock import Mock, patch
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location("ratings_test", Path(__file__).resolve().parents[1] / "ratings.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
PLAN = "52b13d85-3994-5eb5-aa67-1e412dbc9528"


class Conflict(Exception):
    response = {"Error": {"Code": "TransactionCanceledException"}}


class CursorStore:
    def __init__(self):
        self.rows = {}

    def get_item(self, Key, ConsistentRead=True):
        return {"Item": self.rows.get(Key["planId"])}

    def put_item(self, Item):
        self.rows[Item["planId"]] = dict(Item)


class RatingsTests(unittest.TestCase):
    def setUp(self):
        self.rows = {PLAN: {"id": PLAN, "title": "Encuentro", "expiresAt": 900, "revision": 1,
                            "ownerId": "author", "authorName": "Ana", "attendeeIds": ["target"], "attendees": ["Luis"]}}
        for user in ("author", "target"):
            key = api.attendance_key(PLAN, user)
            self.rows[key] = {"id": key, "entityType": "ATTENDANCE", "planId": PLAN, "userId": user}
        self.table = Mock()
        self.table.name = "test"
        self.table.get_item.side_effect = lambda Key, ConsistentRead=False: {"Item": self.rows.get(Key["id"])}
        self.table.scan.side_effect = lambda **kwargs: {"Items": list(self.rows.values())}
        self.table.query.side_effect = self.query
        self.cursors = CursorStore()
        self.client = Mock()
        self.lock = threading.Lock()
        self.writes = 0
        self.lose_response = False
        self.client.transact_write_items.side_effect = self.transact

    def query(self, **kwargs):
        self.assertEqual(kwargs["IndexName"], "targetUserId-createdAt-index")
        self.assertFalse(kwargs["ScanIndexForward"])
        target = kwargs["ExpressionAttributeValues"][":target"]
        rows = [row for row in self.rows.values()
                if row.get("entityType") == "RATING" and row.get("targetUserId") == target]
        rows.sort(key=lambda row: (row["createdAt"], row["id"]), reverse=True)
        start = 0
        exclusive = kwargs.get("ExclusiveStartKey")
        if exclusive:
            self.assertEqual(exclusive.get("targetUserId"), target)
            ids = [row["id"] for row in rows]
            self.assertIn(exclusive["id"], ids)
            start = ids.index(exclusive["id"]) + 1
        limit = kwargs.get("Limit", 20)
        page = rows[start:start + limit]
        result = {"Items": page}
        if start + len(page) < len(rows):
            result["LastEvaluatedKey"] = {"targetUserId": target, "createdAt": page[-1]["createdAt"],
                                          "id": page[-1]["id"]}
        return result

    def add_plan(self, plan_id, title="Encuentro", status=None):
        row = {"id": plan_id, "entityType": "PLAN", "title": title, "expiresAt": 900, "revision": 1}
        if status:
            row["status"] = status
        self.rows[plan_id] = row
        return row

    def add_rating(self, created, score=5, author="author", target="target", plan_id=PLAN):
        key = api.rating_key(plan_id, author, target)
        self.rows[key] = {"id": key, "entityType": "RATING", "planId": plan_id,
                          "authorUserId": author, "targetUserId": target,
                          "score": score, "createdAt": created}
        return self.rows[key]

    def profile(self, count, total, user="target"):
        self.rows["perfil#" + user] = {"id": "perfil#" + user, "entityType": "PROFILE", "userId": user,
                                       "ratingCount": count, "ratingSum": total}

    def received_event(self, user="target", query=None):
        return {"routeKey": "GET /perfil/valoraciones-recibidas",
                "requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id"}}}},
                "queryStringParameters": query}

    def received_data(self, event=None):
        reply = self.call(event or self.received_event())
        self.assertEqual(reply["statusCode"], 200, reply)
        return json.loads(reply["body"])["data"]

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
        return api.handle(self.table, event or self.event(), 1000, self.client, self.cursors)

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

    # US-11: opiniones recibidas ------------------------------------------------

    def test_received_requires_authenticated_account_and_does_not_read(self):
        self.assertEqual(self.call(self.received_event(user=None))["statusCode"], 401)
        self.table.get_item.assert_not_called()
        self.table.scan.assert_not_called()
        self.table.query.assert_not_called()

    def test_received_empty_profile_does_not_invent_votes(self):
        data = self.received_data()
        self.assertEqual(data, {"userId": "target", "count": 0, "average": None,
                                "items": [], "nextKey": None})

    def test_received_summary_uses_profile_and_hides_evaluator(self):
        self.profile(2, 9)
        self.add_rating(1200, 5, author="author")
        self.add_rating(1100, 4, author="otra-cuenta")
        self.add_plan(PLAN, "Café por Miraflores")
        data = self.received_data()
        self.assertEqual((data["count"], data["average"]), (2, 4.5))
        self.assertEqual([item["createdAt"] for item in data["items"]], [1200, 1100])
        self.assertEqual([item["score"] for item in data["items"]], [5, 4])
        self.assertTrue(all(item["planTitle"] == "Café por Miraflores" for item in data["items"]))
        self.assertTrue(all(item["planAvailable"] for item in data["items"]))
        self.assertEqual(set(data["items"][0]), {"id", "planId", "planTitle", "score", "createdAt", "planAvailable"})
        self.assertRegex(data["items"][0]["id"], r"^[0-9a-f]{64}$")
        self.assertNotEqual(data["items"][0]["id"], data["items"][1]["id"])
        serialized = json.dumps(data)
        self.assertNotIn("authorUserId", serialized)
        self.assertNotIn("otra-cuenta", serialized)
        self.assertNotIn("Luis", serialized)

    def test_received_summary_is_not_calculated_from_the_visible_page(self):
        self.profile(5, 20)
        self.add_rating(1200, 5)
        data = self.received_data()
        self.assertEqual((data["count"], data["average"]), (5, 4.0))
        self.assertEqual(len(data["items"]), 1)

    def test_received_pagination_is_ordered_and_uses_a_private_cursor(self):
        self.profile(21, 63)
        for number in range(21):
            self.add_rating(1000 + number, 1 + number % 5, author="persona-%d" % number)
        first = self.received_data()
        self.assertEqual(len(first["items"]), 20)
        self.assertEqual(first["items"][0]["createdAt"], 1020)
        self.assertEqual(first["items"][-1]["createdAt"], 1001)
        self.assertIsNotNone(first["nextKey"])
        stored = self.cursors.rows["page#" + first["nextKey"]]
        self.assertEqual(stored["scope"], "received-ratings#target")
        self.assertEqual(stored["mode"], "scan")
        self.assertNotIn(stored["key"], json.dumps(first))
        second = self.received_data(self.received_event(query={"nextKey": first["nextKey"]}))
        self.assertEqual([item["createdAt"] for item in second["items"]], [1000])
        self.assertIsNone(second["nextKey"])
        self.assertEqual(len({(item["planId"], item["createdAt"], item["score"])
                              for item in first["items"] + second["items"]}), 21)

    def test_received_cursor_from_another_account_or_unknown_parameter_is_rejected(self):
        self.profile(21, 63)
        for number in range(21):
            self.add_rating(1000 + number, 5, author="persona-%d" % number)
        cursor = self.received_data()["nextKey"]
        reply = self.call(self.received_event(user="author", query={"nextKey": cursor}))
        self.assertEqual(reply["statusCode"], 400)
        self.assertEqual(json.loads(reply["body"])["code"], "CURSOR_INVALID")
        foreign = self.received_data(self.received_event(user="author", query={"userId": "target"}))
        self.assertEqual(foreign["userId"], "author")
        self.assertEqual(foreign["items"], [])

    def test_received_expired_or_malformed_cursor_is_not_accepted(self):
        self.profile(21, 63)
        for number in range(21):
            self.add_rating(1000 + number, 5, author="persona-%d" % number)
        cursor = self.received_data()["nextKey"]
        self.cursors.rows["page#" + cursor]["expiresAt"] = 1000
        reply = self.call(self.received_event(query={"nextKey": cursor}))
        self.assertEqual(reply["statusCode"], 409)
        self.assertEqual(json.loads(reply["body"])["code"], "CURSOR_EXPIRED")
        for value in ("no-es-uuid", ""):
            self.assertEqual(self.call(self.received_event(query={"nextKey": value}))["statusCode"], 400)

    def test_received_keeps_rating_with_neutral_reference_when_plan_is_unavailable(self):
        self.profile(2, 9)
        missing = "52b13d85-3994-5eb5-aa67-1e412dbc9529"
        self.add_rating(1200, 5, plan_id=missing)
        self.add_plan(PLAN, "Encuentro cancelado", status="CANCELLED")
        self.add_rating(1100, 4)
        data = self.received_data()
        self.assertEqual([item["planAvailable"] for item in data["items"]], [False, False])
        self.assertEqual([item["planTitle"] for item in data["items"]],
                         ["Encuentro no disponible", "Encuentro no disponible"])
        self.assertEqual([item["score"] for item in data["items"]], [5, 4])

    def test_received_never_writes_or_changes_the_aggregate(self):
        self.profile(1, 5)
        self.add_rating(1200, 5)
        self.received_data()
        self.client.transact_write_items.assert_not_called()
        self.table.put_item.assert_not_called()
        self.assertEqual(self.rows["perfil#target"]["ratingCount"], 1)
        self.assertEqual(self.rows["perfil#target"]["ratingSum"], 5)

    def test_received_uses_recipient_index_when_configured(self):
        self.profile(21, 105)
        for number in range(21):
            self.add_rating(1000 + number, 5, author="persona-%d" % number)
        with patch.dict(os.environ, {"RATINGS_RECEIVED_INDEX": "targetUserId-createdAt-index"}):
            first = self.received_data()
            self.assertEqual(len(first["items"]), 20)
            self.assertEqual(first["items"][0]["createdAt"], 1020)
            self.table.query.assert_called()
            self.table.scan.assert_not_called()
            self.assertEqual(self.cursors.rows["page#" + first["nextKey"]]["mode"], "index")
            second = self.received_data(self.received_event(query={"nextKey": first["nextKey"]}))
        self.assertEqual([item["createdAt"] for item in second["items"]], [1000])
        self.assertIsNone(second["nextKey"])
        exclusive = self.table.query.call_args.kwargs["ExclusiveStartKey"]
        self.assertEqual(exclusive["targetUserId"], "target")
        self.assertEqual(exclusive["createdAt"], 1001)

    def test_received_scan_limit_fails_explicitly_instead_of_returning_empty(self):
        self.table.scan.side_effect = lambda **kwargs: {"Items": [], "LastEvaluatedKey": {"id": "next"}}
        reply = self.call(self.received_event())
        self.assertEqual(reply["statusCode"], 503)
        self.assertNotIn("items", reply["body"])
