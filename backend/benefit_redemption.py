"""Canje autorizado y atomico. No crea operadores ni concede beneficios."""

import hashlib
import json
import re
import uuid


def _response(status, message=None, data=None):
    body = {"success": status == 200}
    body.update({"data": data} if data is not None else {"error": message})
    return {"statusCode": status, "headers": {"Content-Type": "application/json"},
            "body": json.dumps(body)}


def _string(value):
    return {"S": value}


def redeem(table, event, now, transaction_client):
    """transaction_client es un cliente boto3 de bajo nivel, no Table.meta.client."""
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    user = claims.get("sub")
    if not user or claims.get("token_use") != "id":
        return _response(401, "Inicia sesion para continuar")
    # Este registro solo lo provisiona administracion. PUT /perfil no lo modifica.
    operator_key = "operador#" + user
    operator = table.get_item(Key={"id": operator_key}, ConsistentRead=True).get("Item", {})
    if operator.get("entityType") != "BENEFIT_OPERATOR" or operator.get("active") is not True:
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
