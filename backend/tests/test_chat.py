"""Pruebas del chat temporal (US-08) sin credenciales ni llamadas a AWS.

Usan tablas en memoria que imitan GetItem, PutItem condicional y Query de DynamoDB.
El reloj se inyecta (now_ms); no se cambia la hora del equipo.
"""

import copy
import importlib.util
import json
import os
import sys
import types
import time
import unittest
import uuid
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock, patch

BACKEND = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, BACKEND / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ConditionalCheckFailed(Exception):
    def __init__(self):
        super().__init__("ConditionalCheckFailedException")
        self.response = {"Error": {"Code": "ConditionalCheckFailedException"}}


class FakePlans:
    """Tabla PlanYaPlanes: clave id."""

    def __init__(self, *items):
        self.items = {item["id"]: copy.deepcopy(item) for item in items}
        self.name = "PlanYaPlanes"

    def get_item(self, Key, ConsistentRead=False):
        item = self.items.get(Key["id"])
        return {"Item": copy.deepcopy(item)} if item else {}

    def scan(self, Limit, ConsistentRead, FilterExpression, ExpressionAttributeNames,
             ExpressionAttributeValues, ProjectionExpression, ExclusiveStartKey=None):
        assert ConsistentRead is True
        assert ProjectionExpression == "id"
        assert "contains(#members, :user)" in FilterExpression
        assert "#expires > :cutoff" in FilterExpression
        assert ExpressionAttributeNames["#type"] == "entityType"
        values = list(self.items.values())
        if ExclusiveStartKey:
            start = next(i for i, item in enumerate(values) if item["id"] == ExclusiveStartKey["id"])
            values = values[start + 1:]
        page = values[:Limit]
        user = ExpressionAttributeValues[":user"]
        cutoff = ExpressionAttributeValues[":cutoff"]
        matches = [item for item in page if item.get("entityType") in (None, "PLAN")
                   and (item.get("ownerId") == user or user in (item.get("attendeeIds") or []))
                   and item.get("status") != "CANCELLED" and item.get("expiresAt", 0) > cutoff]
        result = {"Items": [{"id": item["id"]} for item in matches]}
        if len(values) > Limit:
            result["LastEvaluatedKey"] = {"id": page[-1]["id"]}
        return result


class FakeMessages:
    """Tabla PlanYaMensajes: clave planId + sk."""

    def __init__(self):
        self.items = {}
        self.fail_after_request = False
        self.name = "PlanYaMensajes"

    def put_item(self, Item, ConditionExpression=None):
        key = (Item["planId"], Item["sk"])
        if ConditionExpression is not None:
            assert ConditionExpression == "attribute_not_exists(sk)"
            if key in self.items:
                raise ConditionalCheckFailed()
        if self.fail_after_request and Item["sk"].startswith("msg#"):
            self.fail_after_request = False
            raise TimeoutError("Lambda interrumpida")
        self.items[key] = copy.deepcopy(Item)

    def get_item(self, Key, ConsistentRead=False):
        item = self.items.get((Key["planId"], Key["sk"]))
        return {"Item": copy.deepcopy(item)} if item else {}

    def query(self, KeyConditionExpression, ExpressionAttributeNames, ExpressionAttributeValues,
              ScanIndexForward=True, Limit=None, ExclusiveStartKey=None, ConsistentRead=False):
        assert KeyConditionExpression == "#plan = :plan AND begins_with(#sk, :prefix)"
        assert ConsistentRead is True
        plan, prefix = ExpressionAttributeValues[":plan"], ExpressionAttributeValues[":prefix"]
        keys = sorted((sk for (p, sk) in self.items if p == plan and sk.startswith(prefix)),
                      reverse=not ScanIndexForward)
        if ExclusiveStartKey:
            start = ExclusiveStartKey["sk"]
            keys = [sk for sk in keys if (sk > start if ScanIndexForward else sk < start)]
        page = keys[:Limit] if Limit else keys
        result = {"Items": [copy.deepcopy(self.items[(plan, sk)]) for sk in page]}
        if Limit and len(keys) > Limit:
            result["LastEvaluatedKey"] = {"planId": plan, "sk": page[-1]}
        return result

    def stored_messages(self, plan_id=None):
        return [item for (p, sk), item in self.items.items()
                if sk.startswith("msg#") and (plan_id is None or p == plan_id)]


