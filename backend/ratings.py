"""Valoraciones de encuentros terminados: asistencia acreditada, voto unico y recibidas."""
import hashlib
import json
import logging
import os
import re
import uuid

RECEIVED_PAGE_SIZE = 20
CURSOR_SECONDS = 15 * 60


def response(status, message=None, data=None, code=None):
    body = {"success": status == 200, **({"data": data} if data is not None else {"error": message})}
    if code is not None:
        body["code"] = code
    return {"statusCode": status, "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
            "body": json.dumps(body)}


def attendance_key(plan, user):
    return "asistencia#" + str(uuid.uuid5(uuid.NAMESPACE_URL, "planya-attendance:" + plan + ":" + user))


def rating_key(plan, author, target):
    return "valoracion#" + str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps([plan, author, target])))


def eligible_plan(plan, now):
    return (plan.get("entityType") in (None, "PLAN") and plan.get("status") != "CANCELLED"
            and isinstance(plan.get("expiresAt"), (int, float)) and plan["expiresAt"] <= now)


class ReceivedCursorError(ValueError):
    def __init__(self, expired=False):
        self.status = 409 if expired else 400
        self.code = "CURSOR_EXPIRED" if expired else "CURSOR_INVALID"
        super().__init__("La continuacion vencio o no es valida. Actualiza la lista")


def whole_number(value):
    """Acepta enteros de DynamoDB/JSON y rechaza booleanos, cadenas y decimales."""
    if isinstance(value, bool):
        return None
    try:
        number = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    try:
        return number if number == value else None
    except (TypeError, ValueError):
        return None


def received_cursor(cursors, raw, user, mode, now):
    """Lee la continuacion privada; nunca acepta una clave interna desde HTTP."""
    if raw is None:
        return None
    try:
        if not isinstance(raw, str) or str(uuid.UUID(raw)) != raw:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise ReceivedCursorError() from None
    if cursors is None:
        raise RuntimeError("cursor storage unavailable")
    saved = cursors.get_item(Key={"planId": "page#" + raw, "sk": "cursor"}, ConsistentRead=True).get("Item") or {}
    if (saved.get("entityType") != "PAGE_CURSOR" or saved.get("scope") != "received-ratings#" + user
            or saved.get("mode") != mode):
        raise ReceivedCursorError()
    try:
        expires = int(saved.get("expiresAt", 0))
    except (TypeError, ValueError):
        expires = 0
    if expires <= now:
        raise ReceivedCursorError(expired=True)
    try:
        key = json.loads(saved.get("key") or "")
    except (TypeError, ValueError):
        raise ReceivedCursorError() from None
    if not isinstance(key, dict):
        raise ReceivedCursorError()
    return key


def save_received_cursor(cursors, user, mode, last, now):
    if last is None:
        return None
    if mode == "index":
        payload = {"targetUserId": str(last.get("targetUserId") or ""),
                   "createdAt": whole_number(last.get("createdAt")), "id": str(last.get("id") or "")}
        valid = (payload["targetUserId"] == user and payload["createdAt"] is not None
                 and payload["createdAt"] > 0 and payload["id"].startswith("valoracion#"))
    else:
        payload = {"createdAt": whole_number(last.get("createdAt")), "id": str(last.get("id") or "")}
        valid = payload["createdAt"] is not None and payload["createdAt"] > 0 and payload["id"].startswith("valoracion#")
    if not valid:
        raise RuntimeError("invalid continuation key")
    if cursors is None:
        raise RuntimeError("cursor storage unavailable")
    raw = str(uuid.uuid4())
    cursors.put_item(Item={"planId": "page#" + raw, "sk": "cursor", "entityType": "PAGE_CURSOR",
                           "scope": "received-ratings#" + user, "mode": mode,
                           "key": json.dumps(payload, separators=(",", ":")), "expiresAt": now + CURSOR_SECONDS})
    return raw


