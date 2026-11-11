"""Pruebas sin credenciales ni llamadas a AWS."""

import importlib.util
import json
import os
from pathlib import Path
import sys
import types
import unittest
import io
import uuid
from decimal import Decimal
from unittest.mock import Mock, patch


class LambdaTests(unittest.TestCase):
    def setUp(self):
        self.table = Mock()
        self.table.name = "PlanYaPlanes"
        resource = Mock()
        resource.Table.return_value = self.table
        fake_boto3 = types.ModuleType("boto3")
        fake_boto3.resource = Mock(return_value=resource)
        path = Path(__file__).resolve().parents[1] / "lambda_function.py"
        spec = importlib.util.spec_from_file_location("planya_lambda", path)
        self.api = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"boto3": fake_boto3}):
            spec.loader.exec_module(self.api)
        self.s3 = Mock()
        self.photo_patch = patch.object(self.api, "s3_client", return_value=self.s3)
        self.photo_patch.start()
        self.addCleanup(self.photo_patch.stop)
        self.env = patch.dict(os.environ, {"TABLE_NAME": "PlanYaPlanes", "FOTOS_BUCKET": "planya-fotos-test"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_http_list_preserves_numbers_and_pagination(self):
        self.table.scan.return_value = {
            "Items": [{"id": "p1", "latitude": Decimal("-12.123"), "capacity": Decimal("5")}],
            "LastEvaluatedKey": {"id": "p1"},
        }
        event = {"requestContext": {}, "routeKey": "GET /planes",
                 "queryStringParameters": {"nextKey": "p0"}}
        response = self.api.lambda_handler(event, None)
        body = json.loads(response["body"])
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(body["data"]["items"][0]["latitude"], -12.123)
        self.assertEqual(body["data"]["nextKey"], "p1")
        self.table.scan.assert_called_once_with(Limit=25, ConsistentRead=True, ExclusiveStartKey={"id": "p0"})

    def test_http_does_not_execute_console_create(self):
        response = self.api.lambda_handler({"requestContext": {}, "routeKey": "POST /planes",
                                             "action": "create", "title": "No crear"}, None)
        self.assertEqual(response["statusCode"], 401)
        self.table.put_item.assert_not_called()

    def test_console_create_validates_and_stores(self):
        response = self.api.lambda_handler({"action": "create", "title": "  Cafe  ", "category": "CAFE"}, None)
        item = self.table.put_item.call_args.kwargs["Item"]
        self.assertEqual(item["title"], "Cafe")
        self.assertEqual(response["plan"]["id"], item["id"])
        self.assertIn("createdAt", item)

    def test_invalid_title_does_not_write(self):
        response = self.api.lambda_handler({"action": "create", "title": "   "}, None)
        self.assertIn("error", response)
        self.table.put_item.assert_not_called()

    def test_empty_list_has_null_next_key(self):
        self.table.scan.return_value = {"Items": []}
        response = self.api.lambda_handler({"action": "list"}, None)
        self.assertEqual(response["data"], {"items": [], "nextKey": None})

    def test_create_stores_location_photo_and_expiry(self):
        response = self.api.lambda_handler({"action": "create", "title": "Cafe",
            "latitude": -12.12, "longitude": -77.03, "meetingPlace": "Miraflores",
            "imageUrl": "https://example.com/cafe.jpg", "capacity": 5, "durationMinutes": 45}, None)
        plan = response["plan"]
        self.assertEqual(plan["latitude"], -12.12)
        self.assertEqual(self.table.put_item.call_args.kwargs["Item"]["latitude"], Decimal("-12.12"))
        json.dumps(response)
        self.assertEqual(plan["attendees"], [])
        created = self.api.datetime.fromisoformat(plan["createdAt"]).timestamp()
        self.assertEqual(plan["expiresAt"], int(created) + 45 * 60)

    def test_partial_or_out_of_range_coordinates_do_not_write(self):
        for fields in ({"latitude": -12.0}, {"latitude": 91, "longitude": 0},
                       {"latitude": True, "longitude": 0}, {"latitude": float("nan"), "longitude": 0}):
            response = self.api.lambda_handler({"action": "create", "title": "Cafe", **fields}, None)
            self.assertIn("error", response)
        self.table.put_item.assert_not_called()

    def test_invalid_duration_capacity_and_image_do_not_write(self):
        for fields in ({"durationMinutes": 5}, {"capacity": 0}, {"imageUrl": "http://example.com/a.jpg"}):
            self.assertIn("error", self.api.lambda_handler({"action": "create", "title": "Cafe", **fields}, None))
        self.table.put_item.assert_not_called()

    def test_get_plan_and_missing_or_expired_plan(self):
        event = {"requestContext": {}, "routeKey": "GET /planes/{id}", "pathParameters": {"id": "p1"}}
        self.table.get_item.return_value = {"Item": {"id": "p1", "title": "Cafe"}}
        response = self.api.lambda_handler(event, None)
        self.assertEqual(json.loads(response["body"])["data"]["id"], "p1")
        self.table.get_item.assert_called_once_with(Key={"id": "p1"}, ConsistentRead=True)
        for result in ({}, {"Item": {"id": "p1", "expiresAt": 1}}):
            self.table.get_item.return_value = result
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 404)

    def http_event(self, route, body=None, user="u1"):
        return {"requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": "id", "name": "Steve"}}}},
                "routeKey": route, "pathParameters": {"id": "p1"}, "body": json.dumps(body or {})}

    def test_redemption_dispatch_uses_integrated_module_and_low_level_client(self):
        module = types.ModuleType("benefit_redemption")
        module.redeem = Mock(return_value={"statusCode": 403})
        client = Mock()
        self.api.boto3.client = Mock(return_value=client)
        event = self.http_event("POST /canjes")
        with patch.dict(sys.modules, {"benefit_redemption": module}):
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 403)
        args = module.redeem.call_args.args
        self.assertIs(args[0], self.table)
        self.assertIs(args[1], event)
        self.assertIsInstance(args[2], int)
        self.assertIs(args[3], client)
        self.api.boto3.client.assert_called_once_with("dynamodb")

    def test_redemption_records_are_not_public_plans(self):
        for kind in ("BENEFIT_OPERATOR", "BENEFIT_REDEMPTION", "BENEFIT", "BENEFIT_CODE", "BENEFIT_OFFER", "BENEFIT_ISSUANCE"):
            self.assertFalse(self.api.is_plan_item({"id": "auxiliary", "entityType": kind}))

    def test_attendance_dispatch_uses_integrated_module(self):
        module = types.ModuleType("benefit_redemption")
        module.confirm_attendance = Mock(return_value={"statusCode": 403})
        client = Mock()
        self.api.boto3.client = Mock(return_value=client)
        event = self.http_event("POST /asistencias")
        with patch.dict(sys.modules, {"benefit_redemption": module}):
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 403)
        args = module.confirm_attendance.call_args.args
        self.assertIs(args[0], self.table)
        self.assertIs(args[1], event)
        self.assertIs(args[3], client)

    def active_plan(self):
        return {"id": "p1", "title": "Cafe", "ownerId": "u1", "category": "CAFE", "capacity": 3,
                "attendees": ["Steve"], "attendeeIds": ["u1"], "revision": 2, "expiresAt": 4102444800,
                "createdAt": "2026-11-05T00:00:00+00:00"}

    def test_authenticated_create_uses_verified_identity_and_validates_required_location(self):
        body = {"title": "Cafe", "latitude": -12.12, "longitude": -77.03, "capacity": 5,
                "meetingPlace": "Parque Kennedy", "authorName": "Nombre falso", "ownerId": "otra-persona"}
        response = self.api.lambda_handler(self.http_event("POST /planes", body), None)
        self.assertEqual(response["statusCode"], 201)
        plan = json.loads(response["body"])["data"]
        self.assertEqual(plan["ownerId"], "u1")
        self.assertEqual(plan["authorName"], "Steve")
        self.assertEqual(plan["attendeeIds"], ["u1"])
        self.assertEqual(self.api.lambda_handler(self.http_event("POST /planes", {"title": "Cafe"}), None)["statusCode"], 400)

    def test_edit_and_cancel_require_ownership(self):
        self.table.get_item.return_value = {"Item": self.active_plan()}
        for route in ("PUT /planes/{id}", "DELETE /planes/{id}"):
            self.assertEqual(self.api.lambda_handler(self.http_event(route, user="intruso"), None)["statusCode"], 403)
        self.table.put_item.assert_not_called()

    def test_join_is_idempotent_and_enforces_capacity(self):
        plan = self.active_plan()
        self.table.get_item.return_value = {"Item": plan}
        self.assertEqual(self.api.lambda_handler(self.http_event("POST /planes/{id}/participantes"), None)["statusCode"], 200)
        self.table.put_item.assert_not_called()
        self.assertEqual(self.api.lambda_handler(self.http_event("POST /planes/{id}/participantes", user="u2"), None)["statusCode"], 200)
        saved = self.table.put_item.call_args.kwargs
        self.assertEqual(saved["Item"]["attendeeIds"], ["u1", "u2"])
        self.assertIn("revision = :previous", saved["ConditionExpression"])
        self.assertIsInstance(saved["ExpressionAttributeNames"], dict)
        self.assertEqual(saved["ExpressionAttributeNames"]["#revision"], "revision")
        plan["capacity"] = len(plan["attendeeIds"])
        self.assertEqual(self.api.lambda_handler(self.http_event("POST /planes/{id}/participantes", user="u3"), None)["statusCode"], 409)

    def test_cancel_is_soft_and_edit_does_not_extend_expiry(self):
        plan = self.active_plan()
        self.table.get_item.return_value = {"Item": plan}
        body = {"title": "Cafe editado", "latitude": -12.12, "longitude": -77.03, "capacity": 5, "meetingPlace": "Parque Kennedy"}
        response = self.api.lambda_handler(self.http_event("PUT /planes/{id}", body), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(json.loads(response["body"])["data"]["expiresAt"], 4102444800)
        response = self.api.lambda_handler(self.http_event("DELETE /planes/{id}"), None)
        self.assertEqual(json.loads(response["body"])["data"]["status"], "CANCELLED")

    def test_leave_frees_the_correct_participant_and_host_cannot_leave(self):
        plan = self.active_plan()
        plan.update(attendeeIds=["u1", "u2"], attendees=["Steve", "Ana"])
        self.table.get_item.return_value = {"Item": plan}
        self.assertEqual(self.api.lambda_handler(self.http_event("DELETE /planes/{id}/participantes/me"), None)["statusCode"], 400)
        response = self.api.lambda_handler(self.http_event("DELETE /planes/{id}/participantes/me", user="u2"), None)
        self.assertEqual(json.loads(response["body"])["data"]["attendeeIds"], ["u1"])

    def test_list_hides_expired_and_cancelled_but_retains_pagination(self):
        self.table.scan.return_value = {"Items": [{"id": "old", "expiresAt": 1}, {"id": "cancel", "status": "CANCELLED"}, {"id": "p1"}],
                                       "LastEvaluatedKey": {"id": "cancel"}}
        result = self.api.list_plans(self.table)
        self.assertEqual(result["data"]["items"], [{"id": "p1"}])
        self.assertEqual(result["data"]["nextKey"], "cancel")

    def test_repeated_create_request_does_not_create_another_id(self):
        owner = {"sub": "u1", "name": "Steve"}
        body = {"title": "Cafe", "latitude": -12.12, "longitude": -77.03, "capacity": 5, "meetingPlace": "Parque Kennedy",
                "requestId": "9f98c4f1-e836-42ca-aec1-7a457e8a3db6"}
        self.assertEqual(self.api.build_plan(body, owner)["id"], self.api.build_plan(body, owner)["id"])

    def test_conditional_conflict_returns_retry_instead_of_overbooking(self):
        class Conflict(Exception):
            response = {"Error": {"Code": "ConditionalCheckFailedException"}}
        self.table.get_item.return_value = {"Item": self.active_plan()}
        self.table.put_item.side_effect = Conflict()
        response = self.api.lambda_handler(self.http_event("POST /planes/{id}/participantes", user="u2"), None)
        self.assertEqual(response["statusCode"], 409)

    def test_body_cannot_supply_verified_identity(self):
        event = self.http_event("POST /planes", {"sub": "u1", "token_use": "id"})
        event["requestContext"] = {}
        self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 401)
        self.table.put_item.assert_not_called()

    def photo_key(self, plan_id, user="u1"):
        return f"fotos/{self.api.photo_owner(user)}/{plan_id}/{uuid.uuid4()}.jpg"

    def photo_info(self):
        self.s3.head_object.return_value = {"ContentLength": 1024, "ContentType": "image/jpeg",
            "LastModified": self.api.datetime.now(self.api.timezone.utc)}
        self.s3.get_object.return_value = {"Body": io.BytesIO(b"\xff\xd8\xff")}

    def test_upload_requires_verified_login_and_rejects_size_or_type(self):
        event = self.http_event("POST /fotos/subida", {"size": 100, "contentType": "image/jpeg"})
        event["requestContext"] = {}
        self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 401)
        for size, mime in ((0, "image/jpeg"), (True, "image/jpeg"), (1_500_001, "image/jpeg"), (100, "text/html")):
            self.assertEqual(self.api.lambda_handler(self.http_event("POST /fotos/subida", {"size": size, "contentType": mime}), None)["statusCode"], 400)
        self.s3.generate_presigned_post.assert_not_called()

    def test_upload_is_bound_to_owner_and_draft_and_has_size_policy(self):
        request_id = str(uuid.uuid4())
        self.s3.generate_presigned_post.return_value = {"url": "https://planya-fotos-test.s3.amazonaws.com", "fields": {}}
        response = self.api.lambda_handler(self.http_event("POST /fotos/subida", {"size": 1024, "contentType": "image/jpeg", "requestId": request_id}), None)
        self.assertEqual(response["statusCode"], 200)
        data = json.loads(response["body"])["data"]
        self.assertTrue(self.api.valid_photo_key(data["imageKey"], "u1", self.api.plan_request_id("u1", request_id)))
        call = self.s3.generate_presigned_post.call_args.kwargs
        self.assertEqual(call["ExpiresIn"], 300)
        self.assertIn(["content-length-range", 1, 1_500_000], call["Conditions"])
        self.assertEqual(call["Fields"]["Cache-Control"], "private, no-store")
        self.table.put_item.assert_not_called()

    def test_upload_for_existing_plan_requires_owner(self):
        plan = self.active_plan()
        plan["id"] = str(uuid.uuid4())
        self.table.get_item.return_value = {"Item": plan}
        response = self.api.lambda_handler(self.http_event("POST /fotos/subida", {"size": 1024, "contentType": "image/jpeg", "planId": plan["id"]}, user="intruso"), None)
        self.assertEqual(response["statusCode"], 403)
        self.s3.generate_presigned_post.assert_not_called()

    def test_create_persists_only_key_and_returns_temporary_url(self):
        request_id = str(uuid.uuid4())
        plan_id = self.api.plan_request_id("u1", request_id)
        key = self.photo_key(plan_id)
        self.photo_info()
        self.s3.generate_presigned_url.return_value = "https://private.example/temporary"
        response = self.api.lambda_handler(self.http_event("POST /planes", {
            "title": "Cafe", "latitude": -12.12, "longitude": -77.03, "capacity": 5,
            "meetingPlace": "Parque", "requestId": request_id, "imageKey": key}), None)
        self.assertEqual(response["statusCode"], 201)
        stored = self.table.put_item.call_args.kwargs["Item"]
        self.assertEqual(stored["imageKey"], key)
        self.assertNotIn("imageUrl", stored)
        self.assertEqual(json.loads(response["body"])["data"]["imageUrl"], "https://private.example/temporary")
        self.assertEqual(response["headers"]["Cache-Control"], "no-store")

    def test_photo_cannot_be_taken_from_other_user_or_plan(self):
        request_id = str(uuid.uuid4())
        expected = self.api.plan_request_id("u1", request_id)
        for key in (self.photo_key(expected, "other"), self.photo_key(str(uuid.uuid4())), "../../photo.jpg"):
            response = self.api.lambda_handler(self.http_event("POST /planes", {
                "title": "Cafe", "latitude": -12.12, "longitude": -77.03, "capacity": 5,
                "meetingPlace": "Parque", "requestId": request_id, "imageKey": key, "_editingPlanId": key.split("/")[-2]}), None)
            self.assertEqual(response["statusCode"], 400)
        self.s3.head_object.assert_not_called()
        self.table.put_item.assert_not_called()

    def test_mime_and_size_and_jpeg_header_are_checked(self):
        plan_id = str(uuid.uuid4())
        key = self.photo_key(plan_id)
        for size, mime in ((1_500_001, "image/jpeg"), (100, "text/html")):
            self.s3.head_object.return_value = {"ContentLength": size, "ContentType": mime}
            with self.assertRaises(ValueError): self.api.check_photo(key, "u1", plan_id)
        self.s3.head_object.return_value = {"ContentLength": 100, "ContentType": "image/jpeg"}
        self.s3.get_object.return_value = {"Body": io.BytesIO(b"<ht")}
        with self.assertRaises(ValueError): self.api.check_photo(key, "u1", plan_id)

    def test_photo_urls_never_outlive_plan_and_expired_is_not_signed(self):
        plan = self.active_plan()
        plan["id"] = str(uuid.uuid4())
        plan["imageKey"] = self.photo_key(plan["id"])
        now = int(self.api.datetime.now(self.api.timezone.utc).timestamp())
        plan["expiresAt"] = now + 20
        self.api.public_plan(plan)
        self.assertLessEqual(self.s3.generate_presigned_url.call_args.kwargs["ExpiresIn"], 20)
        self.s3.reset_mock()
        plan["expiresAt"] = now - 1
        self.assertNotIn("imageUrl", self.api.public_plan(plan))
        self.s3.generate_presigned_url.assert_not_called()

    def test_edit_deletes_old_photo_only_after_successful_write(self):
        plan = self.active_plan()
        plan["id"] = str(uuid.uuid4())
        old = self.photo_key(plan["id"])
        plan["imageKey"] = old
        self.table.get_item.return_value = {"Item": plan}
        event = self.http_event("PUT /planes/{id}", {"title": "Cafe", "latitude": -12.12,
            "longitude": -77.03, "capacity": 5, "meetingPlace": "Parque"})
        event["pathParameters"]["id"] = plan["id"]
        response = self.api.lambda_handler(event, None)
        self.assertEqual(response["statusCode"], 200)
        self.assertNotIn("imageKey", self.table.put_item.call_args.kwargs["Item"])
        self.s3.delete_object.assert_called_once_with(Bucket="planya-fotos-test", Key=old)

    def test_conflicted_write_does_not_delete_photo(self):
        class Conflict(Exception):
            response = {"Error": {"Code": "ConditionalCheckFailedException"}}
        plan = self.active_plan()
        plan["id"] = str(uuid.uuid4())
        plan["imageKey"] = self.photo_key(plan["id"])
        self.table.get_item.return_value = {"Item": plan}
        self.table.put_item.side_effect = Conflict()
        event = self.http_event("DELETE /planes/{id}")
        event["pathParameters"]["id"] = plan["id"]
        self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 409)
        self.s3.delete_object.assert_not_called()

    def test_console_photo_connection_is_read_only(self):
        result = self.api.lambda_handler({"action": "photoConnection"}, None)
        self.assertTrue(result["success"])
        self.s3.head_bucket.assert_called_once_with(Bucket="planya-fotos-test")
        self.s3.generate_presigned_post.assert_not_called()
        self.table.put_item.assert_not_called()

    def test_abandoned_old_upload_cannot_be_published_but_existing_photo_can_be_kept(self):
        plan_id = str(uuid.uuid4())
        key = self.photo_key(plan_id)
        self.photo_info()
        self.s3.head_object.return_value["LastModified"] = self.api.datetime.fromtimestamp(1, self.api.timezone.utc)
        with self.assertRaises(ValueError): self.api.check_photo(key, "u1", plan_id)
        self.api.check_photo(key, "u1", plan_id, fresh=False)

    def test_stale_editor_revision_cannot_overwrite_newer_plan(self):
        self.table.get_item.return_value = {"Item": self.active_plan()}
        response = self.api.lambda_handler(self.http_event("PUT /planes/{id}", {"expectedRevision": 1}), None)
        self.assertEqual(response["statusCode"], 409)
        self.table.put_item.assert_not_called()
        self.s3.delete_object.assert_not_called()

    def test_invalid_editor_revision_is_rejected_without_writing(self):
        self.table.get_item.return_value = {"Item": self.active_plan()}
        for expected in (True, -1, "2", 2.0):
            response = self.api.lambda_handler(self.http_event("PUT /planes/{id}", {"expectedRevision": expected}), None)
            self.assertEqual(response["statusCode"], 400)
        self.table.put_item.assert_not_called()

    def test_matching_editor_revision_preserves_timing_and_participants(self):
        plan = self.active_plan()
        plan.update(startsAt=4102440000, attendeeIds=["u1", "u2"], attendees=["Steve", "Ana"])
        self.table.get_item.return_value = {"Item": plan}
        response = self.api.lambda_handler(self.http_event("PUT /planes/{id}", {
            "title": "Nuevo título", "latitude": -12.12, "longitude": -77.03,
            "capacity": 2, "meetingPlace": "Parque", "expectedRevision": 2,
            "startsAt": 1, "expiresAt": 9999999999, "attendeeIds": ["intruso"]}), None)
        data = json.loads(response["body"])["data"]
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(data["revision"], 3)
        self.assertEqual(data["startsAt"], 4102440000)
        self.assertEqual(data["expiresAt"], 4102444800)
        self.assertEqual(data["attendeeIds"], ["u1", "u2"])

    def test_expired_plan_rejects_every_mutation(self):
        for route in ("PUT /planes/{id}", "DELETE /planes/{id}", "POST /planes/{id}/participantes", "DELETE /planes/{id}/participantes/me"):
            plan = self.active_plan()
            plan["expiresAt"] = 1
            self.table.get_item.return_value = {"Item": plan}
            self.assertEqual(self.api.lambda_handler(self.http_event(route), None)["statusCode"], 404)
        self.table.put_item.assert_not_called()

    def test_edit_cannot_reduce_capacity_below_attendees(self):
        plan = self.active_plan()
        plan.update(attendeeIds=["u1", "u2", "u3"], attendees=["Steve", "Ana", "Luis"])
        self.table.get_item.return_value = {"Item": plan}
        response = self.api.lambda_handler(self.http_event("PUT /planes/{id}", {
            "title": "Cafe", "latitude": -12, "longitude": -77, "capacity": 2, "meetingPlace": "Parque"}), None)
        self.assertEqual(response["statusCode"], 400)
        self.table.put_item.assert_not_called()

    def gallery_event(self, count=4):
        request_id = str(uuid.uuid4())
        plan_id = self.api.plan_request_id("u1", request_id)
        keys = [self.photo_key(plan_id) for _ in range(count)]
        return self.http_event("POST /planes", {"title": "Galeria", "latitude": -12,
            "longitude": -77, "capacity": 5, "meetingPlace": "Parque", "requestId": request_id,
            "photos": [{"imageKey": key} for key in keys]}), keys

    def test_gallery_stores_four_keys_and_signs_each_without_storing_urls(self):
        event, keys = self.gallery_event()
        self.photo_info()
        self.s3.get_object.side_effect = lambda **kwargs: {"Body": io.BytesIO(b"\xff\xd8\xff")}
        self.s3.generate_presigned_url.side_effect = lambda operation, **kwargs: "https://private.example/" + kwargs["Params"]["Key"]
        response = self.api.lambda_handler(event, None)
        self.assertEqual(response["statusCode"], 201)
        stored = self.table.put_item.call_args.kwargs["Item"]
        self.assertEqual([item["imageKey"] for item in stored["photos"]], keys)
        self.assertTrue(all("imageUrl" not in item for item in stored["photos"]))
        data = json.loads(response["body"])["data"]
        self.assertEqual(len(data["photos"]), 4)
        self.assertEqual(data["imageUrl"], data["photos"][0]["imageUrl"])
        self.assertEqual(self.s3.generate_presigned_url.call_count, 4)

    def test_gallery_rejects_five_photos_duplicates_and_foreign_keys(self):
        for mode in ("five", "duplicate", "foreign", "invalid"):
            event, keys = self.gallery_event(5 if mode == "five" else 2)
            body = json.loads(event["body"])
            if mode == "duplicate": body["photos"][1] = body["photos"][0]
            if mode == "foreign": body["photos"][1]["imageKey"] = self.photo_key(str(uuid.uuid4()), "intruder")
            if mode == "invalid": body["photos"] = "not-a-list"
            event["body"] = json.dumps(body)
            self.photo_info()
            self.s3.get_object.side_effect = lambda **kwargs: {"Body": io.BytesIO(b"\xff\xd8\xff")}
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 400)
        self.table.put_item.assert_not_called()

    def test_gallery_removal_deletes_only_removed_key_and_can_clear_all(self):
        plan = self.active_plan()
        plan["id"] = str(uuid.uuid4())
        keys = [self.photo_key(plan["id"]) for _ in range(4)]
        plan.update(photos=[{"imageKey": key} for key in keys], imageKey=keys[0])
        self.table.get_item.return_value = {"Item": plan}
        self.photo_info()
        self.s3.get_object.side_effect = lambda **kwargs: {"Body": io.BytesIO(b"\xff\xd8\xff")}
        self.s3.generate_presigned_url.return_value = "https://private.example/photo"
        event = self.http_event("PUT /planes/{id}", {"title": "Galeria", "latitude": -12, "longitude": -77,
            "capacity": 5, "meetingPlace": "Parque", "photos": [{"imageKey": key} for key in keys[1:]]})
        event["pathParameters"]["id"] = plan["id"]
        self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 200)
        self.s3.delete_object.assert_called_once_with(Bucket="planya-fotos-test", Key=keys[0])
        self.assertEqual(plan["imageKey"], keys[1])
        body = json.loads(event["body"]); body["photos"] = []; event["body"] = json.dumps(body)
        self.s3.delete_object.reset_mock()
        self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 200)
        self.assertEqual(self.s3.delete_object.call_count, 3)
        self.assertEqual(plan["photos"], [])
        self.assertNotIn("imageKey", plan)

    def test_gallery_conflict_does_not_delete_any_photo(self):
        class Conflict(Exception):
            response = {"Error": {"Code": "ConditionalCheckFailedException"}}
        plan = self.active_plan(); plan["id"] = str(uuid.uuid4())
        plan["photos"] = [{"imageKey": self.photo_key(plan["id"])} for _ in range(4)]
        self.table.get_item.return_value = {"Item": plan}
        self.table.put_item.side_effect = Conflict()
        self.assertEqual(self.api.lambda_handler(self.http_event("DELETE /planes/{id}"), None)["statusCode"], 409)
        self.s3.delete_object.assert_not_called()

    def test_expired_gallery_does_not_expose_urls_and_legacy_clients_cannot_drop_it(self):
        plan = self.active_plan(); plan["id"] = str(uuid.uuid4())
        plan["photos"] = [{"imageKey": self.photo_key(plan["id"])} for _ in range(4)]
        self.table.get_item.return_value = {"Item": plan}
        self.assertEqual(self.api.lambda_handler(self.http_event("PUT /planes/{id}"), None)["statusCode"], 409)
        self.table.put_item.assert_not_called()
        plan["expiresAt"] = 1
        result = self.api.public_plan(plan)
        self.assertTrue(all("imageUrl" not in photo for photo in result["photos"]))
        self.s3.generate_presigned_url.assert_not_called()

    def test_profile_routes_require_verified_id_token(self):
        for route in ("GET /perfil", "PUT /perfil", "POST /perfil/foto"):
            event = self.http_event(route)
            event["requestContext"] = {}
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 401)
        self.table.put_item.assert_not_called()

    def test_profile_default_is_private_and_does_not_write(self):
        self.table.get_item.return_value = {}
        event = self.http_event("GET /perfil", {"userId": "other"})
        event["requestContext"]["authorizer"]["jwt"]["claims"]["email"] = "steve@example.com"
        response = self.api.lambda_handler(event, None)
        data = json.loads(response["body"])["data"]
        self.assertEqual(data["userId"], "u1")
        self.assertEqual(data["name"], "Steve")
        self.assertFalse(data["saved"])
        self.table.get_item.assert_called_once_with(Key={"id": "perfil#u1"}, ConsistentRead=True)
        self.table.put_item.assert_not_called()

    def test_profile_saves_name_and_interests_without_modifying_email_or_expiry(self):
        self.table.get_item.return_value = {}
        response = self.api.lambda_handler(self.http_event("PUT /perfil", {"name": "  Ana  ", "expectedRevision": 0,
            "userId": "other", "email": "fake@example.com", "interests": ["CAFE"]}), None)
        self.assertEqual(response["statusCode"], 200)
        saved = self.table.put_item.call_args.kwargs["Item"]
        self.assertEqual(saved["id"], "perfil#u1")
        self.assertEqual(saved["name"], "Ana")
        self.assertEqual(saved["revision"], 1)
        self.assertNotIn("expiresAt", saved)
        self.assertNotIn("email", saved)

    def test_profile_records_are_never_public_plans(self):
        profile = {"id": "perfil#u1", "entityType": "PROFILE", "name": "Ana", "userId": "u1", "revision": 1}
        attendance = {"id": "asistencia#p1#u1", "entityType": "ATTENDANCE", "userId": "u1"}
        rating = {"id": "valoracion#p1#u2#u1", "entityType": "RATING", "targetUserId": "u1"}
        self.table.scan.return_value = {"Items": [profile, attendance, rating, {"id": "p1", "title": "Cafe"}], "LastEvaluatedKey": {"id": profile["id"]}}
        result = self.api.list_plans(self.table)
        self.assertEqual([item["id"] for item in result["data"]["items"]], ["p1"])
        self.assertEqual(result["data"]["nextKey"], "perfil#u1")
        self.table.get_item.return_value = {"Item": profile}
        self.assertEqual(self.api.get_plan(self.table, "perfil#u1")["statusCode"], 404)
        self.table.get_item.return_value = {"Item": attendance}
        self.assertEqual(self.api.get_plan(self.table, "asistencia#p1#u1")["statusCode"], 404)

    def test_profile_upload_is_staged_under_temporary_prefix(self):
        self.s3.generate_presigned_post.return_value = {"url": "https://private.example", "fields": {}}
        response = self.api.lambda_handler(self.http_event("POST /perfil/foto", {"size": 1000, "contentType": "image/jpeg"}), None)
        self.assertEqual(response["statusCode"], 200)
        key = json.loads(response["body"])["data"]["imageKey"]
        self.assertTrue(key.startswith("fotos/perfiles/"))
        self.assertTrue(self.api.valid_avatar_key(key, "u1", pending=True))
        self.assertFalse(self.api.valid_avatar_key(key, "other", pending=True))
        self.assertIn(["content-length-range", 1, 1_500_000], self.s3.generate_presigned_post.call_args.kwargs["Conditions"])
        self.table.put_item.assert_not_called()

    def test_profile_photo_moves_to_persistent_prefix_only_when_saved(self):
        old = f"perfiles/{self.api.photo_owner('u1')}/{uuid.uuid4()}.jpg"
        pending = f"fotos/perfiles/{self.api.photo_owner('u1')}/{uuid.uuid4()}.jpg"
        self.table.get_item.return_value = {"Item": {"id": "perfil#u1", "entityType": "PROFILE", "userId": "u1", "name": "Steve", "revision": 2, "avatarKey": old}}
        self.photo_info()
        self.s3.generate_presigned_url.return_value = "https://private.example/avatar"
        response = self.api.lambda_handler(self.http_event("PUT /perfil", {"name": "Ana", "expectedRevision": 2, "avatarKey": pending}), None)
        self.assertEqual(response["statusCode"], 200)
        copied = self.s3.copy_object.call_args.kwargs
        self.assertTrue(copied["Key"].startswith("perfiles/"))
        self.assertEqual(copied["CopySource"]["Key"], pending)
        self.assertEqual(self.table.put_item.call_args.kwargs["Item"]["avatarKey"], copied["Key"])
        deleted = {call.kwargs["Key"] for call in self.s3.delete_object.call_args_list}
        self.assertEqual(deleted, {old, pending})
        self.assertNotIn(copied["Key"], deleted)

    def test_profile_conflict_does_not_remove_existing_avatar(self):
        class Conflict(Exception):
            response = {"Error": {"Code": "ConditionalCheckFailedException"}}
        old = f"perfiles/{self.api.photo_owner('u1')}/{uuid.uuid4()}.jpg"
        pending = f"fotos/perfiles/{self.api.photo_owner('u1')}/{uuid.uuid4()}.jpg"
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "userId": "u1", "revision": 1, "avatarKey": old}}
        self.photo_info(); self.table.put_item.side_effect = Conflict()
        response = self.api.lambda_handler(self.http_event("PUT /perfil", {"name": "Ana", "expectedRevision": 1, "avatarKey": pending}), None)
        self.assertEqual(response["statusCode"], 409)
        copied = self.s3.copy_object.call_args.kwargs["Key"]
        self.s3.delete_object.assert_called_once_with(Bucket="planya-fotos-test", Key=copied)

    def test_profile_rejects_stale_revision_and_invalid_name(self):
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "userId": "u1", "revision": 2}}
        self.assertEqual(self.api.lambda_handler(self.http_event("PUT /perfil", {"name": "Ana", "expectedRevision": 1}), None)["statusCode"], 409)
        for name in ("", "a" * 81, "Ana\nOtra", 5):
            self.assertEqual(self.api.lambda_handler(self.http_event("PUT /perfil", {"name": name, "expectedRevision": 2}), None)["statusCode"], 400)
        self.table.put_item.assert_not_called()

    def test_profile_removal_keeps_name_and_deletes_saved_avatar_after_write(self):
        old = f"perfiles/{self.api.photo_owner('u1')}/{uuid.uuid4()}.jpg"
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "userId": "u1", "revision": 1, "avatarKey": old}}
        response = self.api.lambda_handler(self.http_event("PUT /perfil", {"name": "Ana", "expectedRevision": 1, "removeAvatar": True}), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertNotIn("avatarKey", self.table.put_item.call_args.kwargs["Item"])
        self.s3.delete_object.assert_called_once_with(Bucket="planya-fotos-test", Key=old)

    def test_new_plan_uses_saved_planya_name_instead_of_old_google_claim(self):
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "userId": "u1", "name": "Nombre PlanYa"}}
        response = self.api.lambda_handler(self.http_event("POST /planes", {"title": "Cafe", "latitude": -12,
            "longitude": -77, "capacity": 5, "meetingPlace": "Parque"}), None)
        self.assertEqual(json.loads(response["body"])["data"]["authorName"], "Nombre PlanYa")

    def test_profile_activity_counts_only_own_plans_and_accredited_attendance(self):
        self.table.scan.return_value = {"Items": [
            {"id": "p1", "ownerId": "u1", "attendeeIds": ["u1"]},
            {"id": "p2", "ownerId": "other", "attendeeIds": ["other", "u1"]},
            {"id": "p3", "ownerId": "other", "attendeeIds": ["other"]},
            {"id": "perfil#u1", "entityType": "PROFILE", "userId": "u1"},
            {"id": "asistencia#p2#u1", "entityType": "ATTENDANCE", "userId": "u1"},
            {"id": "asistencia#p2#other", "entityType": "ATTENDANCE", "userId": "other"},
            {"id": "valoracion#p2#other#u1", "entityType": "RATING", "targetUserId": "u1"},
        ]}
        response = self.api.lambda_handler(self.http_event("GET /perfil/actividad"), None)
        self.assertEqual(response["statusCode"], 200)
        data = json.loads(response["body"])["data"]
        self.assertEqual(data["userId"], "u1")
        self.assertEqual(data["organized"], 1)
        self.assertEqual(data["joined"], 1)
        self.assertEqual(data["attended"], 1)
        self.table.scan.assert_called_once_with(ConsistentRead=True)
        self.table.put_item.assert_not_called()

    def test_profile_activity_paginates_and_requires_id_token(self):
        self.table.scan.side_effect = [
            {"Items": [{"id": "p1", "ownerId": "u1", "attendeeIds": ["u1"]}], "LastEvaluatedKey": {"id": "p1"}},
            {"Items": [{"id": "asistencia#p1#u1", "entityType": "ATTENDANCE", "userId": "u1"}]},
        ]
        response = self.api.lambda_handler(self.http_event("GET /perfil/actividad"), None)
        data = json.loads(response["body"])["data"]
        self.assertEqual((data["organized"], data["joined"], data["attended"]), (1, 0, 1))
        self.assertEqual(self.table.scan.call_args_list[1].kwargs["ExclusiveStartKey"], {"id": "p1"})
        unauthorized = self.http_event("GET /perfil/actividad")
        unauthorized["requestContext"] = {}
        self.assertEqual(self.api.lambda_handler(unauthorized, None)["statusCode"], 401)

    def test_profile_confidence_is_empty_until_real_ratings_exist(self):
        self.table.get_item.return_value = {}
        response = self.api.lambda_handler(self.http_event("GET /perfil/confianza"), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(json.loads(response["body"])["data"], {"userId": "u1", "count": 0, "average": None})
        self.table.get_item.assert_called_once_with(Key={"id": "perfil#u1"}, ConsistentRead=True)

    def test_profile_confidence_uses_server_aggregate_and_rejects_invalid_values(self):
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "ratingCount": 3, "ratingSum": 13.5}}
        data = json.loads(self.api.lambda_handler(self.http_event("GET /perfil/confianza"), None)["body"])["data"]
        self.assertEqual(data, {"userId": "u1", "count": 3, "average": 4.5})
        for profile in ({"ratingCount": 0, "ratingSum": 10}, {"ratingCount": 2, "ratingSum": 0},
                        {"ratingCount": 2, "ratingSum": 99}, {"ratingCount": "dos", "ratingSum": "diez"}):
            self.table.get_item.return_value = {"Item": profile}
            data = json.loads(self.api.lambda_handler(self.http_event("GET /perfil/confianza"), None)["body"])["data"]
            self.assertEqual(data, {"userId": "u1", "count": 0, "average": None})
        unauthorized = self.http_event("GET /perfil/confianza")
        unauthorized["requestContext"] = {}
        self.assertEqual(self.api.lambda_handler(unauthorized, None)["statusCode"], 401)

    def test_profile_verification_is_server_authorized_and_google_never_verifies(self):
        self.table.get_item.return_value = {}
        event = self.http_event("GET /perfil")
        event["requestContext"]["authorizer"]["jwt"]["claims"]["email_verified"] = "true"
        data = json.loads(self.api.lambda_handler(event, None)["body"])["data"]
        self.assertEqual(data["verification"], {"status": "NONE"})
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "verificationStatus": "VERIFIED",
            "verificationMethod": "equipo", "verifiedAt": "2026-11-09T00:00:00+00:00"}}
        data = json.loads(self.api.lambda_handler(self.http_event("GET /perfil"), None)["body"])["data"]
        self.assertEqual(data["verification"], {"status": "VERIFIED", "method": "equipo",
                                                 "verifiedAt": "2026-11-09T00:00:00+00:00"})
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "verificationStatus": "INVENTED"}}
        data = json.loads(self.api.lambda_handler(self.http_event("GET /perfil"), None)["body"])["data"]
        self.assertEqual(data["verification"], {"status": "NONE"})

    def test_profile_rejects_client_supplied_trust_fields(self):
        self.table.get_item.return_value = {"Item": {"entityType": "PROFILE", "revision": 1}}
        for field, value in (("verified", True), ("verificationStatus", "VERIFIED"), ("ratingCount", 5),
                             ("ratingSum", 25), ("activity", {"organized": 99})):
            response = self.api.lambda_handler(self.http_event("PUT /perfil", {
                "name": "Ana", "expectedRevision": 1, field: value}), None)
            self.assertEqual(response["statusCode"], 400)
        self.table.put_item.assert_not_called()


    def review_benefit(self, **changes):
        item = {"id": "beneficio#review", "entityType": "BENEFIT", "userId": "u1",
                "merchantName": "Local de prueba", "title": "Cupon de prueba", "status": "DISPONIBLE",
                "validUntil": int(self.api.datetime.now(self.api.timezone.utc).timestamp()) + 60}
        item.update(changes)
        self.table.get_item.return_value = {"Item": item}
        return item

    def review_benefit_event(self, route):
        event = self.http_event(route)
        event["pathParameters"] = {"id": "review"}
        return event

    def test_integration_private_entities_never_become_public_plans(self):
        for entity in ("PROFILE", "ATTENDANCE", "RATING", "BENEFIT", "BENEFIT_CODE", "CHAT_MESSAGE"):
            item = {"id": "private-record", "entityType": entity, "code": "FAKE-PRIVATE", "expiresAt": 9999999999}
            self.table.scan.return_value = {"Items": [item]}
            self.table.get_item.return_value = {"Item": item}
            self.assertEqual(self.api.list_plans(self.table)["data"]["items"], [], entity)
            self.assertEqual(self.api.get_plan(self.table, item["id"])["statusCode"], 404, entity)
        for prefix in ("perfil#", "beneficio#", "codigo#", "asistencia#", "valoracion#"):
            self.assertFalse(self.api.is_plan_item({"id": prefix + "legacy"}))

    def test_integration_edit_profile_preserves_server_trust_and_rating(self):
        trusted = {"verificationStatus": "VERIFIED", "verificationMethod": "equipo",
                   "verifiedAt": "2026-11-09T00:00:00Z", "verifiedBy": "operator",
                   "ratingCount": 2, "ratingSum": Decimal("9")}
        self.table.get_item.return_value = {"Item": {"id": "perfil#u1", "entityType": "PROFILE",
            "userId": "u1", "revision": 3, "name": "Antes", **trusted}}
        response = self.api.lambda_handler(self.http_event("PUT /perfil", {
            "name": "Nuevo nombre", "expectedRevision": 3, "interests": ["CAFE"]}), None)
        self.assertEqual(response["statusCode"], 200)
        written = self.table.put_item.call_args.kwargs["Item"]
        for key, value in trusted.items():
            self.assertEqual(written[key], value)
        self.assertEqual(written["revision"], 4)
        self.assertEqual(json.loads(response["body"])["data"]["verification"]["status"], "VERIFIED")

    def test_integration_benefits_require_identity(self):
        for route in ("GET /beneficios", "GET /beneficios/{id}", "POST /beneficios/{id}/codigo"):
            event = self.review_benefit_event(route)
            event["requestContext"] = {}
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 401)
        self.table.put_item.assert_not_called()

    def test_integration_benefit_cannot_be_read_or_coded_by_another_user(self):
        self.review_benefit(userId="other")
        for route in ("GET /beneficios/{id}", "POST /beneficios/{id}/codigo"):
            self.assertEqual(self.api.lambda_handler(self.review_benefit_event(route), None)["statusCode"], 403)
        self.table.put_item.assert_not_called()

    def test_integration_benefit_code_never_outlives_coupon(self):
        item = self.review_benefit()
        response = self.api.lambda_handler(self.review_benefit_event("POST /beneficios/{id}/codigo"), None)
        self.assertEqual(response["statusCode"], 200)
        data = json.loads(response["body"])["data"]
        self.assertEqual(data["expiresAt"], item["validUntil"])
        self.assertEqual(self.table.put_item.call_args.kwargs["Item"]["ttl"], item["validUntil"])
        self.assertNotIn("u1", data["code"])

    def test_integration_benefit_invalid_states_do_not_issue_codes(self):
        for changes, expected in (({"status": "USADO"}, 409), ({"validUntil": 1}, 410),
                                  ({"status": "VENCIDO"}, 410), ({"status": "REVOCADO"}, 409)):
            self.review_benefit(**changes)
            response = self.api.lambda_handler(self.review_benefit_event("POST /beneficios/{id}/codigo"), None)
            self.assertEqual(response["statusCode"], expected)
        self.table.put_item.assert_not_called()

    def test_integration_expired_benefit_is_reported_expired_without_background_writer(self):
        self.review_benefit(validUntil=1)
        response = self.api.lambda_handler(self.review_benefit_event("GET /beneficios/{id}"), None)
        self.assertEqual(json.loads(response["body"])["data"]["status"], "VENCIDO")

    def test_integration_benefit_list_filters_expiration_on_server(self):
        self.table.scan.return_value = {"Items": []}
        for status in ("DISPONIBLE", "USADO", "VENCIDO"):
            event = self.review_benefit_event("GET /beneficios")
            event["queryStringParameters"] = {"estado": status}
            self.assertEqual(self.api.lambda_handler(event, None)["statusCode"], 200)
            query = self.table.scan.call_args.kwargs
            self.assertEqual(query["ExpressionAttributeValues"][":uid"], "u1")
            self.assertEqual(":now" in query["ExpressionAttributeValues"], status != "USADO")
            if status == "VENCIDO":
                self.assertEqual(query["ExpressionAttributeValues"][":available"], "DISPONIBLE")
                self.assertIn("validUntil <= :now", query["FilterExpression"])


if __name__ == "__main__":
    unittest.main()
