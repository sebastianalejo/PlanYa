"""Asistencia, emision y canje atomicos. No crea operadores ni ofertas."""

import hashlib
import json
import re
import uuid


def _response(status, message=None, data=None):
    body = {"success": status == 200}
    body.update({"data": data} if data is not None else {"error": message})
    return {"statusCode": status, "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
            "body": json.dumps(body)}


def _string(value):
    return {"S": value}


def _valid_operator(operator):
    merchants = operator.get("merchantIds")
    return (operator.get("entityType") == "BENEFIT_OPERATOR" and operator.get("active") is True
            and isinstance(merchants, (list, set)) and 1 <= len(merchants) <= 32
            and all(isinstance(value, str) and value for value in merchants))


def confirm_attendance(table, event, now, transaction_client):
    """Piloto: una asistencia y un cupon por participante/plan, solo operador."""
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    operator_id = claims.get("sub")
    if not operator_id or claims.get("token_use") != "id":
        return _response(401, "Inicia sesion para continuar")
    operator_key = "operador#" + operator_id
    operator = table.get_item(Key={"id": operator_key}, ConsistentRead=True).get("Item", {})
    if not _valid_operator(operator):
        return _response(403, "Esta cuenta no puede confirmar asistencias")
    try:
        raw = event.get("body") or "{}"
        if event.get("isBase64Encoded") or not isinstance(raw, str) or len(raw) > 2048:
            raise ValueError()
        body = json.loads(raw)
        if not isinstance(body, dict) or set(body) != {"planId", "userId", "offerId", "requestId"}:
            raise ValueError()
        for field in ("planId", "requestId"):
            if not isinstance(body[field], str) or str(uuid.UUID(body[field])) != body[field]:
                raise ValueError()
        for field in ("userId", "offerId"):
            if not isinstance(body[field], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", body[field]):
                raise ValueError()
    except (ValueError, TypeError, AttributeError):
        return _response(400, "Datos de asistencia no validos")
    plan_id, user, offer_id, request_id = (body[key] for key in ("planId", "userId", "offerId", "requestId"))
    if user == operator_id:
        return _response(403, "No puedes confirmar tu propia asistencia")
    fingerprint = hashlib.sha256(json.dumps([plan_id, user, offer_id]).encode()).hexdigest()
    request_key = "emision#" + str(uuid.uuid5(uuid.NAMESPACE_URL, operator_id + ":" + request_id))

    def replay():
        record = table.get_item(Key={"id": request_key}, ConsistentRead=True).get("Item")
        if record is None:
            return None
        if record.get("entityType") != "BENEFIT_ISSUANCE" or record.get("userId") != operator_id or record.get("fingerprint") != fingerprint:
            return _response(409, "No reutilices esta solicitud para otra asistencia")
        if record.get("merchantId") not in operator.get("merchantIds", []):
            return _response(403, "Esta cuenta no esta autorizada para este local")
        return _response(200, data={"benefitId": record["benefitId"], "planId": plan_id,
                                   "attendanceConfirmed": True, "requestId": request_id})

    previous = replay()
    if previous is not None:
        return previous
    offer_key = "oferta#" + offer_id
    offer = table.get_item(Key={"id": offer_key}, ConsistentRead=True).get("Item", {})
    if offer.get("entityType") != "BENEFIT_OFFER":
        return _response(404, "Oferta no encontrada")
    merchant = offer.get("merchantId")
    if not isinstance(merchant, str) or not merchant or merchant not in operator.get("merchantIds", []):
        return _response(403, "Esta cuenta no esta autorizada para este local")
    try:
        start, end, duration, revision = (int(offer[field]) for field in ("validFrom", "validUntil", "couponDurationSeconds", "revision"))
        if not 60 <= duration <= 30 * 86400 or revision < 1:
            raise ValueError()
        for field in ("merchantName", "title", "discount", "conditions", "category"):
            if not isinstance(offer.get(field), str) or not offer[field].strip() or len(offer[field]) > 2000:
                raise ValueError()
        if offer["category"] not in {"CAFE", "ALMUERZO", "CAMINATA", "FIESTA", "DEPORTES"}:
            raise ValueError()
    except (KeyError, TypeError, ValueError, OverflowError):
        return _response(409, "La oferta necesita configuracion administrativa")
    if offer.get("active") is not True or not start <= now < end:
        return _response(410, "La oferta no esta vigente")
    # Una direccion parecida no acredita que un plan pertenezca a un local.
    if not isinstance(offer.get("planIds"), (list, set)) or len(offer["planIds"]) > 32 or plan_id not in offer["planIds"]:
        return _response(403, "El plan no esta autorizado para esta oferta")
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item", {})
    if not plan or plan.get("entityType") not in (None, "PLAN"):
        return _response(404, "Plan no encontrado")
    if plan.get("status") == "CANCELLED":
        return _response(409, "El plan esta cancelado")
    if user != plan.get("ownerId") and user not in (plan.get("attendeeIds") or []):
        return _response(403, "La persona no participa en este plan")
    try:
        plan_revision = int(plan["revision"])
        starts, expires = int(plan["startsAt"]), int(plan["expiresAt"])
    except (KeyError, TypeError, ValueError, OverflowError):
        return _response(409, "El plan necesita actualizar sus datos")
    # Ventana piloto, no acreditacion automatica: inicio hasta 2 h tras expiracion.
    if not starts <= now < expires + 7200:
        return _response(410, "Fuera del horario de confirmacion de asistencia")

    attendance_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "planya-attendance:" + plan_id + ":" + user))
    coupon_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "planya-attendance-coupon:" + plan_id + ":" + user))
    attendance = {"id": "asistencia#" + attendance_id, "entityType": "ATTENDANCE",
                  "userId": user, "planId": plan_id, "merchantId": merchant, "offerId": offer_id,
                  "verifiedBy": operator_id, "confirmedAt": now}
    coupon = {"id": "beneficio#" + coupon_id, "entityType": "BENEFIT", "userId": user,
              "merchantId": merchant, "offerId": offer_id, "planId": plan_id,
              "status": "DISPONIBLE", "validUntil": min(now + duration, end), "issuedAt": now}
    coupon.update({field: offer[field] for field in ("merchantName", "title", "discount", "conditions", "category")})
    if isinstance(offer.get("limits"), str):
        coupon["limits"] = offer["limits"]
    receipt = {"id": request_key, "entityType": "BENEFIT_ISSUANCE", "userId": operator_id,
               "merchantId": merchant, "benefitId": coupon_id, "fingerprint": fingerprint,
               "confirmedAt": now}

    def put(item):
        return {"Put": {"TableName": table.name, "Item": {key: {"N": str(value)} if isinstance(value, int) else _string(value)
                        for key, value in item.items()}, "ConditionExpression": "attribute_not_exists(id)"}}

    operations = [
        {"ConditionCheck": {"TableName": table.name, "Key": {"id": _string(operator_key)},
            "ConditionExpression": "entityType = :type AND active = :active AND contains(merchantIds, :merchant)",
            "ExpressionAttributeValues": {":type": _string("BENEFIT_OPERATOR"), ":active": {"BOOL": True}, ":merchant": _string(merchant)}}},
        {"ConditionCheck": {"TableName": table.name, "Key": {"id": _string(offer_key)},
            "ConditionExpression": "entityType = :type AND active = :active AND revision = :revision AND merchantId = :merchant AND contains(planIds, :plan) AND validFrom <= :now AND validUntil > :now",
            "ExpressionAttributeValues": {":type": _string("BENEFIT_OFFER"), ":active": {"BOOL": True},
                ":revision": {"N": str(revision)}, ":merchant": _string(merchant), ":plan": _string(plan_id), ":now": {"N": str(now)}}}},
        {"ConditionCheck": {"TableName": table.name, "Key": {"id": _string(plan_id)},
            "ConditionExpression": "(attribute_not_exists(entityType) OR entityType = :type) AND revision = :revision AND (attribute_not_exists(#status) OR #status <> :cancelled) AND (ownerId = :user OR contains(attendeeIds, :user)) AND startsAt <= :now AND expiresAt > :cutoff",
            "ExpressionAttributeNames": {"#status": "status"},
            "ExpressionAttributeValues": {":type": _string("PLAN"), ":revision": {"N": str(plan_revision)},
                ":cancelled": _string("CANCELLED"), ":user": _string(user), ":now": {"N": str(now)}, ":cutoff": {"N": str(now - 7200)}}}},
        put(attendance), put(coupon), put(receipt),
    ]
    try:
        transaction_client.transact_write_items(TransactItems=operations)
    except Exception as error:
        if getattr(error, "response", {}).get("Error", {}).get("Code") != "TransactionCanceledException":
            return _response(503, "Asistencia sin confirmar. Reintenta con la misma solicitud")
        previous = replay()
        if previous is not None:
            return previous
        return _response(409, "La asistencia ya fue confirmada o cambiaron el plan, la oferta o los permisos")
    return _response(200, data={"benefitId": coupon_id, "planId": plan_id,
                               "attendanceConfirmed": True, "requestId": request_id})