def index_start(key, user):
    if set(key) != {"targetUserId", "createdAt", "id"}:
        raise ReceivedCursorError()
    created = whole_number(key.get("createdAt"))
    ident = key.get("id")
    if (key.get("targetUserId") != user or created is None or created <= 0
            or not isinstance(ident, str) or not ident.startswith("valoracion#")):
        raise ReceivedCursorError()
    return {"targetUserId": user, "createdAt": created, "id": ident}


def scan_start(key):
    if set(key) != {"createdAt", "id"}:
        raise ReceivedCursorError()
    created = whole_number(key.get("createdAt"))
    ident = key.get("id")
    if (created is None or created <= 0 or not isinstance(ident, str)
            or not ident.startswith("valoracion#")):
        raise ReceivedCursorError()
    return {"createdAt": created, "id": ident}


def received_summary(table, user):
    profile = table.get_item(Key={"id": "perfil#" + user}, ConsistentRead=True).get("Item") or {}
    try:
        count = int(profile.get("ratingCount", 0) or 0)
        total = float(profile.get("ratingSum", 0) or 0)
    except (TypeError, ValueError):
        count, total = 0, 0.0
    if count <= 0 or total <= 0:
        return 0, None
    average = round(total / count, 2)
    if not 1 <= average <= 5:
        return 0, None
    return count, average


def rating_values(row):
    plan_id = row.get("planId")
    score = whole_number(row.get("score"))
    created = whole_number(row.get("createdAt"))
    ident = row.get("id")
    if (not isinstance(plan_id, str) or not plan_id or score is None or not 1 <= score <= 5
            or created is None or created <= 0 or not isinstance(ident, str) or not ident):
        return None
    return {"planId": plan_id, "score": score, "createdAt": created, "id": ident, "row": row}


def received_item(table, rating, cache):
    """Solo expone campos del encuentro y la nota; nunca al evaluador."""
    plan_id = rating["planId"]
    if plan_id not in cache:
        cache[plan_id] = table.get_item(Key={"id": plan_id}).get("Item") or {}
    plan = cache[plan_id]
    available = (bool(plan) and plan.get("entityType") in (None, "PLAN")
                 and plan.get("status") != "CANCELLED")
    if available:
        title = " ".join(str(plan.get("title") or "Encuentro").split())[:120] or "Encuentro"
    else:
        title = "Encuentro no disponible"
    public_id = hashlib.sha256(("planya-received:" + rating["id"]).encode()).hexdigest()
    return {"id": public_id, "planId": plan_id, "planTitle": title, "score": rating["score"],
            "createdAt": rating["createdAt"], "planAvailable": available}


def received_index_page(table, user, start, index):
    options = {"IndexName": index, "KeyConditionExpression": "targetUserId = :target",
               "FilterExpression": "entityType = :rating",
               "ExpressionAttributeValues": {":target": user, ":rating": "RATING"},
               "ScanIndexForward": False, "Limit": RECEIVED_PAGE_SIZE}
    if start is not None:
        options["ExclusiveStartKey"] = index_start(start, user)
    page = table.query(**options)
    cache, items = {}, []
    for row in page.get("Items", []):
        rating = rating_values(row)
        if rating is not None:
            items.append(received_item(table, rating, cache))
    return items, page.get("LastEvaluatedKey")


def received_scan_page(table, user, start):
    """Fallback MVP mientras el indice de destinatario no este habilitado."""
    rows, options = [], {"ConsistentRead": True, "Limit": 100}
    for _ in range(100):
        page = table.scan(**options)
        rows.extend(page.get("Items", []))
        if not page.get("LastEvaluatedKey"):
            break
        options["ExclusiveStartKey"] = page["LastEvaluatedKey"]
    else:
        raise RuntimeError("scan limit reached")
    ratings = []
    for row in rows:
        if row.get("entityType") == "RATING" and row.get("targetUserId") == user:
            rating = rating_values(row)
            if rating is not None:
                ratings.append(rating)
    ratings.sort(key=lambda item: (item["createdAt"], item["id"]), reverse=True)
    offset = 0
    if start is not None:
        wanted = scan_start(start)
        position = next((position for position, rating in enumerate(ratings)
                         if rating["createdAt"] == wanted["createdAt"] and rating["id"] == wanted["id"]), None)
        if position is None:
            raise ReceivedCursorError()
        offset = position + 1
    selected = ratings[offset:offset + RECEIVED_PAGE_SIZE]
    last = None
    if offset + len(selected) < len(ratings):
        last = {"createdAt": selected[-1]["createdAt"], "id": selected[-1]["id"]}
    cache = {}
    return [received_item(table, rating, cache) for rating in selected], last


