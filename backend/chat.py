"""US-08 — Chat temporal de un plan.

Módulo separado de lambda_function.py para no editar la Lambda de planes a la vez
que el resto del equipo. lambda_function.lambda_handler sigue siendo la entrada y
solo despacha estas rutas hacia handle().

Rutas (API Gateway HTTP API, payload 2.0, TODAS con el autorizador JWT PlanYaAcceso):
- GET  /planes/{id}/mensajes             página más reciente (30 mensajes)
- GET  /planes/{id}/mensajes?nextKey=C   mensajes ANTERIORES al cursor C
- GET  /planes/{id}/mensajes?after=C     mensajes NUEVOS posteriores al cursor C
- POST /planes/{id}/mensajes             cuerpo {"text": "...", "clientMessageId": "UUID"}

Tabla DynamoDB (variable MESSAGES_TABLE, predeterminada PlanYaMensajes):
- Clave de partición planId (String) y clave de ordenación sk (String).
- Mensaje:   sk = "msg#<milisegundos 13 dígitos>#<uuid>"  → orden estable por hora del servidor.
- Reintento: sk = "req#<uuid5(usuario, clientMessageId)>" → evita guardar dos veces el mismo envío.
- TTL: atributo expiresAt (Number, segundos Unix) = cierre del chat. El TTL solo LIMPIA
  y es asíncrono; el bloqueo de lectura y escritura lo hace este código con la hora del servidor.

Decisión del equipo (docs/equipo/US-08-CHAT.md): el final del encuentro es expiresAt del
plan y el chat cierra en chatExpiresAt = expiresAt + 7200. Un plan cancelado se bloquea
de inmediato. Un plan sin horario no recibe una caducidad inventada.
"""

import base64
import json
import re
import time
import uuid
from decimal import Decimal

