"""Pruebas de US-12 (reportar un problema y moderación) sin credenciales ni llamadas a AWS.

Las tablas en memoria imitan GetItem, PutItem condicional y Query sobre el índice disperso
GSI_ReportsByStatus. La hora se inyecta (now); no se cambia el reloj del equipo.
"""

import copy
import importlib.util
import json
import os
import sys
import types
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


class AwsError(Exception):
    def __init__(self, code, message=""):
        super().__init__(code)
        self.response = {"Error": {"Code": code, "Message": message}}


class FakePlans:
    """PlanYaPlanes (clave id) con el índice disperso GSI_ReportsByStatus (status + reportSortKey)."""

    def __init__(self, *items):
        self.items = {item["id"]: copy.deepcopy(item) for item in items}
        self.index_exists = True
        self.writes = 0

    def get_item(self, Key, ConsistentRead=False):
        item = self.items.get(Key["id"])
        return {"Item": copy.deepcopy(item)} if item else {}

    def put_item(self, Item, ConditionExpression=None, ExpressionAttributeNames=None, ExpressionAttributeValues=None):
        current = self.items.get(Item["id"])
        if ConditionExpression == "attribute_not_exists(id)":
            if current is not None:
                raise AwsError("ConditionalCheckFailedException")
        elif ConditionExpression == "#revision = :expected AND #status = :current":
            values = ExpressionAttributeValues
            if not current or current["revision"] != values[":expected"] or current["status"] != values[":current"]:
                raise AwsError("ConditionalCheckFailedException")
        elif ConditionExpression is not None:
            raise AssertionError(f"Condición inesperada: {ConditionExpression}")
        self.writes += 1
        self.items[Item["id"]] = copy.deepcopy(Item)

    def query(self, IndexName, KeyConditionExpression, ExpressionAttributeNames, ExpressionAttributeValues,
              ScanIndexForward=True, Limit=None, ExclusiveStartKey=None):
        if not self.index_exists:
            raise AwsError("ValidationException", "The table does not have the specified index: " + IndexName)
        assert IndexName == "GSI_ReportsByStatus"
        assert KeyConditionExpression == "#status = :status"
        status = ExpressionAttributeValues[":status"]
        rows = sorted((item for item in self.items.values()
                       if item.get("status") == status and "reportSortKey" in item),
                      key=lambda item: item["reportSortKey"], reverse=not ScanIndexForward)
        if ExclusiveStartKey:
            start = ExclusiveStartKey["reportSortKey"]
            rows = [r for r in rows if (r["reportSortKey"] < start if not ScanIndexForward else r["reportSortKey"] > start)]
        page = rows[:Limit]
        result = {"Items": [copy.deepcopy(r) for r in page]}
        if Limit and len(rows) > Limit:
            last = page[-1]
            result["LastEvaluatedKey"] = {"id": last["id"], "status": last["status"], "reportSortKey": last["reportSortKey"]}
        return result

    def reports(self):
        return [item for item in self.items.values() if item.get("entityType") == "REPORT"]


class FakeMessages:
    """PlanYaMensajes (planId + sk): mensajes del chat y cursores de moderación."""

    def __init__(self):
        self.items = {}

    def get_item(self, Key, ConsistentRead=False):
        item = self.items.get((Key["planId"], Key["sk"]))
        return {"Item": copy.deepcopy(item)} if item else {}

    def put_item(self, Item, ConditionExpression=None):
        self.items[(Item["planId"], Item["sk"])] = copy.deepcopy(Item)


NOW = 1_800_000_000
PLAN, OTHER_PLAN = str(uuid.uuid4()), str(uuid.uuid4())
HOST, GUEST, STRANGER, MOD = "host-sub", "guest-sub", "stranger-sub", "mod-sub"
MSG = str(uuid.uuid4())
MSG_CURSOR = f"{(NOW - 60) * 1000:013d}#{MSG}"
OWN_MSG = str(uuid.uuid4())
OWN_CURSOR = f"{(NOW - 30) * 1000:013d}#{OWN_MSG}"


def plan(**changes):
    item = {"id": PLAN, "title": "Café por Miraflores", "ownerId": HOST, "attendeeIds": [HOST, GUEST],
            "attendees": ["Ana", "Gabo"], "expiresAt": Decimal(NOW + 3600), "revision": Decimal(2)}
    item.update(changes)
    return item


