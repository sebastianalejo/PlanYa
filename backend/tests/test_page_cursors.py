"""Las claves internas nunca se devuelven en continuaciones HTTP."""

import copy
import json
import unittest
import uuid
from unittest.mock import Mock, patch
import test_lambda_function as core


class PageCursorTests(unittest.TestCase):
    def setUp(self):
        core.LambdaTests.setUp(self)
        self.saved = {}
        self.cursors = Mock()
        self.cursors.put_item.side_effect = lambda Item: self.saved.update({Item["planId"]: copy.deepcopy(Item)})
        self.cursors.get_item.side_effect = lambda Key, ConsistentRead: {"Item": self.saved.get(Key["planId"], {})}
        cursor_patch = patch.object(self.api, "page_cursor_table", return_value=self.cursors)
        cursor_patch.start()
        self.addCleanup(cursor_patch.stop)
        self.table.scan.return_value = {"Items": [], "LastEvaluatedKey": {"id": "codigo#PRIVATE-CODE"}}

    def event(self, route="GET /planes", cursor=None, user="u1", status="DISPONIBLE"):
        event = {"requestContext": {}, "routeKey": route, "queryStringParameters": {"estado": status}}
        if route == "GET /beneficios":
            event["requestContext"] = {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id"}}}}
        if cursor is not None:
            event["queryStringParameters"]["nextKey"] = cursor
        return event

    def call(self, event=None):
        return self.api.lambda_handler(event or self.event(), None)

    def token(self, response):
        self.assertEqual(response["statusCode"], 200)
        return json.loads(response["body"])["data"]["nextKey"]

    def test_public_empty_page_exposes_only_opaque_uuid_and_can_continue(self):
        response = self.call()
        cursor = self.token(response)
        self.assertEqual(str(uuid.UUID(cursor)), cursor)
        self.assertNotIn("PRIVATE-CODE", response["body"])
        self.assertNotIn("codigo#", response["body"])
        self.assertEqual(self.call(self.event(cursor=cursor))["statusCode"], 200)
        self.assertEqual(self.table.scan.call_args.kwargs["ExclusiveStartKey"], {"id": "codigo#PRIVATE-CODE"})

    def test_private_key_and_other_account_metadata_are_not_in_public_page(self):
        self.table.scan.return_value["Items"] = [{"id": "perfil#private-user", "entityType": "PROFILE"},
            {"id": "codigo#PRIVATE-CODE", "entityType": "BENEFIT_CODE", "code": "PRIVATE-CODE"}]
        response = self.call()
        self.assertNotIn("private-user", response["body"])
        self.assertNotIn("PRIVATE-CODE", response["body"])
        self.assertEqual(json.loads(response["body"])["data"]["items"], [])

    def test_benefit_cursor_bound_to_account_filter_and_route(self):
        cursor = self.token(self.call(self.event("GET /beneficios")))
        for event in (self.event("GET /beneficios", cursor, user="other"),
                      self.event("GET /beneficios", cursor, status="USADO"), self.event(cursor=cursor)):
            self.assertEqual(self.call(event)["statusCode"], 400)
        self.assertEqual(self.table.scan.call_count, 1)
        self.assertEqual(self.call(self.event("GET /beneficios", cursor))["statusCode"], 200)

    def test_public_cursor_cannot_be_used_for_private_list(self):
        cursor = self.token(self.call())
        self.assertEqual(self.call(self.event("GET /beneficios", cursor))["statusCode"], 400)

    def test_expiry_checked_before_ttl_cleanup_and_retries_restart_explicitly(self):
        cursor = self.token(self.call())
        self.saved["page#" + cursor]["expiresAt"] = 0
        response = self.call(self.event(cursor=cursor))
        self.assertEqual(response["statusCode"], 409)
        self.assertEqual(json.loads(response["body"])["code"], "CURSOR_EXPIRED")
        self.assertEqual(self.table.scan.call_count, 1)
        self.assertEqual(self.call()["statusCode"], 200)

    def test_old_raw_unknown_and_empty_cursors_do_not_reach_scan(self):
        for value in ("codigo#PRIVATE-CODE", "", "x" * 1000, str(uuid.uuid4())):
            response = self.call(self.event(cursor=value))
            self.assertEqual(response["statusCode"], 400)
            self.assertEqual(json.loads(response["body"])["code"], "CURSOR_INVALID")
        self.table.scan.assert_not_called()

    def test_last_page_does_not_read_or_write_cursor_table(self):
        self.table.scan.return_value = {"Items": []}
        self.assertIsNone(self.token(self.call()))
        self.assertIsNone(self.token(self.call(self.event("GET /beneficios"))))
        self.cursors.get_item.assert_not_called()
        self.cursors.put_item.assert_not_called()

    def test_cursor_is_replayable_within_ttl_and_storage_failure_never_falls_back_to_raw_key(self):
        cursor = self.token(self.call())
        self.assertEqual(self.call(self.event(cursor=cursor))["statusCode"], 200)
        self.assertEqual(self.call(self.event(cursor=cursor))["statusCode"], 200)
        self.cursors.put_item.side_effect = RuntimeError("simulated storage outage")
        with self.assertRaises(RuntimeError):
            self.call()

    def test_private_rows_rechecked_even_if_scan_ignores_filter(self):
        self.table.scan.return_value["Items"] = [
            {"id": "beneficio#owned", "entityType": "BENEFIT", "userId": "u1", "status": "DISPONIBLE"},
            {"id": "beneficio#foreign", "entityType": "BENEFIT", "userId": "other", "status": "DISPONIBLE"},
            {"id": "beneficio#used", "entityType": "BENEFIT", "userId": "u1", "status": "USADO"},
            {"id": "codigo#PRIVATE-CODE", "entityType": "BENEFIT_CODE"}]
        response = self.call(self.event("GET /beneficios"))
        items = json.loads(response["body"])["data"]["items"]
        self.assertEqual([item["id"] for item in items], ["owned"])
        self.assertNotIn("PRIVATE-CODE", response["body"])
        self.assertTrue(self.table.scan.call_args.kwargs["ConsistentRead"])

    def test_internal_primary_key_larger_than_old_public_limit_can_resume(self):
        raw = "oferta#" + "x" * 200
        self.table.scan.return_value["LastEvaluatedKey"] = {"id": raw}
        cursor = self.token(self.call())
        self.assertNotIn(raw, self.call()["body"])
        self.assertEqual(self.call(self.event(cursor=cursor))["statusCode"], 200)
        self.assertEqual(self.table.scan.call_args.kwargs["ExclusiveStartKey"], {"id": raw})