CHAT_WINDOW_SECONDS = 2 * 60 * 60
MAX_TEXT = 1000
PAGE_SIZE = 30
NEWER_PAGE_SIZE = 50
ROUTES = {"GET /planes/{id}/mensajes", "POST /planes/{id}/mensajes"}
_CURSOR = re.compile(r"\d{13}#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


class ChatDenied(Exception):
    """El servidor rechaza el acceso al chat (404, 403 o 410)."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


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


def _read_body(event):
    raw = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw, validate=True).decode("utf-8")
    if not isinstance(raw, str) or len(raw) > 12_000:
        raise ValueError("Solicitud demasiado grande")
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Solicitud no valida")
    return data


def _canonical_uuid(value, message):
    if not isinstance(value, str):
        raise ValueError(message)
    try:
        parsed = str(uuid.UUID(value))
    except ValueError as error:
        raise ValueError(message) from error
    if parsed != value.lower():
        raise ValueError(message)
    return parsed


def _cursor(value, name):
    if value is None:
        return None
    if not isinstance(value, str) or not _CURSOR.fullmatch(value):
        raise ValueError(f"{name} no valido")
    return "msg#" + value


def clean_text(value):
    """Texto de 1 a 1000 caracteres tras recortar; admite saltos de línea."""
    if not isinstance(value, str):
        raise ValueError("Escribe un mensaje")
    text = value.replace("\r\n", "\n").replace("\r", "\n").strip()
    if not text:
        raise ValueError("Escribe un mensaje")
    if len(text) > MAX_TEXT:
        raise ValueError(f"El mensaje admite hasta {MAX_TEXT} caracteres")
    # Controles invisibles y mitades sueltas de emoji (DynamoDB no puede guardarlas en UTF-8).
    if any((ord(c) < 32 and c not in "\n\t") or 127 <= ord(c) < 160 or 0xD800 <= ord(c) <= 0xDFFF for c in text):
        raise ValueError("El mensaje contiene caracteres no permitidos")
    return text


def chat_access(plans, plan_id, user, now_seconds):
    """Comprueba en el servidor membresía, cancelación y horario en CADA lectura y escritura.

    Lee el registro del plan directamente (no el GET público, que oculta planes vencidos),
    así un participante conserva el acceso durante las dos horas posteriores al encuentro.
    """
    plan = plans.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
    if not plan or plan.get("entityType") == "PROFILE":
        raise ChatDenied(404, "PLAN_NOT_FOUND", "El plan no existe")
    members = plan.get("attendeeIds") or []
    # Primero la membresía: a una persona ajena no se le revela el estado del plan.
    if user != plan.get("ownerId") and user not in members:
        raise ChatDenied(403, "NOT_MEMBER", "Solo el anfitrion y los participantes confirmados pueden usar este chat")
    if plan.get("status") == "CANCELLED":
        raise ChatDenied(410, "PLAN_CANCELLED", "El plan fue cancelado y su chat se cerro")
    expires = plan.get("expiresAt")
    if isinstance(expires, bool) or not isinstance(expires, (int, Decimal)):
        raise ChatDenied(410, "NO_SCHEDULE", "El plan no tiene horario confirmado; su chat no esta disponible")
    chat_expires = int(expires) + CHAT_WINDOW_SECONDS
    if now_seconds >= chat_expires:
        raise ChatDenied(410, "CHAT_CLOSED", "El chat de este plan ya se cerro")
    return plan, chat_expires


def _sender_name(plans, plan, user, claims):
    profile = plans.get_item(Key={"id": "perfil#" + user}, ConsistentRead=True).get("Item")
    if isinstance(profile, dict) and profile.get("entityType") == "PROFILE" and profile.get("name"):
        return str(profile["name"])[:80]
    ids, names = plan.get("attendeeIds") or [], plan.get("attendees") or []
    if user in ids and ids.index(user) < len(names):
        return str(names[ids.index(user)])[:80]
    return str(claims.get("name") or "Usuario PlanYa")[:80]


def public_message(item, viewer):
    result = {
        "id": item["id"],
        "planId": item["planId"],
        "senderId": item["senderId"],
        "senderName": item["senderName"],
        "text": item["text"],
        "createdAt": int(item["createdAt"]),
        "cursor": item["sk"][len("msg#"):],
        "expiresAt": int(item["expiresAt"]),
    }
    # El identificador de reintento solo le sirve a quien envió el mensaje.
    if item["senderId"] == viewer and item.get("clientMessageId"):
        result["clientMessageId"] = item["clientMessageId"]
    return result


def list_messages(plans, messages, plan_id, user, query, now_ms):
    older = _cursor(query.get("nextKey"), "nextKey")
    newer = _cursor(query.get("after"), "after")
    if older and newer:
        raise ValueError("Usa nextKey o after, no ambos")
    plan, chat_expires = chat_access(plans, plan_id, user, now_ms // 1000)
    options = {
        "KeyConditionExpression": "#plan = :plan AND begins_with(#sk, :prefix)",
        "ExpressionAttributeNames": {"#plan": "planId", "#sk": "sk"},
        "ExpressionAttributeValues": {":plan": plan_id, ":prefix": "msg#"},
        "ConsistentRead": True,
    }
    if newer:
        options.update(ScanIndexForward=True, Limit=NEWER_PAGE_SIZE,
                       ExclusiveStartKey={"planId": plan_id, "sk": newer})
    else:
        options.update(ScanIndexForward=False, Limit=PAGE_SIZE)
        if older:
            options["ExclusiveStartKey"] = {"planId": plan_id, "sk": older}
    result = messages.query(**options)
    items = sorted((item for item in result.get("Items", []) if item.get("entityType") == "MESSAGE"),
                   key=lambda item: item["sk"])
    last = (result.get("LastEvaluatedKey") or {}).get("sk")
    cursor = last[len("msg#"):] if isinstance(last, str) and last.startswith("msg#") else None
    return {
        "success": True,
        "data": {
            "items": [public_message(item, user) for item in items],
            "nextKey": None if newer else cursor,
            "afterKey": cursor if newer else None,
            "chatExpiresAt": chat_expires,
            "serverTime": now_ms // 1000,
            "planTitle": plan.get("title"),
            "isHost": plan.get("ownerId") == user,
            # Actualización manual: la app no debe presentarlo como tiempo real.
            "realtime": False,
        },
    }


def _conditional_failed(error):
    return getattr(error, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException"


def send_message(plans, messages, plan_id, user, claims, body, now_ms):
    text = clean_text(body.get("text"))
    client_id = _canonical_uuid(body.get("clientMessageId"), "clientMessageId debe ser un UUID")
    plan, chat_expires = chat_access(plans, plan_id, user, now_ms // 1000)
    message_id = str(uuid.uuid4())
    item = {
        "planId": plan_id,
        "sk": f"msg#{now_ms:013d}#{message_id}",
        "entityType": "MESSAGE",
        "id": message_id,
        # Autor y hora salen del token verificado y del reloj del servidor, nunca del cuerpo.
        "senderId": user,
        "senderName": _sender_name(plans, plan, user, claims),
        "text": text,
        "createdAt": now_ms,
        "clientMessageId": client_id,
        "expiresAt": chat_expires,
    }
    request = {
        "planId": plan_id,
        "sk": "req#" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"planya-chat:{user}:{client_id}")),
        "entityType": "MESSAGE_REQUEST",
        "senderId": user,
        "message": item,
        "expiresAt": chat_expires,
    }
    try:
        messages.put_item(Item=request, ConditionExpression="attribute_not_exists(sk)")
    except Exception as error:
        if not _conditional_failed(error):
            raise
        # Reintento de un envío que ya llegó: devolver el mismo mensaje, sin duplicarlo.
        existing = messages.get_item(Key={"planId": plan_id, "sk": request["sk"]}, ConsistentRead=True).get("Item")
        if not existing or existing.get("senderId") != user or not isinstance(existing.get("message"), dict):
            return _error(409, "No se pudo confirmar el envio. Actualiza el chat", "RETRY_CONFLICT")
        previous = existing["message"]
        if previous.get("text") != text:
            return _error(409, "Ese identificador ya se uso con otro mensaje", "RETRY_CONFLICT")
        # Si la primera ejecución se interrumpió entre las dos escrituras, se completa aquí.
        messages.put_item(Item=previous)
        return _response(200, {"success": True, "data": public_message(previous, user), "duplicate": True})
    messages.put_item(Item=item)
    return _response(201, {"success": True, "data": public_message(item, user)})


def handle(event, plans, messages, now_ms=None):
    """Punto de entrada de las rutas de chat. now_ms se inyecta en las pruebas."""
    now_ms = int(time.time() * 1000) if now_ms is None else int(now_ms)
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return _error(401, "Inicia sesion para usar el chat")
    route = event.get("routeKey")
    if route not in ROUTES:
        return _error(404, "Ruta no disponible")
    try:
        plan_id = _canonical_uuid((event.get("pathParameters") or {}).get("id"), "Identificador de plan no valido")
        if route == "GET /planes/{id}/mensajes":
            query = event.get("queryStringParameters") or {}
            return _response(200, list_messages(plans, messages, plan_id, user, query, now_ms))
        return send_message(plans, messages, plan_id, user, claims, _read_body(event), now_ms)
    except ChatDenied as denied:
        return _error(denied.status, denied.message, denied.code)
    except (ValueError, TypeError, UnicodeDecodeError) as error:
        return _error(400, str(error), "INVALID")