def received(table, event, now, cursors, user):
    query = event.get("queryStringParameters") or {}
    if not isinstance(query, dict):
        return response(400, "La continuacion no es valida", code="CURSOR_INVALID")
    index = os.environ.get("RATINGS_RECEIVED_INDEX", "").strip()
    mode = "index" if index else "scan"
    try:
        start = received_cursor(cursors, query.get("nextKey"), user, mode, now)
    except ReceivedCursorError as error:
        return response(error.status, str(error), code=error.code)
    try:
        count, average = received_summary(table, user)
        if mode == "index":
            items, last = received_index_page(table, user, start, index)
        else:
            items, last = received_scan_page(table, user, start)
        next_key = save_received_cursor(cursors, user, mode, last, now)
    except ReceivedCursorError as error:
        return response(error.status, str(error), code=error.code)
    except Exception:
        logging.exception("No se pudieron leer las valoraciones recibidas")
        return response(503, "No pudimos cargar tus opiniones. Reintenta mas tarde")
    return response(200, data={"userId": user, "count": count, "average": average,
                               "items": items, "nextKey": next_key})


def handle(table, event, now, client, cursors=None):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return response(401, "Inicia sesion para valorar")
    if event.get("routeKey") == "GET /perfil/valoraciones-recibidas":
        return received(table, event, now, cursors, user)
    if event.get("routeKey") == "GET /valoraciones":
        # Limite defensivo: si la tabla crece, no presentar una lista incompleta
        # como si no hubiera encuentros. Este MVP no es un indice de produccion.
        rows, options = [], {"ConsistentRead": True, "Limit": 100}
        for _ in range(100):
            page = table.scan(**options)
            rows.extend(page.get("Items", []))
            if not page.get("LastEvaluatedKey"):
                break
            options["ExclusiveStartKey"] = page["LastEvaluatedKey"]
        else:
            return response(503, "No pudimos completar la consulta. Reintenta mas tarde")
        plans = {row["id"]: row for row in rows if row.get("id") and row.get("entityType") in (None, "PLAN")}
        attended = {}
        for row in rows:
            if row.get("entityType") == "ATTENDANCE" and row.get("planId") and row.get("userId"):
                attended.setdefault(row["planId"], set()).add(row["userId"])
        votes = {(row.get("planId"), row.get("targetUserId")) for row in rows
                 if row.get("entityType") == "RATING" and row.get("authorUserId") == user}
        items = []
        for plan_id, members in attended.items():
            plan = plans.get(plan_id, {})
            # DynamoDB numeros son Decimal; comparar sin convertir a float.
            if user not in members or plan.get("status") == "CANCELLED" or "expiresAt" not in plan or plan["expiresAt"] > now:
                continue
            for target in sorted(members - {user}):
                if (plan_id, target) in votes:
                    continue
                names = dict(zip(plan.get("attendeeIds", []), plan.get("attendees", [])))
                if plan.get("ownerId"):
                    names[plan["ownerId"]] = plan.get("authorName")
                items.append({"planId": plan_id, "targetUserId": target,
                              "planTitle": str(plan.get("title") or "Encuentro")[:120],
                              "name": str(names.get(target) or "Participante")[:80]})
        return response(200, data={"userId": user, "items": sorted(items, key=lambda item: (item["planId"], item["targetUserId"]))})
    try:
        raw = event.get("body") or "{}"
        if event.get("isBase64Encoded") or not isinstance(raw, str) or len(raw) > 1024:
            raise ValueError()
        body = json.loads(raw)
        if not isinstance(body, dict) or set(body) != {"planId", "targetUserId", "score"}:
            raise ValueError()
        plan_id, target, score = body["planId"], body["targetUserId"], body["score"]
        if not isinstance(plan_id, str) or str(uuid.UUID(plan_id)) != plan_id:
            raise ValueError()
        if not isinstance(target, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", target):
            raise ValueError()
        if type(score) is not int or not 1 <= score <= 5:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        return response(400, "Selecciona un encuentro, participante y nota de 1 a 5")
    if target == user:
        return response(403, "No puedes valorarte a ti mismo")
    key = rating_key(plan_id, user, target)
    previous = table.get_item(Key={"id": key}, ConsistentRead=True).get("Item")
    if previous:
        if previous.get("entityType") == "RATING" and previous.get("score") == score:
            return response(200, data={"saved": True, "alreadySaved": True})
        return response(409, "Ya valoraste a esta persona en este encuentro")
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item") or {}
    if not plan or plan.get("entityType") not in (None, "PLAN") or plan.get("status") == "CANCELLED":
        return response(409, "El encuentro no esta disponible")
    if plan.get("expiresAt", now + 1) > now:
        return response(409, "Podras valorar cuando termine el encuentro")
    attendance_ids = [attendance_key(plan_id, person) for person in (user, target)]
    for person, item_id in zip((user, target), attendance_ids):
        item = table.get_item(Key={"id": item_id}, ConsistentRead=True).get("Item") or {}
        if item.get("entityType") != "ATTENDANCE" or item.get("userId") != person or item.get("planId") != plan_id:
            return response(403, "Ambas personas necesitan asistencia acreditada en este encuentro")
    def s(value):
        return {"S": value}
    def n(value):
        return {"N": str(value)}
    operations = []
    for person, item_id in zip((user, target), attendance_ids):
        operations.append({"ConditionCheck": {"TableName": table.name, "Key": {"id": s(item_id)},
            "ConditionExpression": "entityType = :type AND userId = :user AND planId = :plan",
            "ExpressionAttributeValues": {":type": s("ATTENDANCE"), ":user": s(person), ":plan": s(plan_id)}}})
    operations.append({"ConditionCheck": {"TableName": table.name, "Key": {"id": s(plan_id)},
        "ConditionExpression": "revision = :revision AND expiresAt <= :now AND (attribute_not_exists(#status) OR #status <> :cancelled)",
        "ExpressionAttributeNames": {"#status": "status"}, "ExpressionAttributeValues": {
            ":revision": n(plan.get("revision", -1)), ":now": n(now), ":cancelled": s("CANCELLED")}}})
    operations.append({"Put": {"TableName": table.name, "ConditionExpression": "attribute_not_exists(id)",
        "Item": {"id": s(key), "entityType": s("RATING"), "planId": s(plan_id), "authorUserId": s(user),
                 "targetUserId": s(target), "score": n(score), "createdAt": n(now)}}})
    operations.append({"Update": {"TableName": table.name, "Key": {"id": s("perfil#" + target)},
        "ConditionExpression": "attribute_not_exists(id) OR (entityType = :type AND userId = :user)",
        "UpdateExpression": "SET entityType = :type, userId = :user ADD ratingCount :one, ratingSum :score, revision :one",
        "ExpressionAttributeValues": {":type": s("PROFILE"), ":user": s(target), ":one": n(1), ":score": n(score)}}})
    try:
        client.transact_write_items(TransactItems=operations)
    except Exception as error:
        # El mismo voto se puede reintentar tras perder una respuesta sin sumar dos veces.
        previous = table.get_item(Key={"id": key}, ConsistentRead=True).get("Item") or {}
        if previous.get("entityType") == "RATING" and previous.get("score") == score:
            return response(200, data={"saved": True, "alreadySaved": True})
        if getattr(error, "response", {}).get("Error", {}).get("Code") == "TransactionCanceledException":
            return response(409, "Ya existe la valoracion o cambiaron los datos. Actualiza la lista")
        return response(503, "No pudimos confirmar el guardado. Reintenta la misma nota")
    return response(200, data={"saved": True, "alreadySaved": False})