PLAN = str(uuid.uuid4())
HOST, GUEST, OTHER, STRANGER = "host-sub", "guest-sub", "other-sub", "stranger-sub"
NOW = 1_800_000_000  # segundos; el plan vence una hora después
NOW_MS = NOW * 1000


def plan(**changes):
    item = {"id": PLAN, "title": "Cafe por Miraflores", "ownerId": HOST, "attendeeIds": [HOST, GUEST, OTHER],
            "attendees": ["Ana Host", "Gabo", "Olga"], "capacity": 5, "expiresAt": Decimal(NOW + 3600),
            "startsAt": Decimal(NOW + 900), "revision": Decimal(3)}
    item.update(changes)
    return item


def claims(sub, token_use="id", name=None):
    result = {"sub": sub, "token_use": token_use}
    if name:
        result["name"] = name
    return result


def request(route, sub=GUEST, plan_id=PLAN, body=None, query=None, token_use="id"):
    event = {"routeKey": route, "pathParameters": {"id": plan_id},
             "requestContext": {"authorizer": {"jwt": {"claims": claims(sub, token_use)}}} if sub else {}}
    if body is not None:
        event["body"] = json.dumps(body)
    if query is not None:
        event["queryStringParameters"] = query
    return event


class ChatTests(unittest.TestCase):
    def setUp(self):
        self.chat = load("planya_chat", "chat.py")
        self.plans = FakePlans(plan(), {"id": "perfil#" + GUEST, "entityType": "PROFILE", "name": "Gabriel R."})
        self.messages = FakeMessages()

    def call(self, event, now_ms=NOW_MS):
        response = self.chat.handle(event, self.plans, self.messages, now_ms=now_ms)
        return response["statusCode"], json.loads(response["body"])

    def send(self, text="Llego en 5 minutos", sub=GUEST, client_id=None, now_ms=NOW_MS, extra=None):
        body = {"text": text, "clientMessageId": client_id or str(uuid.uuid4())}
        body.update(extra or {})
        return self.call(request("POST /planes/{id}/mensajes", sub, body=body), now_ms)

    def read(self, sub=GUEST, query=None, now_ms=NOW_MS):
        return self.call(request("GET /planes/{id}/mensajes", sub, query=query), now_ms)

    # Criterio 1 y 3: participante confirmado consulta y envía con identidad y hora del servidor.
    def test_participant_sends_with_server_identity_and_time(self):
        status, body = self.send("  Hola grupo  ", extra={"senderId": HOST, "senderName": "Falso", "createdAt": 1})
        self.assertEqual(status, 201)
        message = body["data"]
        self.assertEqual(message["senderId"], GUEST)
        self.assertEqual(message["senderName"], "Gabriel R.")  # nombre guardado en Perfil
        self.assertEqual(message["text"], "Hola grupo")
        self.assertEqual(message["createdAt"], NOW_MS)
        self.assertEqual(message["expiresAt"], NOW + 3600 + 7200)
        status, page = self.read()
        self.assertEqual(status, 200)
        self.assertEqual([m["text"] for m in page["data"]["items"]], ["Hola grupo"])
        self.assertEqual(page["data"]["chatExpiresAt"], NOW + 3600 + 7200)
        self.assertEqual(page["data"]["serverTime"], NOW)
        self.assertFalse(page["data"]["realtime"])

    def test_host_can_use_chat_and_name_falls_back_to_attendee_list(self):
        status, body = self.send("Ya estoy en el parque", sub=HOST)
        self.assertEqual(status, 201)
        self.assertEqual(body["data"]["senderName"], "Ana Host")
        status, page = self.read(sub=HOST)
        self.assertEqual(status, 200)
        self.assertTrue(page["data"]["isHost"])

    # Criterio 2: ajeno o sin sesión válida recibe 401/403 aunque llame directo a la API.
    def test_stranger_and_missing_token_are_rejected_without_writing(self):
        self.assertEqual(self.send(sub=STRANGER)[0], 403)
        self.assertEqual(self.read(sub=STRANGER)[0], 403)
        self.assertEqual(self.call(request("GET /planes/{id}/mensajes", sub=None))[0], 401)
        self.assertEqual(self.call(request("POST /planes/{id}/mensajes", token_use="access",
                                           body={"text": "x", "clientMessageId": str(uuid.uuid4())}))[0], 401)
        self.assertEqual(self.messages.items, {})

    def test_stranger_does_not_learn_that_plan_was_cancelled(self):
        self.plans.items[PLAN]["status"] = "CANCELLED"
        status, body = self.read(sub=STRANGER)
        self.assertEqual((status, body["code"]), (403, "NOT_MEMBER"))

    # Criterio 4 del documento: texto vacío o demasiado largo no se envía.
    def test_text_validation(self):
        for text in ("", "   \n  ", None, 5, "x" * 1001, "hola\x00", "hola\x1b[31m", "emoji roto \ud83d"):
            status, body = self.send(text)
            self.assertEqual(status, 400, text)
            self.assertEqual(body["code"], "INVALID")
        self.assertEqual(self.messages.items, {})
        self.assertEqual(self.send("x" * 1000)[0], 201)
        status, body = self.send("Linea 1\r\nLinea 2\tok")
        self.assertEqual((status, body["data"]["text"]), (201, "Linea 1\nLinea 2\tok"))

    def test_client_message_id_must_be_a_uuid(self):
        for value in (None, "", "abc", 123, "{" + str(uuid.uuid4()) + "}"):
            status, _ = self.call(request("POST /planes/{id}/mensajes", body={"text": "Hola", "clientMessageId": value}))
            self.assertEqual(status, 400, value)
        self.assertEqual(self.messages.items, {})

    # Criterio 4 de la historia: un reintento con la misma clave no se guarda dos veces.
    def test_retry_with_same_client_message_id_is_not_duplicated(self):
        client_id = str(uuid.uuid4())
        first_status, first = self.send("Voy llegando", client_id=client_id)
        retry_status, retry = self.send("Voy llegando", client_id=client_id, now_ms=NOW_MS + 4000)
        self.assertEqual((first_status, retry_status), (201, 200))
        self.assertTrue(retry["duplicate"])
        self.assertEqual(retry["data"]["id"], first["data"]["id"])
        self.assertEqual(retry["data"]["createdAt"], NOW_MS)  # conserva la hora original
        self.assertEqual(len(self.messages.stored_messages()), 1)

    def test_same_client_id_with_different_text_is_a_conflict(self):
        client_id = str(uuid.uuid4())
        self.send("Primero", client_id=client_id)
        status, body = self.send("Otro texto", client_id=client_id)
        self.assertEqual((status, body["code"]), (409, "RETRY_CONFLICT"))
        self.assertEqual(len(self.messages.stored_messages()), 1)

    def test_retry_completes_a_send_interrupted_between_writes(self):
        client_id = str(uuid.uuid4())
        self.messages.fail_after_request = True
        with self.assertRaises(TimeoutError):
            self.send("Mensaje interrumpido", client_id=client_id)
        self.assertEqual(self.messages.stored_messages(), [])
        status, body = self.send("Mensaje interrumpido", client_id=client_id, now_ms=NOW_MS + 9000)
        self.assertEqual(status, 200)
        self.assertEqual(body["data"]["createdAt"], NOW_MS)
        self.assertEqual(len(self.messages.stored_messages()), 1)

    def test_other_user_with_same_client_id_does_not_collide(self):
        client_id = str(uuid.uuid4())
        self.assertEqual(self.send("Yo", sub=GUEST, client_id=client_id)[0], 201)
        self.assertEqual(self.send("Yo", sub=OTHER, client_id=client_id)[0], 201)
        self.assertEqual(len(self.messages.stored_messages()), 2)

    # Criterio 6 del documento: paginación sin duplicados y con orden estable.
    def test_history_pages_are_stable_and_do_not_overlap(self):
        for index in range(70):
            self.send(f"m{index:02d}", sub=[GUEST, OTHER, HOST][index % 3], now_ms=NOW_MS + index * 1000)
        seen = []
        query = None
        pages = 0
        while True:
            status, page = self.read(query=query, now_ms=NOW_MS + 100_000)
            self.assertEqual(status, 200)
            texts = [m["text"] for m in page["data"]["items"]]
            self.assertEqual(texts, sorted(texts))  # cada página llega de antigua a reciente
            seen = texts + seen
            pages += 1
            if not page["data"]["nextKey"]:
                break
            query = {"nextKey": page["data"]["nextKey"]}
        self.assertEqual(pages, 3)
        self.assertEqual(seen, [f"m{index:02d}" for index in range(70)])

    def test_after_cursor_returns_only_newer_messages(self):
        self.send("viejo", now_ms=NOW_MS)
        _, page = self.read()
        cursor = page["data"]["items"][-1]["cursor"]
        self.send("nuevo 1", sub=OTHER, now_ms=NOW_MS + 1000)
        self.send("nuevo 2", sub=HOST, now_ms=NOW_MS + 2000)
        status, newer = self.read(query={"after": cursor})
        self.assertEqual(status, 200)
        self.assertEqual([m["text"] for m in newer["data"]["items"]], ["nuevo 1", "nuevo 2"])
        self.assertIsNone(newer["data"]["afterKey"])
        self.assertIsNone(newer["data"]["nextKey"])

    def test_same_millisecond_messages_keep_a_stable_order(self):
        for text in ("a", "b", "c"):
            self.send(text, now_ms=NOW_MS)
        first = [m["id"] for m in self.read()[1]["data"]["items"]]
        second = [m["id"] for m in self.read()[1]["data"]["items"]]
        self.assertEqual(first, second)
        self.assertEqual(len(first), 3)

    def test_invalid_cursor_and_plan_id(self):
        for query in ({"nextKey": "msg#1"}, {"after": "x" * 60}, {"nextKey": "1" * 13 + "#" + str(uuid.uuid4()),
                                                                     "after": "1" * 13 + "#" + str(uuid.uuid4())}):
            self.assertEqual(self.read(query=query)[0], 400, query)
        for plan_id in ("perfil#" + GUEST, "no-es-uuid", None, PLAN.upper() + "x"):
            self.assertEqual(self.call(request("GET /planes/{id}/mensajes", plan_id=plan_id))[0], 400, plan_id)

    def test_missing_plan_is_404(self):
        status, body = self.call(request("GET /planes/{id}/mensajes", plan_id=str(uuid.uuid4())))
        self.assertEqual((status, body["code"]), (404, "PLAN_NOT_FOUND"))

    # Criterio 5: después de salir del plan, el servidor deniega lectura y escritura.
    def test_leaving_the_plan_revokes_read_and_write(self):
        self.send("Antes de salir")
        self.plans.items[PLAN]["attendeeIds"] = [HOST, OTHER]
        self.plans.items[PLAN]["attendees"] = ["Ana Host", "Olga"]
        self.assertEqual(self.read()[1]["code"], "NOT_MEMBER")
        self.assertEqual(self.send("Despues")[0], 403)
        self.assertEqual(len(self.messages.stored_messages()), 1)

    # Criterio 9 del documento: plan terminado normalmente, chat aún abierto.
    def test_finished_plan_keeps_chat_open_until_two_hours_later(self):
        after_plan = (NOW + 3600 + 60) * 1000
        self.assertEqual(self.send("Gracias a todos", now_ms=after_plan)[0], 201)
        self.assertEqual(self.read(now_ms=after_plan)[0], 200)
        last_second = (NOW + 3600 + 7200 - 1) * 1000
        self.assertEqual(self.read(now_ms=last_second)[0], 200)

    # Criterio 6 de la historia: al cumplirse el plazo se bloquea aunque los mensajes sigan guardados.
    def test_closed_chat_blocks_read_and_write_even_if_records_remain(self):
        self.send("Mensaje que espera limpieza")
        closed = (NOW + 3600 + 7200) * 1000
        status, body = self.read(now_ms=closed)
        self.assertEqual((status, body["code"]), (410, "CHAT_CLOSED"))
        self.assertNotIn("Mensaje que espera limpieza", json.dumps(body))
        self.assertEqual(self.send("Tarde", now_ms=closed)[0], 410)
        self.assertEqual(len(self.messages.stored_messages()), 1)  # el TTL limpia después

    # Criterio 7: plan cancelado deja de permitir operaciones de inmediato.
    def test_cancelled_plan_blocks_chat_immediately_without_new_window(self):
        self.send("Antes de cancelar")
        # DELETE /planes/{id} marca CANCELLED y pone expiresAt = ahora; no se abre otra ventana.
        self.plans.items[PLAN].update(status="CANCELLED", expiresAt=Decimal(NOW + 10))
        for status, body in (self.read(now_ms=NOW_MS + 20_000), self.send("Despues", now_ms=NOW_MS + 20_000)):
            self.assertEqual((status, body["code"]), (410, "PLAN_CANCELLED"))

    def test_plan_without_schedule_has_no_invented_expiry(self):
        del self.plans.items[PLAN]["expiresAt"]
        status, body = self.read()
        self.assertEqual((status, body["code"]), (410, "NO_SCHEDULE"))
        self.assertEqual(self.send()[0], 410)

    # Criterio 11 del documento: dos participantes ven lo mismo y nadie cambia el autor.
    def test_two_participants_see_same_messages_authors_and_order(self):
        self.send("Hola", sub=GUEST, now_ms=NOW_MS)
        self.send("Hola, ya voy", sub=OTHER, now_ms=NOW_MS + 500)
        self.send("Estoy en la puerta", sub=HOST, now_ms=NOW_MS + 900)
        guest_view = [(m["senderId"], m["text"]) for m in self.read(sub=GUEST)[1]["data"]["items"]]
        other_view = [(m["senderId"], m["text"]) for m in self.read(sub=OTHER)[1]["data"]["items"]]
        self.assertEqual(guest_view, other_view)
        self.assertEqual([sender for sender, _ in guest_view], [GUEST, OTHER, HOST])

    def test_client_message_id_is_only_returned_to_its_sender(self):
        client_id = str(uuid.uuid4())
        self.send("Privado", client_id=client_id)
        mine = self.read(sub=GUEST)[1]["data"]["items"][0]
        theirs = self.read(sub=OTHER)[1]["data"]["items"][0]
        self.assertEqual(mine["clientMessageId"], client_id)
        self.assertNotIn("clientMessageId", theirs)

    def test_ttl_attribute_is_the_chat_close_time(self):
        self.send()
        for item in self.messages.items.values():
            self.assertEqual(item["expiresAt"], NOW + 3600 + 7200)

    def test_requests_are_never_listed_as_messages(self):
        self.send("Uno")
        self.assertEqual(len(self.messages.items), 2)  # mensaje + marca de reintento
        self.assertEqual(len(self.read()[1]["data"]["items"]), 1)

    def test_non_plan_entities_cannot_be_used_as_chat_plans(self):
        for entity_type in ("BENEFIT", "BENEFIT_CODE", "PROFILE", "MESSAGE", "UNKNOWN"):
            with self.subTest(entity_type=entity_type):
                self.plans.items[PLAN]["entityType"] = entity_type
                self.assertEqual(self.read()[0], 404)
                self.assertEqual(self.send()[0], 404)
        self.assertEqual(self.messages.items, {})


