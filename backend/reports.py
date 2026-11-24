"""US-12 — Reportar un problema (plan o mensaje del chat) y soporte de moderación.

Módulo separado: lambda_function.lambda_handler solo despacha estas rutas.

Rutas (API Gateway HTTP API, payload 2.0, todas con el autorizador JWT):
- POST  /reportes                    cuenta autenticada con acceso al contenido reportado
- GET   /moderacion/reportes         solo grupo PlanYaModeradores  (?status=RECIBIDO&nextKey=...)
- PATCH /moderacion/reportes/{id}    solo grupo PlanYaModeradores

Persistencia en PlanYaPlanes (sin tabla nueva), entityType REPORT, id "reporte#<uuid>":
reporterId (del token), targetType, planId, messageId («<ms 13 dígitos>#<uuid>», el cursor del chat),
reason, description, planTitle (referencia mínima), status, revision, createdAt, updatedAt,
reportSortKey («<createdAt 10 dígitos>#<uuid>»), requestId, payloadHash, history.
Sigue el contrato acordado con US-13 (docs/equipo/CONTRATO-REPORTES.md). Esta historia usa el índice
GSI_ReportsByStatus (status + reportSortKey) para la cola de moderación.

Reglas: el autor y el estado los fija el servidor; un reintento con el mismo requestId y el mismo
contenido devuelve el mismo reporte; con otro contenido responde 409. Reportar no cancela el plan,
no borra mensajes, no bloquea cuentas y no copia el texto del chat (respeta su TTL).
"""

import base64
import hashlib
import json
import re
import uuid
from decimal import Decimal

ROUTES = {"POST /reportes", "GET /moderacion/reportes", "PATCH /moderacion/reportes/{id}"}
REASONS = ("INAPPROPRIATE", "SPAM", "SAFETY", "OTHER")
STATUSES = ("RECIBIDO", "EN_REVISION", "RESUELTO", "DESCARTADO")
TRANSITIONS = {"RECIBIDO": ("EN_REVISION",), "EN_REVISION": ("RESUELTO", "DESCARTADO")}
CLOSED = ("RESUELTO", "DESCARTADO")
MODERATORS_GROUP = "PlanYaModeradores"
STATUS_INDEX = "GSI_ReportsByStatus"
PAGE_SIZE = 20
CURSOR_SECONDS = 15 * 60
CHAT_WINDOW_SECONDS = 2 * 60 * 60
MIN_TEXT, MAX_TEXT = 10, 500
_MESSAGE_CURSOR = re.compile(r"\d{13}#([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")


class ReportError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _json_number(value):
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    raise TypeError(f"Tipo no serializable: {type(value).__name__}")


def _response(status, payload):
    return {"statusCode": status,
            "headers": {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"},
            "body": json.dumps(payload, ensure_ascii=False, default=_json_number)}


def _error(status, message, code):
    return _response(status, {"success": False, "error": message, "code": code})


def _read_body(event):
    raw = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw, validate=True).decode("utf-8")
    if not isinstance(raw, str) or len(raw) > 8_000:
        raise ValueError("Solicitud demasiado grande")
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Solicitud no valida")
    return data


def _uuid(value, message):
    if not isinstance(value, str):
        raise ValueError(message)
    try:
        parsed = str(uuid.UUID(value))
    except ValueError as error:
        raise ValueError(message) from error
    if parsed != value.lower():
        raise ValueError(message)
    return parsed


def clean_text(value, field, minimum=MIN_TEXT, maximum=MAX_TEXT):
    """Texto de minimum a maximum caracteres tras quitar espacios extremos; admite saltos de línea."""
    if not isinstance(value, str):
        raise ValueError(f"Escribe {field}")
    text = value.replace("\r\n", "\n").replace("\r", "\n").strip()
    if any((ord(c) < 32 and c not in "\n\t") or 127 <= ord(c) < 160 or 0xD800 <= ord(c) <= 0xDFFF for c in text):
        raise ValueError(f"{field[0].upper()}{field[1:]} contiene caracteres no permitidos")
    if not minimum <= len(text) <= maximum:
        raise ValueError(f"{field[0].upper()}{field[1:]} debe tener entre {minimum} y {maximum} caracteres")
    return text


def groups(claims):
    """Grupos de Cognito del token. HTTP API puede entregarlos como lista o como "[a b]"."""
    raw = claims.get("cognito:groups")
    if isinstance(raw, (list, tuple)):
        return {str(item) for item in raw}
    if isinstance(raw, str):
        return {part for part in re.split(r"[\s,\[\]\"]+", raw) if part}
    return set()


