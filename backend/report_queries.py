"""US-13 — Mis reportes: consulta privada de los reportes creados por la propia cuenta.

Módulo separado (como chat.py y ratings.py). lambda_function.lambda_handler solo despacha:
- GET /reportes?estado=TODOS|ABIERTOS|CERRADOS&nextKey=<cursor opaco>
- GET /reportes/{id}

Ambas rutas usan el autorizador JWT existente. La cuenta sale SIEMPRE de los claims
(sub, token_use=id); Android no puede elegir reporterId, estado ni permisos.

Modelo REPORT compartido con US-12 (ver docs/equipo/CONTRATO-REPORTES.md), en PlanYaPlanes:
  id="reporte#<uuid>", entityType="REPORT", reporterId, targetType=PLAN|MESSAGE, planId,
  messageId (solo MESSAGE), reason, description, planTitle, status, createdAt, updatedAt,
  revision, reportSortKey="<createdAt 10 dígitos>#<uuid>", requestId, payloadHash,
  publicResponse (solo la escribe moderación al cerrar).
Índice GSI_ReportsByReporter: partición reporterId, orden reportSortKey.

Esta pantalla es de solo lectura: no cambia estados, no borra y no aplica sanciones.
"""

import json
import os
import re
import uuid
from decimal import Decimal

ROUTES = {"GET /reportes", "GET /reportes/{id}"}
PAGE_SIZE = 20
REPORT_PREFIX = "reporte#"
STATUSES = ("RECIBIDO", "EN_REVISION", "RESUELTO", "DESCARTADO")
FILTERS = {
    "TODOS": STATUSES,
    "ABIERTOS": ("RECIBIDO", "EN_REVISION"),
    "CERRADOS": ("RESUELTO", "DESCARTADO"),
}
TARGETS = {"PLAN", "MESSAGE"}
REASONS = {"INAPPROPRIATE", "SPAM", "SAFETY", "OTHER"}
_SORT_KEY = re.compile(r"\d{10}#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
_MESSAGE_ID = re.compile(r"\d{13}#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def _json_number(value):
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    raise TypeError(f"Tipo no serializable: {type(value).__name__}")


def _response(status, payload):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"},
        "body": json.dumps(payload, ensure_ascii=False, default=_json_number),
    }


def _error(status, message, code=None):
    payload = {"success": False, "error": message}
    if code:
        payload["code"] = code
    return _response(status, payload)


def _canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def _int(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, Decimal)) and value == int(value):
        return int(value)
    return None


def _text(value, limit):
    return value.strip()[:limit] if isinstance(value, str) and value.strip() else None


def is_own_report(item, user):
    """Lista positiva: solo un REPORT bien formado de esta cuenta llega a la respuesta."""
    return (isinstance(item, dict) and item.get("entityType") == "REPORT"
            and item.get("reporterId") == user and isinstance(item.get("id"), str)
            and item["id"].startswith(REPORT_PREFIX) and _canonical_uuid(item["id"][len(REPORT_PREFIX):])
            and item.get("status") in STATUSES and item.get("targetType") in TARGETS
            and _int(item.get("createdAt")) is not None)


def public_summary(item):
    raw_id = item["id"][len(REPORT_PREFIX):]
    created = _int(item.get("createdAt"))
    updated = _int(item.get("updatedAt"))
    closed = item["status"] in FILTERS["CERRADOS"]
    return {
        "id": raw_id,
        # Solo para mostrar; las consultas usan siempre el id completo.
        "shortCode": raw_id.replace("-", "")[:4].upper(),
        "targetType": item["targetType"],
        "planTitle": _text(item.get("planTitle"), 120),
        "reason": item.get("reason") if item.get("reason") in REASONS else "OTHER",
        "status": item["status"],
        "createdAt": created,
        "updatedAt": updated if updated is not None and updated >= created else created,
        "hasResponse": closed and _text(item.get("publicResponse"), 500) is not None,
    }


def _target_available(item, table, messages, now):
    plan_id = item.get("planId")
    if not _canonical_uuid(plan_id):
        return False
    if item["targetType"] == "MESSAGE":
        message_id = item.get("messageId")
        if messages is None or not isinstance(message_id, str) or not _MESSAGE_ID.fullmatch(message_id):
            return False
        message = messages.get_item(Key={"planId": plan_id, "sk": "msg#" + message_id},
                                    ConsistentRead=True).get("Item")
        # El TTL limpia tarde: un mensaje con expiresAt pasado ya no está disponible.
        expires = _int((message or {}).get("expiresAt"))
        return bool(message) and (expires is None or expires > now)
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
    if not plan or plan.get("entityType") not in (None, "PLAN") or plan.get("status") == "CANCELLED":
        return False
    expires = _int(plan.get("expiresAt"))
    return expires is None or expires > now


