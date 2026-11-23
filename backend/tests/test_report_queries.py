"""US-13 Mis reportes: consultas aisladas, sin AWS, usuarios reales ni credenciales."""

import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
import uuid
from decimal import Decimal
from unittest.mock import Mock, patch

import test_lambda_function as core

spec = importlib.util.spec_from_file_location("reports_under_test", Path(__file__).resolve().parents[1] / "report_queries.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

NOW = 2_000_000_000
PLAN = "11111111-2222-4333-8444-555555555555"


class CursorError(ValueError):
    def __init__(self, expired=False):
        self.status = 409 if expired else 400
        self.code = "CURSOR_EXPIRED" if expired else "CURSOR_INVALID"
        super().__init__("La continuacion vencio o no es valida. Actualiza la lista")


class Cursors:
    """Misma regla que lambda_function: cursor UUID opaco ligado a scope y con vencimiento."""

    def __init__(self):
        self.saved = {}

    def read(self, cursor, scope, now):
        if cursor is None:
            return None
        saved = self.saved.get(cursor)
        if not saved or saved["scope"] != scope:
            raise CursorError()
        if saved["expiresAt"] <= now:
            raise CursorError(expired=True)
        return saved["key"]

    def save(self, key, scope, now):
        if key is None:
            return None
        cursor = str(uuid.uuid4())
        self.saved[cursor] = {"scope": scope, "key": key, "expiresAt": now + 900}
        return cursor


class MissingIndex(Exception):
    response = {"Error": {"Code": "ValidationException"}}


class Store:
    """Tabla aislada: get_item por id y query que imita GSI_ReportsByReporter."""

    def __init__(self):
        self.items = {}
        self.queries = []
        self.writes = 0
        self.fail = None

    def add(self, **item):
        self.items[item["id"]] = item
        return item

    def get_item(self, Key, ConsistentRead):
        assert ConsistentRead
        item = self.items.get(Key["id"])
        return {"Item": copy.deepcopy(item)} if item else {}

    def query(self, **options):
        self.queries.append(copy.deepcopy(options))
        if self.fail:
            raise self.fail
        assert options["IndexName"] == "GSI_ReportsByReporter"
        assert options["KeyConditionExpression"] == "reporterId = :user"
        assert options["ScanIndexForward"] is False
        user = options["ExpressionAttributeValues"][":user"]
        allowed = {value for key, value in options["ExpressionAttributeValues"].items() if key.startswith(":s")}
        rows = sorted((item for item in self.items.values() if item.get("reporterId") == user and "reportSortKey" in item),
                      key=lambda item: item["reportSortKey"], reverse=True)
        start = options.get("ExclusiveStartKey")
        if start:
            rows = [row for row in rows if row["reportSortKey"] < start["reportSortKey"]]
        page, rest = rows[:options["Limit"]], rows[options["Limit"]:]
        visible = [copy.deepcopy(row) for row in page
                   if row.get("entityType") == "REPORT" and row.get("status") in allowed]
        result = {"Items": visible}
        if rest:
            last = page[-1]
            result["LastEvaluatedKey"] = {"id": last["id"], "reporterId": last["reporterId"],
                                          "reportSortKey": last["reportSortKey"]}
        return result

    def put_item(self, **_):
        self.writes += 1

    def update_item(self, **_):
        self.writes += 1


class Messages:
    def __init__(self):
        self.items = {}

    def get_item(self, Key, ConsistentRead):
        assert ConsistentRead
        item = self.items.get((Key["planId"], Key["sk"]))
        return {"Item": copy.deepcopy(item)} if item else {}


def report(store, user="ana", created=1_900_000_000, status="RECIBIDO", **extra):
    raw = str(uuid.uuid4())
    item = {"id": "reporte#" + raw, "entityType": "REPORT", "reporterId": user, "targetType": "PLAN",
            "planId": PLAN, "reason": "INAPPROPRIATE", "description": "Solicito revisar el contenido de este encuentro.",
            "planTitle": "Café por Miraflores", "status": status, "createdAt": Decimal(created),
            "updatedAt": Decimal(created), "revision": Decimal(0), "reportSortKey": f"{created:010d}#{raw}",
            "requestId": str(uuid.uuid4()), "payloadHash": "hash-interno-no-publico"}
    item.update(extra)
    store.add(**item)
    return raw


class ReportQueriesTests(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.messages = Messages()
        self.cursors = Cursors()
        self.store.add(id=PLAN, entityType="PLAN", title="Café por Miraflores", expiresAt=NOW + 3600)

    def call(self, route="GET /reportes", user="ana", estado=None, cursor=None, report_id=None, token_use="id"):
        event = {"routeKey": route, "requestContext": {"authorizer": {"jwt": {"claims": {"sub": user, "token_use": token_use}}}},
                 "queryStringParameters": {}}
        if estado is not None:
            event["queryStringParameters"]["estado"] = estado
        if cursor is not None:
            event["queryStringParameters"]["nextKey"] = cursor
        if report_id is not None:
            event["pathParameters"] = {"id": report_id}
        response = api.handle(event, self.store, self.messages, NOW, self.cursors.read, self.cursors.save, CursorError)
        return response["statusCode"], json.loads(response["body"])

    # CA 13-01
    def test_empty_history_is_confirmed_without_demo_items(self):
        status, body = self.call()
        self.assertEqual(status, 200)
        self.assertEqual(body["data"], {"userId": "ana", "filter": "TODOS", "items": [], "nextKey": None})

    def test_requires_id_token_session(self):
        for kwargs in ({"user": ""}, {"token_use": "access"}):
            status, body = self.call(**kwargs)
            self.assertEqual(status, 401)
            self.assertEqual(body["code"], "AUTH_REQUIRED")
        self.assertEqual(self.store.queries, [])

    # CA 13-02
    def test_report_created_by_us12_is_listed_and_expandable_with_same_id(self):
        raw = report(self.store)
        status, body = self.call()
        item = body["data"]["items"][0]
        self.assertEqual((status, item["id"], item["shortCode"]), (200, raw, raw.replace("-", "")[:4].upper()))
        status, detail = self.call("GET /reportes/{id}", report_id=raw)
        self.assertEqual(status, 200)
        self.assertEqual(detail["data"]["report"]["id"], raw)
        self.assertEqual(detail["data"]["report"]["description"], "Solicito revisar el contenido de este encuentro.")
        self.assertTrue(detail["data"]["report"]["targetAvailable"])

    # CA 13-03
    def test_filters_group_open_and_closed_states(self):
        ids = {status: report(self.store, status=status, created=1_900_000_000 + i)
               for i, status in enumerate(("RECIBIDO", "EN_REVISION", "RESUELTO", "DESCARTADO"))}
        _, open_ = self.call(estado="abiertos")
        _, closed = self.call(estado="CERRADOS")
        _, all_ = self.call()
        self.assertEqual({i["status"] for i in open_["data"]["items"]}, {"RECIBIDO", "EN_REVISION"})
        self.assertEqual({i["status"] for i in closed["data"]["items"]}, {"RESUELTO", "DESCARTADO"})
        self.assertEqual([i["id"] for i in all_["data"]["items"]], [ids["DESCARTADO"], ids["RESUELTO"], ids["EN_REVISION"], ids["RECIBIDO"]])
        self.assertEqual(self.call(estado="BORRADOS")[0], 400)

    def test_pagination_keeps_order_without_duplicates_and_empty_filtered_page_continues(self):
        for index in range(25):
            report(self.store, created=1_900_000_000 + index, status="RESUELTO" if index >= 5 else "RECIBIDO")
        _, first = self.call(estado="ABIERTOS")
        # La primera página (20 más recientes) son todos RESUELTO: vacía pero con continuación.
        self.assertEqual(first["data"]["items"], [])
        self.assertIsNotNone(first["data"]["nextKey"])
        _, second = self.call(estado="ABIERTOS", cursor=first["data"]["nextKey"])
        self.assertEqual(len(second["data"]["items"]), 5)
        self.assertIsNone(second["data"]["nextKey"])
        _, page1 = self.call()
        _, page2 = self.call(cursor=page1["data"]["nextKey"])
        ids = [i["id"] for i in page1["data"]["items"] + page2["data"]["items"]]
        self.assertEqual(len(ids), 25)
        self.assertEqual(len(set(ids)), 25)
        created = [i["createdAt"] for i in page1["data"]["items"] + page2["data"]["items"]]
        self.assertEqual(created, sorted(created, reverse=True))
        # El cursor no expone la clave interna.
        self.assertNotIn("reporte#", json.dumps(page1))
        self.assertEqual(str(uuid.UUID(page1["data"]["nextKey"])), page1["data"]["nextKey"])

    # CA 13-04
    def test_moderation_response_only_when_closed_and_no_private_fields(self):
        raw = report(self.store, status="RESUELTO", updatedAt=Decimal(1_900_000_500),
                     publicResponse="Revisamos el contenido y atendimos tu reporte.",
                     moderatorId="mod-secreto", internalNote="nota interna")
        pending = report(self.store, status="EN_REVISION", publicResponse="borrador que aún no es público")
        _, listing = self.call()
        _, detail = self.call("GET /reportes/{id}", report_id=raw)
        _, open_detail = self.call("GET /reportes/{id}", report_id=pending)
        text = json.dumps([listing, detail, open_detail])
        for secret in ("mod-secreto", "nota interna", "hash-interno-no-publico", "requestId", "revision", "reporterId"):
            self.assertNotIn(secret, text)
        self.assertEqual(detail["data"]["report"]["publicResponse"], "Revisamos el contenido y atendimos tu reporte.")
        self.assertEqual(detail["data"]["report"]["updatedAt"], 1_900_000_500)
        self.assertIsNone(open_detail["data"]["report"]["publicResponse"])
        self.assertFalse(open_detail["data"]["report"]["hasResponse"])

    # CA 13-05
    def test_other_account_cannot_read_report_or_reuse_cursor(self):
        mine = report(self.store, user="ana")
        for index in range(21):
            report(self.store, user="beto", created=1_900_000_000 + index)
        _, ana = self.call(user="ana")
        self.assertEqual([i["id"] for i in ana["data"]["items"]], [mine])
        missing = self.call("GET /reportes/{id}", user="beto", report_id=mine)
        unknown = self.call("GET /reportes/{id}", user="beto", report_id=str(uuid.uuid4()))
        self.assertEqual(missing, unknown)  # no revela que exista
        self.assertEqual(missing[0], 404)
        _, beto = self.call(user="beto")
        status, body = self.call(user="ana", cursor=beto["data"]["nextKey"])
        self.assertEqual((status, body["code"]), (400, "CURSOR_INVALID"))
        status, body = self.call(user="beto", estado="CERRADOS", cursor=beto["data"]["nextKey"])
        self.assertEqual(status, 400)

    def test_forged_cursor_key_for_other_account_is_rejected(self):
        report(self.store, user="beto")
        forged = self.cursors.save(json.dumps({"id": "reporte#x", "reporterId": "beto", "reportSortKey": "1900000000#" + str(uuid.uuid4())}),
                                   json.dumps(["reports", "ana", "TODOS"]), NOW)
        status, body = self.call(user="ana", cursor=forged)
        self.assertEqual((status, body["code"]), (400, "CURSOR_INVALID"))
        self.assertEqual(self.store.queries, [])

    def test_expired_cursor_asks_to_refresh(self):
        for index in range(21):
            report(self.store, created=1_900_000_000 + index)
        _, first = self.call()
        self.cursors.saved[first["data"]["nextKey"]]["expiresAt"] = NOW
        status, body = self.call(cursor=first["data"]["nextKey"])
        self.assertEqual((status, body["code"]), (409, "CURSOR_EXPIRED"))

    # CA 13-08
    def test_expired_or_deleted_target_is_unavailable_but_report_is_kept(self):
        gone_message = report(self.store, targetType="MESSAGE", messageId="1900000000000#" + str(uuid.uuid4()))
        expired_message_id = "1900000000001#" + str(uuid.uuid4())
        expired = report(self.store, targetType="MESSAGE", messageId=expired_message_id)
        self.messages.items[(PLAN, "msg#" + expired_message_id)] = {"text": "texto privado", "expiresAt": NOW - 1}
        live_id = "1900000000002#" + str(uuid.uuid4())
        live = report(self.store, targetType="MESSAGE", messageId=live_id)
        self.messages.items[(PLAN, "msg#" + live_id)] = {"text": "texto privado", "expiresAt": NOW + 60}
        cancelled_plan = str(uuid.uuid4())
        self.store.add(id=cancelled_plan, entityType="PLAN", status="CANCELLED")
        cancelled = report(self.store, planId=cancelled_plan)
        deleted = report(self.store, planId=str(uuid.uuid4()))
        results = {raw: self.call("GET /reportes/{id}", report_id=raw)[1]["data"]["report"]
                   for raw in (gone_message, expired, live, cancelled, deleted)}
        self.assertFalse(results[gone_message]["targetAvailable"])
        self.assertFalse(results[expired]["targetAvailable"])
        self.assertTrue(results[live]["targetAvailable"])
        self.assertFalse(results[cancelled]["targetAvailable"])
        self.assertFalse(results[deleted]["targetAvailable"])
        self.assertNotIn("texto privado", json.dumps(results))
        self.assertEqual(len(self.call()[1]["data"]["items"]), 5)

    def test_malformed_or_foreign_entities_are_never_listed(self):
        report(self.store, status="BORRADOR")
        report(self.store, entityType="RATING")
        report(self.store, targetType="USER")
        status, body = self.call()
        self.assertEqual((status, body["data"]["items"]), (200, []))
        self.assertEqual(self.call("GET /reportes/{id}", report_id="no-es-uuid")[0], 404)

    def test_missing_index_is_a_clear_error_not_an_empty_history(self):
        self.store.fail = MissingIndex()
        status, body = self.call()
        self.assertEqual((status, body["code"]), (503, "REPORTS_INDEX_UNAVAILABLE"))
        self.store.fail = RuntimeError("timeout")
        self.assertEqual(self.call()[1]["code"], "REPORTS_UNAVAILABLE")

    # CA 13-07 (servidor): solo lectura, no hay operación para cambiar estados.
    def test_queries_never_write(self):
        raw = report(self.store)
        self.call()
        self.call("GET /reportes/{id}", report_id=raw)
        self.assertEqual(self.store.writes, 0)
        self.assertEqual(self.call("PATCH /reportes/{id}", report_id=raw)[0], 404)


class LambdaDispatchTests(unittest.TestCase):
    def setUp(self):
        core.LambdaTests.setUp(self)
        self.saved = {}
        cursors = Mock()
        cursors.put_item.side_effect = lambda Item: self.saved.update({Item["planId"]: copy.deepcopy(Item)})
        cursors.get_item.side_effect = lambda Key, ConsistentRead: {"Item": self.saved.get(Key["planId"], {})}
        cursor_patch = patch.object(self.api, "page_cursor_table", return_value=cursors)
        cursor_patch.start()
        self.addCleanup(cursor_patch.stop)
        # La Lambda importa report_queries desde la raíz del ZIP; aquí se registra el módulo
        # bajo prueba para que funcione igual desde la raíz del repo o desde backend/.
        module_patch = patch.dict(sys.modules, {"report_queries": api})
        module_patch.start()
        self.addCleanup(module_patch.stop)

    def test_lambda_dispatches_reports_with_shared_protected_cursors(self):
        raw = str(uuid.uuid4())
        self.table.query.return_value = {"Items": [{"id": "reporte#" + raw, "entityType": "REPORT", "reporterId": "ana",
            "targetType": "PLAN", "planId": PLAN, "reason": "SPAM", "status": "RECIBIDO",
            "createdAt": Decimal(1), "reportSortKey": "0000000001#" + raw}],
            "LastEvaluatedKey": {"id": "reporte#" + raw, "reporterId": "ana", "reportSortKey": "0000000001#" + raw}}
        event = {"routeKey": "GET /reportes", "queryStringParameters": None,
                 "requestContext": {"authorizer": {"jwt": {"claims": {"sub": "ana", "token_use": "id"}}}}}
        response = self.api.lambda_handler(event, None)
        body = json.loads(response["body"])
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(body["data"]["items"][0]["id"], raw)
        self.assertEqual(str(uuid.UUID(body["data"]["nextKey"])), body["data"]["nextKey"])
        self.assertNotIn("reporte#", response["body"])

    def test_reports_never_leak_into_public_plans(self):
        self.table.scan.return_value = {"Items": [{"id": "reporte#" + str(uuid.uuid4()), "title": "x"},
                                                  {"id": "reporte#" + str(uuid.uuid4()), "entityType": "REPORT"}]}
        response = self.api.lambda_handler({"routeKey": "GET /planes", "requestContext": {}}, None)
        self.assertEqual(json.loads(response["body"])["data"]["items"], [])


if __name__ == "__main__":
    unittest.main()