def is_moderator(claims):
    return MODERATORS_GROUP in groups(claims)


def _conditional_failed(error):
    return getattr(error, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException"


def _missing_index(error):
    details = getattr(error, "response", {}).get("Error", {})
    return details.get("Code") == "ValidationException" and "index" in str(details.get("Message", "")).lower()


def _plan_member(plan, user):
    return user == plan.get("ownerId") or user in (plan.get("attendeeIds") or [])


def _number(value):
    return isinstance(value, (int, Decimal)) and not isinstance(value, bool)


def report_key(report_id):
    return "reporte#" + report_id


def public_report(item):
    """Lo que ve quien reportó: sin payloadHash, sin moderador, sin notas internas ni persona reportada."""
    result = {
        "id": item["id"][len("reporte#"):],
        "status": item["status"],
        "revision": int(item["revision"]),
        "createdAt": int(item["createdAt"]),
        "updatedAt": int(item["updatedAt"]),
        "targetType": item["targetType"],
        "planId": item["planId"],
        "planTitle": item.get("planTitle"),
        "reason": item["reason"],
        "description": item["description"],
        "publicResponse": item.get("publicResponse"),
    }
    if item.get("messageId"):
        result["messageId"] = item["messageId"]
    return result


def moderator_report(item):
    result = public_report(item)
    result.update({
        "reporterId": item["reporterId"],
        "reportedUserId": item.get("reportedUserId"),
        "history": [{key: (int(value) if _number(value) else value) for key, value in entry.items()}
                    for entry in item.get("history") or []],
        "internalNotes": list(item.get("internalNotes") or []),
    })
    return result


def _validate_target(table, messages, user, body, now):
    """Comprueba en el servidor que quien reporta puede ver el plan o el mensaje. Devuelve campos a guardar."""
    target_type = body.get("targetType")
    if target_type not in ("PLAN", "MESSAGE"):
        raise ValueError("Indica si reportas un plan o un mensaje")
    plan_id = _uuid(body.get("planId"), "Identificador de plan no valido")
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
    if not plan or plan.get("entityType") not in (None, "PLAN"):
        raise ReportError(404, "PLAN_NOT_FOUND", "El plan no existe o ya no está disponible")
    member = _plan_member(plan, user)
    title = str(plan.get("title") or "Plan sin título")[:120]
    if target_type == "PLAN":
        if plan.get("ownerId") == user:
            raise ValueError("No puedes reportar tu propio plan")
        expires = plan.get("expiresAt")
        visible = plan.get("status") != "CANCELLED" and (expires is None or (_number(expires) and expires > now))
        if not visible and not member:
            raise ReportError(404, "PLAN_NOT_FOUND", "El plan no existe o ya no está disponible")
        return {"targetType": "PLAN", "planId": plan_id, "planTitle": title,
                "reportedUserId": plan.get("ownerId")}
    # Mensaje: mismas reglas que el chat (miembro, chat abierto, mensaje de este plan y ajeno).
    # Contrato con US-13: messageId es el cursor del chat «<ms 13 dígitos>#<uuid>» (sin el prefijo msg#).
    cursor = body.get("messageId")
    match = _MESSAGE_CURSOR.fullmatch(cursor) if isinstance(cursor, str) else None
    if not match:
        raise ValueError("Referencia de mensaje no valida")
    message_id = match.group(1)
    if not member:
        raise ReportError(403, "NOT_MEMBER", "Solo quienes participan en el plan pueden reportar sus mensajes")
    expires = plan.get("expiresAt")
    if plan.get("status") == "CANCELLED" or not _number(expires) or now >= int(expires) + CHAT_WINDOW_SECONDS:
        raise ReportError(410, "CHAT_CLOSED", "El chat de este plan ya se cerró")
    message = messages.get_item(Key={"planId": plan_id, "sk": "msg#" + cursor}, ConsistentRead=True).get("Item")
    if not message or message.get("entityType") != "MESSAGE" or message.get("id") != message_id \
            or message.get("kind") == "SYSTEM":
        # No se revela si el mensaje existe en otro plan.
        raise ReportError(404, "MESSAGE_NOT_FOUND", "El mensaje no existe o ya no está disponible")
    if message.get("senderId") == user:
        raise ValueError("No puedes reportar tu propio mensaje")
    # Solo la referencia: el texto del chat no se copia para no saltarse su vencimiento.
    return {"targetType": "MESSAGE", "planId": plan_id, "planTitle": title, "messageId": cursor,
            "reportedUserId": message.get("senderId")}


def create_report(table, messages, claims, body, now):
    user = claims["sub"]
    request_id = _uuid(body.get("requestId"), "requestId debe ser un UUID")
    reason = body.get("reason")
    if reason not in REASONS:
        raise ValueError("Elige un motivo")
    description = clean_text(body.get("description"), "la descripción")
    # El contenido de la solicitud define el intento; reporterId, status o revision del cuerpo se ignoran.
    payload = {"targetType": body.get("targetType"), "planId": body.get("planId"),
               "messageId": body.get("messageId"), "reason": reason, "description": description}
    payload_hash = hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    key = report_key(str(uuid.uuid5(uuid.NAMESPACE_URL, f"planya-report:{user}:{request_id}")))

    def previous():
        existing = table.get_item(Key={"id": key}, ConsistentRead=True).get("Item")
        if not existing:
            return None
        if existing.get("entityType") != "REPORT" or existing.get("reporterId") != user \
                or existing.get("payloadHash") != payload_hash:
            raise ReportError(409, "REQUEST_CONFLICT", "Ese intento ya se usó con otro contenido. Vuelve a enviar")
        return _response(200, {"success": True, "data": public_report(existing), "duplicate": True})

    # Reintento de un envío que ya se guardó: se devuelve el mismo recibo aunque luego cambie el acceso.
    repeated = previous()
    if repeated:
        return repeated
    target = _validate_target(table, messages, user, body, now)
    item = {
        "id": key, "entityType": "REPORT", "reporterId": user, **target,
        "reason": reason, "description": description,
        "status": "RECIBIDO", "revision": 0, "createdAt": now, "updatedAt": now,
        "reportSortKey": f"{now:010d}#{key[len('reporte#'):]}", "requestId": request_id, "payloadHash": payload_hash,
        "history": [{"status": "RECIBIDO", "at": now}],
    }
    try:
        table.put_item(Item=item, ConditionExpression="attribute_not_exists(id)")
    except Exception as error:
        if not _conditional_failed(error):
            raise
        repeated = previous()  # dos envíos simultáneos del mismo intento
        if repeated:
            return repeated
        raise
    return _response(201, {"success": True, "data": public_report(item)})


def _cursor_key(cursor):
    return {"planId": "moderacion#" + cursor, "sk": "cursor"}


def list_for_moderation(table, messages, claims, query, now):
    status = query.get("status") or "RECIBIDO"
    if status not in STATUSES:
        raise ValueError("Estado no valido")
    options = {"IndexName": STATUS_INDEX, "KeyConditionExpression": "#status = :status",
               "ExpressionAttributeNames": {"#status": "status"}, "ExpressionAttributeValues": {":status": status},
               "ScanIndexForward": False, "Limit": PAGE_SIZE}
    raw_cursor = query.get("nextKey")
    if raw_cursor is not None:
        cursor = _uuid(raw_cursor, "Continuación no valida")
        saved = messages.get_item(Key=_cursor_key(cursor), ConsistentRead=True).get("Item")
        # Cursores opacos, ligados a la cuenta, al filtro y con vencimiento.
        if not saved or saved.get("entityType") != "MODERATION_CURSOR" or saved.get("userId") != claims["sub"] \
                or saved.get("status") != status:
            raise ValueError("Continuación no valida")
        if int(saved.get("expiresAt", 0)) <= now:
            raise ReportError(409, "CURSOR_EXPIRED", "La lista cambió. Actualiza para continuar")
        options["ExclusiveStartKey"] = json.loads(saved["key"])
    try:
        result = table.query(**options)
    except Exception as error:
        if _missing_index(error):
            raise ReportError(503, "REPORTS_INDEX_UNAVAILABLE", "Falta el índice de reportes en AWS (GSI_ReportsByStatus)")
        raise
    items = [moderator_report(item) for item in result.get("Items", []) if item.get("entityType") == "REPORT"]
    next_key = None
    if result.get("LastEvaluatedKey"):
        next_key = str(uuid.uuid4())
        messages.put_item(Item={**_cursor_key(next_key), "entityType": "MODERATION_CURSOR",
                                "userId": claims["sub"], "status": status,
                                "key": json.dumps(result["LastEvaluatedKey"], default=_json_number),
                                "expiresAt": now + CURSOR_SECONDS})
    return _response(200, {"success": True, "data": {"items": items, "nextKey": next_key, "status": status}})


def update_report(table, claims, report_id, body, now):
    report_id = _uuid(report_id, "Identificador de reporte no valido")
    request_id = _uuid(body.get("requestId"), "requestId debe ser un UUID")
    expected = body.get("expectedRevision")
    if isinstance(expected, bool) or not isinstance(expected, int) or expected < 0:
        raise ValueError("expectedRevision no valido")
    target = body.get("status")
    if target not in STATUSES:
        raise ValueError("Estado no valido")
    current = table.get_item(Key={"id": report_key(report_id)}, ConsistentRead=True).get("Item")
    if not current or current.get("entityType") != "REPORT":
        raise ReportError(404, "REPORT_NOT_FOUND", "El reporte no existe")
    if current.get("lastRequestId") == request_id:
        if current.get("status") == target:  # reintento de un cambio ya aplicado: mismo recibo
            return _response(200, {"success": True, "data": moderator_report(current), "duplicate": True})
        raise ReportError(409, "REQUEST_CONFLICT", "Ese intento ya se usó con otro cambio")
    if int(current["revision"]) != expected:
        raise ReportError(409, "REVISION_CONFLICT", "Otra persona actualizó el reporte. Vuelve a cargarlo")
    if target not in TRANSITIONS.get(current["status"], ()):
        raise ReportError(409, "INVALID_TRANSITION", f"No se puede pasar de {current['status']} a {target}")
    updated = dict(current)
    if target in CLOSED:
        updated["publicResponse"] = clean_text(body.get("publicResponse"), "la respuesta pública")
        updated["closedAt"] = now
    elif body.get("publicResponse") not in (None, ""):
        raise ValueError("La respuesta pública se escribe al cerrar el reporte")
    note = body.get("internalNote")
    if note not in (None, ""):
        updated["internalNotes"] = list(current.get("internalNotes") or []) + [
            {"text": clean_text(note, "la nota interna", 1, MAX_TEXT), "at": now, "by": claims["sub"]}]
    updated.update({"status": target, "revision": expected + 1, "updatedAt": now, "lastRequestId": request_id,
                    # Auditoría: quién, cuándo y de qué estado a cuál (no se muestra a quien reportó).
                    "history": list(current.get("history") or []) + [
                        {"status": target, "from": current["status"], "at": now, "by": claims["sub"],
                         "requestId": request_id}],
                    "reportSortKey": current.get("reportSortKey")
                    or f"{int(current['createdAt']):010d}#{current['id'][len('reporte#'):]}"})
    try:
        table.put_item(Item=updated, ConditionExpression="#revision = :expected AND #status = :current",
                       ExpressionAttributeNames={"#revision": "revision", "#status": "status"},
                       ExpressionAttributeValues={":expected": expected, ":current": current["status"]})
    except Exception as error:
        if _conditional_failed(error):
            raise ReportError(409, "REVISION_CONFLICT", "Otra persona actualizó el reporte. Vuelve a cargarlo")
        raise
    return _response(200, {"success": True, "data": moderator_report(updated)})


def handle(table, messages, event, now):
    """Punto de entrada de las rutas de reportes. now en segundos UTC (se inyecta en pruebas)."""
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return _error(401, "Inicia sesión para continuar", "UNAUTHORIZED")
    route = event.get("routeKey")
    if route not in ROUTES:
        return _error(404, "Ruta no disponible", "NOT_FOUND")
    try:
        if route == "POST /reportes":
            return create_report(table, messages, claims, _read_body(event), now)
        # Moderación: el rol sale del grupo de Cognito en el token, nunca del cuerpo ni del perfil.
        if not is_moderator(claims):
            return _error(403, "Solo el equipo de moderación puede revisar reportes", "NOT_MODERATOR")
        if route == "GET /moderacion/reportes":
            return list_for_moderation(table, messages, claims, event.get("queryStringParameters") or {}, now)
        return update_report(table, claims, (event.get("pathParameters") or {}).get("id"), _read_body(event), now)
    except ReportError as error:
        return _error(error.status, error.message, error.code)
    except (ValueError, TypeError, UnicodeDecodeError) as error:
        return _error(400, str(error), "INVALID")
