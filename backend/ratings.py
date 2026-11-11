"""Valoraciones de encuentros terminados: asistencia acreditada y voto unico."""
import json
import re
import uuid


def response(status, message=None, data=None):
    return {"statusCode": status, "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
            "body": json.dumps({"success": status == 200, **({"data": data} if data is not None else {"error": message})})}


def attendance_key(plan, user):
    return "asistencia#" + str(uuid.uuid5(uuid.NAMESPACE_URL, "planya-attendance:" + plan + ":" + user))


def rating_key(plan, author, target):
    return "valoracion#" + str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps([plan, author, target])))


def eligible_plan(plan, now):
    return (plan.get("entityType") in (None, "PLAN") and plan.get("status") != "CANCELLED"
            and isinstance(plan.get("expiresAt"), (int, float)) and plan["expiresAt"] <= now)


def handle(table, event, now, client):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return response(401, "Inicia sesion para valorar")
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