def public_detail(item, table, messages, now):
    detail = public_summary(item)
    detail["description"] = _text(item.get("description"), 500)
    # Respuesta pública solo cuando moderación cerró el reporte. Nunca notas internas,
    # identidad del moderador, payloadHash, requestId ni revision.
    detail["publicResponse"] = _text(item.get("publicResponse"), 500) if detail["hasResponse"] else None
    # Nunca se devuelve el texto del mensaje ni datos del plan: solo si sigue disponible.
    detail["targetAvailable"] = _target_available(item, table, messages, now)
    return detail


def _encode_key(key, user):
    if not key:
        return None
    if (key.get("reporterId") != user or not isinstance(key.get("id"), str)
            or not isinstance(key.get("reportSortKey"), str)):
        return None
    return json.dumps({"id": key["id"], "reporterId": key["reporterId"],
                       "reportSortKey": key["reportSortKey"]}, separators=(",", ":"))


def _decode_key(raw, user, cursor_error):
    try:
        key = json.loads(raw)
    except (TypeError, ValueError):
        raise cursor_error() from None
    if (not isinstance(key, dict) or set(key) != {"id", "reporterId", "reportSortKey"}
            or key["reporterId"] != user or not isinstance(key["id"], str)
            or not key["id"].startswith(REPORT_PREFIX) or not isinstance(key["reportSortKey"], str)
            or not _SORT_KEY.fullmatch(key["reportSortKey"])):
        raise cursor_error()
    return key


def _index_missing(error):
    code = getattr(error, "response", {}).get("Error", {}).get("Code")
    return code in {"ValidationException", "ResourceNotFoundException"}


def list_reports(table, event, user, now, read_cursor, save_cursor, cursor_error):
    query = event.get("queryStringParameters") or {}
    raw_filter = query.get("estado", "TODOS")
    status_filter = raw_filter.upper() if isinstance(raw_filter, str) else None
    if status_filter not in FILTERS:
        return _error(400, "Filtro no válido", "INVALID_FILTER")
    # El cursor queda ligado a la cuenta y al filtro: no sirve para otra persona.
    scope = json.dumps(["reports", user, status_filter])
    try:
        raw_key = read_cursor(query.get("nextKey"), scope, now)
        start = _decode_key(raw_key, user, cursor_error) if raw_key is not None else None
    except cursor_error as error:
        return _error(error.status, str(error), error.code)
    statuses = FILTERS[status_filter]
    names = {"#st": "status"}
    values = {":user": user, ":type": "REPORT"}
    for index, status in enumerate(statuses):
        values[f":s{index}"] = status
    options = {
        "IndexName": os.environ.get("REPORTS_INDEX", "GSI_ReportsByReporter"),
        "KeyConditionExpression": "reporterId = :user",
        "FilterExpression": "entityType = :type AND #st IN (" + ", ".join(f":s{i}" for i in range(len(statuses))) + ")",
        "ExpressionAttributeNames": names,
        "ExpressionAttributeValues": values,
        "ScanIndexForward": False,
        "Limit": PAGE_SIZE,
    }
    if start:
        options["ExclusiveStartKey"] = start
    try:
        result = table.query(**options)
    except Exception as error:
        if _index_missing(error):
            # Un índice faltante no se presenta como "no tienes reportes".
            return _error(503, "Mis reportes todavía no está configurado en el servidor", "REPORTS_INDEX_UNAVAILABLE")
        return _error(503, "No pudimos consultar tus reportes. Reintenta", "REPORTS_UNAVAILABLE")
    items = [public_summary(item) for item in result.get("Items", []) if is_own_report(item, user)
             and item["status"] in statuses]
    # Una página puede quedar vacía por el filtro y aún tener continuación: se devuelve igual.
    next_key = save_cursor(_encode_key(result.get("LastEvaluatedKey"), user), scope, now)
    return _response(200, {"success": True, "data": {"userId": user, "filter": status_filter,
                                                      "items": items, "nextKey": next_key}})


def get_report(table, messages, event, user, now):
    raw_id = (event.get("pathParameters") or {}).get("id")
    if not _canonical_uuid(raw_id):
        return _error(404, "No encontramos este reporte", "REPORT_NOT_FOUND")
    item = table.get_item(Key={"id": REPORT_PREFIX + raw_id}, ConsistentRead=True).get("Item")
    # Ajeno o inexistente responden igual: no se confirma que exista el de otra cuenta.
    if not is_own_report(item, user):
        return _error(404, "No encontramos este reporte", "REPORT_NOT_FOUND")
    return _response(200, {"success": True, "data": {"userId": user,
                                                      "report": public_detail(item, table, messages, now)}})


def handle(event, table, messages, now, read_cursor, save_cursor, cursor_error):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not isinstance(user, str) or not user or claims.get("token_use") != "id":
        return _error(401, "Inicia sesión para ver tus reportes", "AUTH_REQUIRED")
    route = event.get("routeKey")
    if route == "GET /reportes":
        return list_reports(table, event, user, now, read_cursor, save_cursor, cursor_error)
    if route == "GET /reportes/{id}":
        return get_report(table, messages, event, user, now)
    return _error(404, "Ruta no disponible")
