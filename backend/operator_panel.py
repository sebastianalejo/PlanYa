"""Lecturas minimas del panel de operadores, siempre autorizadas en servidor."""

import re
import uuid

from benefit_redemption import _response


def read(table, cursors, event, now):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return _response(401, "Inicia sesion para continuar")
    operator = table.get_item(Key={"id": "operador#" + user}, ConsistentRead=True).get("Item", {})
    merchants = operator.get("merchantIds", [])
    if (operator.get("entityType") != "BENEFIT_OPERATOR" or operator.get("active") is not True
            or not isinstance(merchants, (list, set)) or not 1 <= len(merchants) <= 32
            or any(not isinstance(item, str) or not item for item in merchants)):
        return _response(403, "Acceso exclusivo para operadores autorizados")
    if event.get("routeKey") == "GET /operador/ofertas":
        query = event.get("queryStringParameters") or {}
        values = {":type": "BENEFIT_OFFER", ":active": True, ":now": now}
        merchant_filters = []
        for index, merchant in enumerate(sorted(merchants)):
            key = ":m" + str(index)
            values[key] = merchant
            merchant_filters.append("merchantId = " + key)
        options = {"Limit": 20, "ConsistentRead": True,
            "FilterExpression": "entityType = :type AND active = :active AND validFrom <= :now AND validUntil > :now AND (" + " OR ".join(merchant_filters) + ")",
            "ExpressionAttributeValues": values}
        cursor = query.get("nextKey")
        if cursor:
            try:
                if not isinstance(cursor, str) or str(uuid.UUID(cursor)) != cursor:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                return _response(400, "Continuacion no valida; actualiza la lista")
            saved = cursors.get_item(Key={"planId": "operator#" + cursor, "sk": "cursor"}, ConsistentRead=True).get("Item", {})
            if saved.get("entityType") != "OPERATOR_CURSOR" or saved.get("userId") != user or saved.get("merchants") != sorted(merchants):
                return _response(400, "Continuacion no valida; actualiza la lista")
            if int(saved.get("expiresAt", 0)) <= now:
                return _response(409, "La lista vencio; actualiza para empezar de nuevo")
            options["ExclusiveStartKey"] = saved["key"]
        result = table.scan(**options)
        items = []
        for item in result.get("Items", []):
            # Defensa adicional: no devolver registros auxiliares aunque una
            # consulta mal configurada ignore el filtro de DynamoDB.
            if (item.get("entityType") != "BENEFIT_OFFER" or item.get("active") is not True
                    or item.get("merchantId") not in merchants
                    or not int(item.get("validFrom", now + 1)) <= now < int(item.get("validUntil", 0))):
                continue
            plans = item.get("planIds", [])
            if not isinstance(plans, (set, list)) or len(plans) > 32:
                continue
            items.append({"id": item["id"].removeprefix("oferta#"),
                "title": item.get("title", ""), "merchantName": item.get("merchantName", ""),
                "discount": item.get("discount", ""), "conditions": item.get("conditions", ""),
                "planIds": sorted(plans)})
        next_key = None
        if result.get("LastEvaluatedKey"):
            next_key = str(uuid.uuid4())
            cursors.put_item(Item={"planId": "operator#" + next_key, "sk": "cursor",
                "entityType": "OPERATOR_CURSOR", "userId": user, "merchants": sorted(merchants),
                "key": result["LastEvaluatedKey"], "expiresAt": now + 900})
        return _response(200, data={"userId": user, "items": items, "nextKey": next_key})

    path = event.get("pathParameters") or {}
    offer_id, plan_id = path.get("id"), path.get("planId")
    try:
        if not isinstance(offer_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", offer_id):
            raise ValueError()
        if not isinstance(plan_id, str) or str(uuid.UUID(plan_id)) != plan_id:
            raise ValueError()
    except (TypeError, ValueError, AttributeError):
        return _response(400, "Oferta o plan no valido")
    offer = table.get_item(Key={"id": "oferta#" + offer_id}, ConsistentRead=True).get("Item", {})
    if (offer.get("entityType") != "BENEFIT_OFFER" or offer.get("merchantId") not in merchants
            or plan_id not in offer.get("planIds", [])):
        return _response(403, "Oferta o plan no autorizado para esta cuenta")
    if offer.get("active") is not True or not int(offer.get("validFrom", now + 1)) <= now < int(offer.get("validUntil", 0)):
        return _response(410, "La oferta no esta vigente")
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item", {})
    if not plan or plan.get("entityType") not in (None, "PLAN"):
        return _response(404, "Plan no encontrado")
    if plan.get("status") == "CANCELLED":
        return _response(409, "El plan esta cancelado")
    if not int(plan.get("startsAt", now + 1)) <= now < int(plan.get("expiresAt", 0)) + 7200:
        return _response(410, "Fuera del horario de confirmacion")
    ids, names = plan.get("attendeeIds", []), plan.get("attendees", [])
    members = []
    seen = {user}
    for index, member in enumerate([plan.get("ownerId")] + list(ids)):
        if not isinstance(member, str) or member in seen:
            continue
        seen.add(member)
        name = plan.get("authorName") if index == 0 else names[index - 1] if index - 1 < len(names) else None
        members.append({"userId": member, "name": name or "Participante", "isHost": member == plan.get("ownerId")})
    return _response(200, data={"userId": user, "offerId": offer_id, "planId": plan_id,
        "title": plan.get("title", "Plan"), "members": members})