class ChatDispatchTests(unittest.TestCase):
    """La Lambda principal envía solo las rutas de mensajes al módulo de chat."""

    def setUp(self):
        self.plans = FakePlans(plan())
        self.messages = FakeMessages()
        resource = Mock()
        resource.Table.side_effect = lambda name: {"PlanYaPlanes": self.plans, "PlanYaMensajes": self.messages}[name]
        fake_boto3 = types.ModuleType("boto3")
        fake_boto3.resource = Mock(return_value=resource)
        chat_module = load("chat", "chat.py")
        with patch.dict(sys.modules, {"boto3": fake_boto3}):
            self.api = load("planya_lambda_chat", "lambda_function.py")
        self.modules = patch.dict(sys.modules, {"chat": chat_module})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.env = patch.dict(os.environ, {"TABLE_NAME": "PlanYaPlanes"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_lambda_routes_messages_to_chat_with_default_table(self):
        body = {"text": "Hola desde la Lambda", "clientMessageId": str(uuid.uuid4())}
        response = self.api.lambda_handler(request("POST /planes/{id}/mensajes", body=body), None)
        self.assertEqual(response["statusCode"], 201)
        self.assertEqual(len(self.messages.stored_messages(PLAN)), 1)
        response = self.api.lambda_handler(request("GET /planes/{id}/mensajes"), None)
        self.assertEqual(json.loads(response["body"])["data"]["items"][0]["text"], "Hola desde la Lambda")

    def test_public_plan_routes_do_not_load_chat(self):
        self.plans.items[PLAN]["expiresAt"] = Decimal(4_000_000_000)
        response = self.api.lambda_handler({"requestContext": {}, "routeKey": "GET /planes/{id}",
                                            "pathParameters": {"id": PLAN}}, None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(self.messages.items, {})

    def test_conversations_reopens_finished_plan_without_public_detail(self):
        self.plans.items[PLAN]["expiresAt"] = Decimal(int(time.time()) - 60)
        public = self.api.lambda_handler({"requestContext": {}, "routeKey": "GET /planes/{id}",
                                         "pathParameters": {"id": PLAN}}, None)
        self.assertEqual(public["statusCode"], 404)
        listing = self.api.lambda_handler(request("GET /conversaciones"), None)
        self.assertEqual(listing["statusCode"], 200)
        self.assertEqual(json.loads(listing["body"])["data"]["items"][0]["planId"], PLAN)
        chat = self.api.lambda_handler(request("GET /planes/{id}/mensajes"), None)
        self.assertEqual(chat["statusCode"], 200)
        self.assertEqual(json.loads(chat["body"])["data"]["hostId"], HOST)


class ConversationsTests(unittest.TestCase):
    def setUp(self):
        self.chat = load("planya_conversations", "chat.py")
        self.plans = FakePlans(plan())
        self.messages = FakeMessages()

    def call(self, sub=GUEST, query=None, now=NOW, token_use="id"):
        response = self.chat.handle(request("GET /conversaciones", sub=sub, query=query, token_use=token_use),
                                    self.plans, self.messages, now_ms=now * 1000)
        return response["statusCode"], json.loads(response["body"])

    def test_requires_verified_id_token(self):
        self.assertEqual(self.call(sub=None)[0], 401)
        self.assertEqual(self.call(token_use="access")[0], 401)
        self.assertEqual(self.messages.items, {})

    def test_only_own_open_plans_and_minimal_fields_are_returned(self):
        self.plans = FakePlans(plan(description="privado", meetingPlace="direccion", imageKey="foto"),
                               plan(id=str(uuid.uuid4()), ownerId=STRANGER, attendeeIds=[STRANGER]),
                               plan(id=str(uuid.uuid4()), status="CANCELLED"),
                               plan(id=str(uuid.uuid4()), expiresAt=NOW - 7200),
                               plan(id=str(uuid.uuid4()), entityType="BENEFIT_CODE"),
                               plan(id=str(uuid.uuid4()), expiresAt=0))
        status, body = self.call(query={"userId": STRANGER})
        self.assertEqual(status, 200)
        self.assertEqual(body["data"]["userId"], GUEST)
        self.assertEqual([i["planId"] for i in body["data"]["items"]], [PLAN])
        item = body["data"]["items"][0]
        self.assertEqual(set(item), {"planId", "title", "hostId", "hostName", "category", "planExpiresAt", "chatExpiresAt", "isHost"})
        self.assertFalse(item["isHost"])

    def test_host_access_does_not_require_host_in_attendee_array(self):
        self.plans.items[PLAN]["attendeeIds"] = []
        status, body = self.call(sub=HOST)
        self.assertEqual(status, 200)
        self.assertTrue(body["data"]["items"][0]["isHost"])

    def test_finished_plan_remains_until_exact_chat_close(self):
        self.plans.items[PLAN]["expiresAt"] = NOW - 3600
        self.assertEqual(len(self.call()[1]["data"]["items"]), 1)
        self.assertEqual(self.call(now=NOW + 3600)[1]["data"]["items"], [])

    def test_membership_is_rechecked_after_scan(self):
        self.plans.scan = Mock(return_value={"Items": [{"id": PLAN}]})
        self.plans.items[PLAN]["attendeeIds"] = []
        self.assertEqual(self.call()[1]["data"]["items"], [])

    def test_cursor_hides_private_key_and_allows_empty_page_to_continue(self):
        self.plans.scan = Mock(side_effect=[{"Items": [], "LastEvaluatedKey": {"id": "codigo#SECRETO_AJENO"}},
                                           {"Items": [{"id": PLAN}]}])
        status, body = self.call()
        self.assertEqual(status, 200)
        self.assertEqual(body["data"]["items"], [])
        cursor = body["data"]["nextKey"]
        self.assertEqual(str(uuid.UUID(cursor)), cursor)
        self.assertNotIn("SECRETO_AJENO", json.dumps(body))
        status, second = self.call(query={"nextKey": cursor})
        self.assertEqual(status, 200)
        self.assertEqual(second["data"]["items"][0]["planId"], PLAN)
        self.assertEqual(self.plans.scan.call_args.kwargs["ExclusiveStartKey"], {"id": "codigo#SECRETO_AJENO"})
        stored = self.messages.items[("conversations#" + cursor, "cursor")]
        self.assertEqual(stored["expiresAt"], NOW + 900)

    def test_cursor_cannot_be_reused_by_another_account(self):
        self.plans.scan = Mock(return_value={"Items": [], "LastEvaluatedKey": {"id": "privado"}})
        cursor = self.call()[1]["data"]["nextKey"]
        self.assertEqual(self.call(sub=OTHER, query={"nextKey": cursor})[0], 400)
        self.assertEqual(self.call(query={"nextKey": "codigo#privado"})[0], 400)
        self.assertEqual(self.call(query={"nextKey": str(uuid.uuid4())})[0], 400)

    def test_expired_cursor_requires_refresh_even_before_ttl_cleanup(self):
        self.plans.scan = Mock(return_value={"Items": [], "LastEvaluatedKey": {"id": "privado"}})
        cursor = self.call()[1]["data"]["nextKey"]
        status, body = self.call(query={"nextKey": cursor}, now=NOW + 900)
        self.assertEqual(status, 409)
        self.assertEqual(body["code"], "CURSOR_EXPIRED")


if __name__ == "__main__":
    unittest.main()