def event(route, sub=GUEST, body=None, query=None, path_id=None, groups=None, token_use="id"):
    claims = {"sub": sub, "token_use": token_use}
    if groups is not None:
        claims["cognito:groups"] = groups
    result = {"routeKey": route, "requestContext": {"authorizer": {"jwt": {"claims": claims}}} if sub else {}}
    if body is not None:
        result["body"] = json.dumps(body)
    if query is not None:
        result["queryStringParameters"] = query
    if path_id is not None:
        result["pathParameters"] = {"id": path_id}
    return result


def plan_body(**changes):
    body = {"requestId": str(uuid.uuid4()), "targetType": "PLAN", "planId": PLAN, "reason": "INAPPROPRIATE",
            "description": "Solicito revisar el contenido de este encuentro."}
    body.update(changes)
    return body


def message_body(**changes):
    body = plan_body(targetType="MESSAGE", messageId=MSG_CURSOR, reason="SPAM",
                     description="Este mensaje es publicidad repetida.")
    body.update(changes)
    return body


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.reports = load("planya_reports", "reports.py")
        self.plans = FakePlans(plan(), plan(id=OTHER_PLAN, title="Otro plan", ownerId=STRANGER, attendeeIds=[STRANGER]))
        self.messages = FakeMessages()
        for msg_id, cursor, sender, kind in ((MSG, MSG_CURSOR, HOST, None), (OWN_MSG, OWN_CURSOR, GUEST, None)):
            item = {"planId": PLAN, "sk": "msg#" + cursor, "entityType": "MESSAGE", "id": msg_id,
                    "senderId": sender, "senderName": "X", "text": "Texto privado del chat", "createdAt": 1}
            if kind:
                item["kind"] = kind
            self.messages.put_item(item)

    def call(self, evt, now=NOW):
        response = self.reports.handle(self.plans, self.messages, evt, now)
        return response["statusCode"], json.loads(response["body"])

    def create(self, body, sub=GUEST, now=NOW):
        return self.call(event("POST /reportes", sub, body=body), now)

    def moderate(self, report_id, body, sub=MOD, groups="[PlanYaModeradores]", now=NOW):
        return self.call(event("PATCH /moderacion/reportes/{id}", sub, body=body, path_id=report_id, groups=groups), now)

    # CA 12 01: envío válido persiste un reporte RECIBIDO con identificador.
    def test_valid_plan_report_is_persisted_as_received(self):
        before = copy.deepcopy(self.plans.items[PLAN])
        status, body = self.create(plan_body(reporterId=HOST, status="RESUELTO", revision=7, description="  Revisen el contenido de este plan.  "),
                                   sub=STRANGER)
        self.assertEqual(status, 201)
        data = body["data"]
        self.assertEqual(str(uuid.UUID(data["id"])), data["id"])
        self.assertEqual((data["status"], data["revision"], data["createdAt"]), ("RECIBIDO", 0, NOW))
        self.assertEqual((data["targetType"], data["planTitle"]), ("PLAN", "Café por Miraflores"))
        self.assertEqual(data["description"], "Revisen el contenido de este plan.")
        stored = self.plans.reports()[0]
        self.assertEqual(stored["reporterId"], STRANGER)  # del token, no del cuerpo
        self.assertEqual(stored["reportedUserId"], HOST)
        self.assertEqual(stored["reportSortKey"], f"{NOW:010d}#{data['id']}")  # contrato con US-13
        self.assertNotIn("reporterId", data)
        self.assertNotIn("payloadHash", data)
        # CA 12 08: reportar no cancela ni modifica el plan.
        self.assertEqual(self.plans.items[PLAN], before)

    # CA 12 02: validación del formulario sin crear reportes.
    def test_form_validation_creates_nothing(self):
        cases = [plan_body(reason=None), plan_body(reason="OFENSA"), plan_body(description="corto"),
                 plan_body(description="     nueve car     "), plan_body(description="x" * 501),
                 plan_body(description="texto con control \x07 raro"), plan_body(requestId="abc"),
                 plan_body(targetType="USUARIO"), plan_body(planId="no-uuid")]
        for body in cases:
            status, data = self.create(body, sub=STRANGER)
            self.assertEqual((status, data["code"]), (400, "INVALID"), body)
        self.assertEqual(self.plans.reports(), [])
        self.assertEqual(self.create(plan_body(description="x" * 500), sub=STRANGER)[0], 201)
        self.assertEqual(self.create(plan_body(description="Diez letra"), sub=STRANGER)[0], 201)

    def test_plan_access_rules(self):
        self.assertEqual(self.create(plan_body(), sub=HOST)[0], 400)  # su propio plan
        self.assertEqual(self.create(plan_body(planId=str(uuid.uuid4())), sub=STRANGER)[1]["code"], "PLAN_NOT_FOUND")
        self.plans.items[PLAN]["status"] = "CANCELLED"
        self.assertEqual(self.create(plan_body(), sub=STRANGER)[0], 404)  # cancelado y ajeno: no visible
        self.assertEqual(self.create(plan_body(), sub=GUEST)[0], 201)  # participante sí puede
        self.plans.items[PLAN].pop("status")
        self.plans.items[PLAN].pop("expiresAt")
        self.assertEqual(self.create(plan_body(), sub=STRANGER)[0], 201)  # plan público sin horario
        self.plans.items["perfil#x"] = {"id": "perfil#x", "entityType": "PROFILE"}
        self.assertEqual(self.create(plan_body(planId=str(uuid.uuid4())), sub=STRANGER)[0], 404)

    def test_valid_message_report_keeps_only_the_reference(self):
        status, body = self.create(message_body())
        self.assertEqual(status, 201)
        self.assertEqual((body["data"]["targetType"], body["data"]["messageId"]), ("MESSAGE", MSG_CURSOR))
        stored = self.plans.reports()[0]
        self.assertEqual(stored["reportedUserId"], HOST)
        self.assertNotIn("Texto privado del chat", json.dumps(stored, default=str))  # sin copia del chat
        self.assertNotIn("Texto privado del chat", json.dumps(body))

    # CA 12 03: contexto manipulado o contenido inaccesible.
    def test_message_access_is_rechecked_by_the_server(self):
        self.assertEqual(self.create(message_body(), sub=STRANGER)[1]["code"], "NOT_MEMBER")
        status, body = self.create(message_body(planId=OTHER_PLAN), sub=STRANGER)  # mensaje de otro plan
        self.assertEqual((status, body["code"]), (404, "MESSAGE_NOT_FOUND"))
        self.assertNotIn("Texto privado", json.dumps(body))
        fake = f"{(NOW - 60) * 1000:013d}#{uuid.uuid4()}"
        self.assertEqual(self.create(message_body(messageId=fake))[0], 404)  # cursor inventado
        self.assertEqual(self.create(message_body(messageId=f"{(NOW - 60) * 1000:013d}#{OWN_MSG}"))[0], 404)  # id cruzado
        for bad in ("msg#1", MSG, "msg#" + MSG_CURSOR, None):
            self.assertEqual(self.create(message_body(messageId=bad))[0], 400, bad)
        self.assertEqual(self.create(message_body(messageId=OWN_CURSOR))[0], 400)  # propio
        self.messages.items[(PLAN, "msg#" + MSG_CURSOR)]["kind"] = "SYSTEM"
        self.assertEqual(self.create(message_body())[0], 404)
        self.messages.items[(PLAN, "msg#" + MSG_CURSOR)].pop("kind")
        status, body = self.create(message_body(), now=NOW + 3600 + 7200)
        self.assertEqual((status, body["code"]), (410, "CHAT_CLOSED"))
        self.assertEqual(self.plans.reports(), [])

    # CA 12 04: reintento sin duplicar; otro contenido con la misma clave es conflicto.
    def test_retry_with_same_request_returns_same_report(self):
        body = message_body()
        first_status, first = self.create(body)
        retry_status, retry = self.create(body, now=NOW + 30)
        self.assertEqual((first_status, retry_status), (201, 200))
        self.assertTrue(retry["duplicate"])
        self.assertEqual(retry["data"], first["data"])
        self.assertEqual(len(self.plans.reports()), 1)
        status, conflict = self.create({**body, "description": "Otro texto distinto para el mismo intento."})
        self.assertEqual((status, conflict["code"]), (409, "REQUEST_CONFLICT"))
        self.assertEqual(len(self.plans.reports()), 1)
        # El mismo requestId de otra cuenta es otro intento independiente.
        self.plans.items[PLAN]["attendeeIds"].append(STRANGER)
        self.assertEqual(self.create(body, sub=STRANGER)[0], 201)
        self.assertEqual(len(self.plans.reports()), 2)

    def test_retry_after_access_changed_still_recovers_the_receipt(self):
        body = message_body()
        self.create(body)
        status, again = self.create(body, now=NOW + 3600 + 7200)  # chat ya cerrado
        self.assertEqual(status, 200)
        self.assertEqual(again["data"]["status"], "RECIBIDO")

    def test_requires_verified_id_token(self):
        self.assertEqual(self.call(event("POST /reportes", None, body=plan_body()))[0], 401)
        self.assertEqual(self.call(event("POST /reportes", STRANGER, body=plan_body(), token_use="access"))[0], 401)

    # CA 12 06: moderación solo con el grupo del token; nada del cuerpo concede el rol.
    def test_only_moderator_group_can_review(self):
        report_id = self.create(plan_body(), sub=STRANGER)[1]["data"]["id"]
        for groups in (None, "", "[PlanYaOperadores]", "PlanYaModeradoresFalso"):
            status, body = self.moderate(report_id, {"requestId": str(uuid.uuid4()), "expectedRevision": 0,
                                                     "status": "EN_REVISION", "isModerator": True,
                                                     "cognito:groups": "PlanYaModeradores"}, groups=groups)
            self.assertEqual((status, body["code"]), (403, "NOT_MODERATOR"), groups)
            self.assertEqual(self.call(event("GET /moderacion/reportes", MOD, groups=groups))[0], 403)
        self.assertEqual(self.plans.reports()[0]["status"], "RECIBIDO")
        for groups in ("[PlanYaModeradores]", "PlanYaModeradores", ["PlanYaModeradores", "Otro"], "[Otro PlanYaModeradores]"):
            self.assertTrue(self.reports.is_moderator({"cognito:groups": groups}), groups)

    # CA 12 07: revisión y cierre auditados con versión vigente y respuesta pública.
    def test_review_and_close_flow_with_audit(self):
        report_id = self.create(plan_body(), sub=STRANGER)[1]["data"]["id"]
        step = lambda rev, st, **extra: self.moderate(report_id, {"requestId": str(uuid.uuid4()), "expectedRevision": rev,
                                                                  "status": st, **extra}, now=NOW + 100)
        self.assertEqual(step(0, "RESUELTO", publicResponse="Revisado y resuelto.")[1]["code"], "INVALID_TRANSITION")
        self.assertEqual(step(0, "EN_REVISION", publicResponse="Todavía no se responde")[0], 400)
        status, body = step(0, "EN_REVISION", internalNote="Lo reviso hoy")
        self.assertEqual((status, body["data"]["status"], body["data"]["revision"]), (200, "EN_REVISION", 1))
        self.assertEqual(step(0, "RESUELTO", publicResponse="Revisado y resuelto.")[1]["code"], "REVISION_CONFLICT")
        self.assertEqual(step(1, "RESUELTO")[0], 400)  # cerrar exige respuesta pública
        self.assertEqual(step(1, "RESUELTO", publicResponse="corta")[0], 400)
        status, body = step(1, "RESUELTO", publicResponse="Revisamos el plan y contactamos al anfitrión.")
        self.assertEqual((status, body["data"]["status"], body["data"]["revision"]), (200, "RESUELTO", 2))
        history = body["data"]["history"]
        self.assertEqual([h["status"] for h in history], ["RECIBIDO", "EN_REVISION", "RESUELTO"])
        self.assertEqual(history[-1]["by"], MOD)
        self.assertEqual(step(2, "EN_REVISION")[1]["code"], "INVALID_TRANSITION")  # sin reapertura en el MVP
        # Lo que verá US-13: estado y respuesta pública, sin moderador, historial ni notas internas.
        public = self.reports.public_report(self.plans.reports()[0])
        self.assertEqual((public["status"], public["publicResponse"]),
                         ("RESUELTO", "Revisamos el plan y contactamos al anfitrión."))
        for private in ("history", "internalNotes", "reporterId", "reportedUserId", "payloadHash"):
            self.assertNotIn(private, public)

    def test_moderation_retry_returns_same_receipt(self):
        report_id = self.create(plan_body(), sub=STRANGER)[1]["data"]["id"]
        change = {"requestId": str(uuid.uuid4()), "expectedRevision": 0, "status": "EN_REVISION"}
        first = self.moderate(report_id, change)
        retry = self.moderate(report_id, change)
        self.assertEqual((first[0], retry[0]), (200, 200))
        self.assertTrue(retry[1]["duplicate"])
        self.assertEqual(retry[1]["data"]["revision"], 1)
        self.assertEqual(len(self.plans.reports()[0]["history"]), 2)
        self.assertEqual(self.moderate(report_id, {**change, "status": "DESCARTADO",
                                                   "publicResponse": "No incumple las normas."})[1]["code"], "REQUEST_CONFLICT")
        self.assertEqual(self.moderate(str(uuid.uuid4()), {**change, "requestId": str(uuid.uuid4())})[0], 404)

    def test_moderation_queue_pages_by_status_with_bound_cursor(self):
        for index in range(25):
            self.create(plan_body(), sub=STRANGER, now=NOW + index)
        listing = lambda query, sub=MOD, now=NOW + 100: self.call(event("GET /moderacion/reportes", sub, query=query,
                                                                         groups="PlanYaModeradores"), now)
        status, first = listing({"status": "RECIBIDO"})
        self.assertEqual((status, len(first["data"]["items"])), (200, 20))
        created = [item["createdAt"] for item in first["data"]["items"]]
        self.assertEqual(created, sorted(created, reverse=True))  # más reciente primero
        cursor = first["data"]["nextKey"]
        self.assertEqual(str(uuid.UUID(cursor)), cursor)  # opaco
        status, second = listing({"status": "RECIBIDO", "nextKey": cursor})
        self.assertEqual((status, len(second["data"]["items"]), second["data"]["nextKey"]), (200, 5, None))
        ids = {i["id"] for i in first["data"]["items"]} | {i["id"] for i in second["data"]["items"]}
        self.assertEqual(len(ids), 25)
        self.assertEqual(listing({"status": "EN_REVISION", "nextKey": cursor})[0], 400)  # otro filtro
        self.assertEqual(listing({"status": "RECIBIDO", "nextKey": cursor}, sub="otro-mod")[0], 400)  # otra cuenta
        self.assertEqual(listing({"status": "RECIBIDO", "nextKey": cursor}, now=NOW + 100 + 900)[1]["code"], "CURSOR_EXPIRED")
        self.assertEqual(listing({"status": "ABIERTO"})[0], 400)
        self.assertEqual(listing({"status": "EN_REVISION"})[1]["data"]["items"], [])

    def test_missing_index_is_a_clear_error_not_an_empty_list(self):
        self.plans.index_exists = False
        status, body = self.call(event("GET /moderacion/reportes", MOD, groups="PlanYaModeradores"))
        self.assertEqual((status, body["code"]), (503, "REPORTS_INDEX_UNAVAILABLE"))