def redeem(table, event, now, transaction_client):
    """transaction_client es un cliente boto3 de bajo nivel, no Table.meta.client."""
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return _response(401, "Inicia sesion para continuar")
    # Este registro solo lo provisiona administracion. PUT /perfil no lo modifica.
    operator_key = "operador#" + user
    operator = table.get_item(Key={"id": operator_key}, ConsistentRead=True).get("Item", {})
    if not _valid_operator(operator):
        return _response(403, "Esta cuenta no esta autorizada para canjear")
    try:
        raw = event.get("body") or "{}"
        if event.get("isBase64Encoded") or not isinstance(raw, str) or len(raw) > 1024:
            raise ValueError()
        body = json.loads(raw)
        if not isinstance(body, dict) or set(body) != {"code", "requestId"}:
            raise ValueError()
        code, request_id = body["code"], body["requestId"]
        if not isinstance(code, str) or not re.fullmatch(r"(?:[A-F0-9]{12}|[A-F0-9]{32})", code):
            raise ValueError()
        if not isinstance(request_id, str) or str(uuid.UUID(request_id)) != request_id:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        return _response(400, "Codigo o identificador de solicitud no valido")

    fingerprint = hashlib.sha256(code.encode("ascii")).hexdigest()
    request_key = "canje#" + str(uuid.uuid5(uuid.NAMESPACE_URL, user + ":" + request_id))

    def previous_result():
        previous = table.get_item(Key={"id": request_key}, ConsistentRead=True).get("Item")
        if previous is None:
            return None
        if previous.get("entityType") != "BENEFIT_REDEMPTION" or previous.get("userId") != user:
            return _response(409, "Solicitud no disponible")
        if previous.get("fingerprint") != fingerprint:
            return _response(409, "No reutilices esta solicitud para otro codigo")
        if previous.get("merchantId") not in operator.get("merchantIds", []):
            return _response(403, "Esta cuenta no esta autorizada para este local")
        return _response(200, data={"benefitId": previous["benefitId"],
                                   "status": "USADO", "usedAt": int(previous["usedAt"]),
                                   "requestId": request_id})

    previous = previous_result()
    if previous is not None:
        return previous
    code_key = "codigo#" + code
    token = table.get_item(Key={"id": code_key}, ConsistentRead=True).get("Item", {})
    if token.get("entityType") != "BENEFIT_CODE":
        return _response(404, "Codigo no encontrado")
    if int(token.get("expiresAt", 0)) <= now:
        return _response(410, "El codigo vencio; solicita uno nuevo")
    benefit_key = token.get("benefitId")
    if not isinstance(benefit_key, str) or not benefit_key.startswith("beneficio#"):
        return _response(404, "Beneficio no encontrado")
    benefit = table.get_item(Key={"id": benefit_key}, ConsistentRead=True).get("Item", {})
    if benefit.get("entityType") != "BENEFIT" or benefit.get("userId") != token.get("userId"):
        return _response(404, "Beneficio no encontrado")
    merchant = benefit.get("merchantId")
    if not isinstance(merchant, str) or not merchant or merchant not in operator.get("merchantIds", []):
        return _response(403, "Esta cuenta no esta autorizada para este local")
    if benefit.get("userId") == user:
        return _response(403, "No puedes canjear tu propio beneficio")
    if benefit.get("status") == "VENCIDO" or int(benefit.get("validUntil", 0)) <= now:
        return _response(410, "El beneficio vencio")
    if benefit.get("status") != "DISPONIBLE":
        return _response(409, "El beneficio ya fue usado o no esta disponible")

    raw_id = benefit_key.removeprefix("beneficio#")
    values = {":type": _string("BENEFIT"), ":available": _string("DISPONIBLE"),
              ":used": _string("USADO"), ":merchant": _string(merchant),
              ":owner": _string(benefit["userId"]), ":now": {"N": str(now)},
              ":operator": _string(user), ":request": _string(request_key)}
    # Las cuatro condiciones se verifican juntas. Nunca hay "USADO" sin recibo.
    operations = [
        {"ConditionCheck": {"TableName": table.name, "Key": {"id": _string(operator_key)},
            "ConditionExpression": "entityType = :type AND active = :active AND contains(merchantIds, :merchant)",
            "ExpressionAttributeValues": {":type": _string("BENEFIT_OPERATOR"),
                ":active": {"BOOL": True}, ":merchant": _string(merchant)}}},
        {"ConditionCheck": {"TableName": table.name, "Key": {"id": _string(code_key)},
            "ConditionExpression": "entityType = :type AND benefitId = :benefit AND userId = :owner AND expiresAt > :now",
            "ExpressionAttributeValues": {":type": _string("BENEFIT_CODE"),
                ":benefit": _string(benefit_key), ":owner": _string(benefit["userId"]),
                ":now": {"N": str(now)}}}},
        {"Update": {"TableName": table.name, "Key": {"id": _string(benefit_key)},
            "ConditionExpression": "entityType = :type AND #status = :available AND merchantId = :merchant AND userId = :owner AND validUntil > :now",
            "UpdateExpression": "SET #status = :used, usedAt = :now, usedBy = :operator, redemptionId = :request",
            "ExpressionAttributeNames": {"#status": "status"}, "ExpressionAttributeValues": values}},
        {"Put": {"TableName": table.name,
            "Item": {"id": _string(request_key), "entityType": _string("BENEFIT_REDEMPTION"),
                "userId": _string(user), "merchantId": _string(merchant),
                "benefitId": _string(raw_id), "fingerprint": _string(fingerprint),
                "usedAt": {"N": str(now)}},
            "ConditionExpression": "attribute_not_exists(id)"}},
    ]
    try:
        transaction_client.transact_write_items(TransactItems=operations)
    except Exception as error:
        reason = getattr(error, "response", {}).get("Error", {}).get("Code")
        if reason != "TransactionCanceledException":
            # No declarar fallo definitivo: una respuesta de red perdida puede
            # ocurrir despues del commit. El mismo requestId recupera el recibo.
            return _response(503, "Canje sin confirmar. Reintenta con la misma solicitud")
        previous = previous_result()
        if previous is not None:
            return previous
        return _response(409, "El beneficio o los permisos cambiaron. Actualiza antes de reintentar")
    return _response(200, data={"benefitId": raw_id, "status": "USADO", "usedAt": now,
                               "requestId": request_id})