class ReportDispatchTests(unittest.TestCase):
    """La Lambda principal envía solo las rutas de reportes al módulo reports."""

    def setUp(self):
        self.plans = FakePlans(plan(), plan(id=OTHER_PLAN, ownerId=STRANGER, attendeeIds=[STRANGER]))
        self.messages = FakeMessages()
        resource = Mock()
        resource.Table.side_effect = lambda name: {"PlanYaPlanes": self.plans, "PlanYaMensajes": self.messages}[name]
        fake_boto3 = types.ModuleType("boto3")
        fake_boto3.resource = Mock(return_value=resource)
        fake_boto3.client = Mock()
        reports_module = load("reports", "reports.py")
        with patch.dict(sys.modules, {"boto3": fake_boto3}):
            self.api = load("planya_lambda_reports", "lambda_function.py")
        self.modules = patch.dict(sys.modules, {"reports": reports_module})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.env = patch.dict(os.environ, {"TABLE_NAME": "PlanYaPlanes"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_lambda_routes_reports_to_module(self):
        response = self.api.lambda_handler(event("POST /reportes", STRANGER, body=plan_body()), None)
        self.assertEqual(response["statusCode"], 201)
        self.assertEqual(len(self.plans.reports()), 1)
        self.assertFalse(self.api.is_plan_item(self.plans.reports()[0]))  # nunca aparece como plan público
        response = self.api.lambda_handler(event("GET /moderacion/reportes", STRANGER), None)
        self.assertEqual(response["statusCode"], 403)


if __name__ == "__main__":
    unittest.main()
