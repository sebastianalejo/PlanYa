"""PlanYaApi: crear planes desde la consola y publicarlos por GET /planes.

Runtime: Python 3.13. Variable de entorno: TABLE_NAME=PlanYaPlanes.
La integración HTTP utiliza API Gateway HTTP API, payload 2.0.
"""

import json
import os
import uuid
import math
import base64
import re
import logging
from datetime import datetime, timezone
from decimal import Decimal

import boto3

dynamodb = boto3.resource("dynamodb")
CATEGORIES = {"CAFE", "ALMUERZO", "CAMINATA", "FIESTA", "DEPORTES"}
MAX_PHOTO_BYTES = 1_500_000
PHOTO_PREFIX = "fotos/"
MAX_PHOTOS = 4
PROFILE_ENTITY = "PROFILE"
ATTENDANCE_ENTITY = "ATTENDANCE"
RATING_ENTITY = "RATING"
AUXILIARY_ENTITIES = {PROFILE_ENTITY, ATTENDANCE_ENTITY, RATING_ENTITY}


def s3_client():
    from botocore.config import Config
    return boto3.client("s3", region_name=os.environ.get("AWS_REGION", "us-east-1"),
                        config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"},
                                      connect_timeout=3, read_timeout=5, retries={"max_attempts": 1}))


def photo_bucket():
    bucket = os.environ.get("FOTOS_BUCKET", "")
    if not bucket:
        raise ValueError("Falta configurar FOTOS_BUCKET en Lambda")
    return bucket


def photo_owner(user):
    # No interpolar identificadores sin validar en una ruta de objetos.
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "planya-user:" + user))


def plan_request_id(owner, request_id):
    uuid.UUID(request_id)
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"planya:{owner}:{request_id}"))


def valid_photo_key(key, owner=None, plan_id=None):
    if not isinstance(key, str) or not re.fullmatch(r"fotos/[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\.jpg", key):
        return False
    parts = key.split("/")
    return (owner is None or parts[1] == photo_owner(owner)) and (plan_id is None or parts[2] == plan_id)


def valid_avatar_key(key, owner=None, pending=False):
    value = key.removeprefix("fotos/") if isinstance(key, str) and pending else key
    return isinstance(value, str) and re.fullmatch(r"perfiles/[a-f0-9-]{36}/[a-f0-9-]{36}\.jpg", value) is not None and (owner is None or value.split("/")[1] == photo_owner(owner))


def plan_photos(plan):
    if "photos" in plan:
        return plan["photos"]
    if plan.get("imageKey"):
        return [{"imageKey": plan["imageKey"]}]
    return [{"imageUrl": plan["imageUrl"]}] if plan.get("imageUrl") else []


def public_plan(plan):
    result = dict(plan)
    result.pop("imageUrl", None)
    remaining = int(plan.get("expiresAt", 0)) - int(datetime.now(timezone.utc).timestamp())
    visible = remaining > 0 and plan.get("status") != "CANCELLED"
    photos = []
    for photo in plan_photos(plan):
        item = dict(photo)
        item.pop("imageUrl", None)
        key = photo.get("imageKey")
        if visible and key and valid_photo_key(key, plan.get("ownerId"), plan.get("id")):
            try:
                item["imageUrl"] = s3_client().generate_presigned_url("get_object",
                    Params={"Bucket": photo_bucket(), "Key": key, "ResponseCacheControl": "private, no-store"},
                    ExpiresIn=min(300, remaining))
            except Exception:
                logging.warning("No se pudo generar acceso temporal a una foto")
        elif visible and not key and photo.get("imageUrl", "").startswith("https://"):
            item["imageUrl"] = photo["imageUrl"]
        photos.append(item)
    # No añadir un campo vacío a los registros antiguos sin foto.
    if photos or "photos" in plan:
        result["photos"] = photos
    if photos and photos[0].get("imageUrl"):
        result["imageUrl"] = photos[0]["imageUrl"]
        if photos[0].get("imageKey"):
            result["imageUrlExpiresAt"] = int(datetime.now(timezone.utc).timestamp()) + min(300, remaining)
    return result


def delete_photo(key):
    if key and (valid_photo_key(key) or valid_avatar_key(key, pending=True)):
        try:
            s3_client().delete_object(Bucket=photo_bucket(), Key=key)
        except Exception:
            # No convertir un guardado exitoso de DynamoDB en un falso fallo.
            # La regla Lifecycle del prefijo fotos/ queda como respaldo.
            logging.warning("Limpieza de foto pendiente; revisar permisos o Lifecycle")


def check_photo(key, owner, plan_id, fresh=True, avatar=False):
    if not (valid_avatar_key(key, owner, pending=True) if avatar else valid_photo_key(key, owner, plan_id)):
        raise ValueError("La foto no pertenece a este usuario y plan")
    try:
        s3 = s3_client()
        info = s3.head_object(Bucket=photo_bucket(), Key=key)
        if not 1 <= info["ContentLength"] <= MAX_PHOTO_BYTES or info.get("ContentType") != "image/jpeg":
            raise ValueError("La foto debe ser JPEG y pesar como maximo 1.5 MB")
        if fresh:
            uploaded = info.get("LastModified")
            if not isinstance(uploaded, datetime) or not -60 <= (datetime.now(timezone.utc) - uploaded).total_seconds() <= 1800:
                raise ValueError("La subida de foto vencio. Vuelve a subirla antes de publicar")
        header = s3.get_object(Bucket=photo_bucket(), Key=key, Range="bytes=0-2")["Body"]
        try:
            if header.read(3) != b"\xff\xd8\xff":
                raise ValueError("El archivo no tiene una cabecera JPEG valida")
        finally:
            header.close()
    except ValueError:
        raise
    except Exception as error:
        raise ValueError("No se pudo comprobar la foto subida. Reintenta o publica sin foto") from error


def read_body(event):
    raw = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw, validate=True).decode("utf-8")
    if not isinstance(raw, str) or len(raw) > 12_000:
        raise ValueError("Solicitud demasiado grande")
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Solicitud no valida")
    return data


def upload_photo(table, event):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return http_response(401, {"success": False, "error": "Inicia sesion para subir una foto"})
    try:
        data = read_body(event)
        size = data.get("size")
        if type(size) is not int or not 1 <= size <= MAX_PHOTO_BYTES or data.get("contentType") != "image/jpeg":
            raise ValueError("La foto debe ser JPEG y pesar como maximo 1.5 MB")
        plan_id = data.get("planId")
        if plan_id is not None:
            if not isinstance(plan_id, str):
                raise ValueError("Identificador no valido")
            uuid.UUID(plan_id)
            plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
            if not plan or plan.get("ownerId") != claims["sub"]:
                return http_response(403, {"success": False, "error": "Solo el anfitrion puede cambiar la foto"})
            if plan.get("status") == "CANCELLED" or plan.get("expiresAt", 0) <= int(datetime.now(timezone.utc).timestamp()):
                raise ValueError("El plan ya termino")
        else:
            request_id = data.get("requestId")
            if not isinstance(request_id, str):
                raise ValueError("Falta el identificador del borrador")
            plan_id = plan_request_id(claims["sub"], request_id)
        key = f"fotos/{photo_owner(claims['sub'])}/{plan_id}/{uuid.uuid4()}.jpg"
        fields = {"Content-Type": "image/jpeg", "Cache-Control": "private, no-store", "x-amz-server-side-encryption": "AES256"}
        signed = s3_client().generate_presigned_post(Bucket=photo_bucket(), Key=key, Fields=fields,
            Conditions=[["content-length-range", 1, MAX_PHOTO_BYTES]] + [{k: v} for k, v in fields.items()], ExpiresIn=300)
        return http_response(200, {"success": True, "data": {"uploadUrl": signed["url"], "fields": signed["fields"],
            "imageKey": key, "maxBytes": MAX_PHOTO_BYTES}})
    except (ValueError, TypeError, AttributeError) as error:
        return http_response(400, {"success": False, "error": str(error)})


def json_number(value):
    # DynamoDB devuelve números Decimal; JSON necesita int o float.
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    raise TypeError(f"Tipo no serializable: {type(value).__name__}")


def http_response(status, payload):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"},
        "body": json.dumps(payload, ensure_ascii=False, default=json_number),
    }


def profile_id(user):
    return "perfil#" + user


def is_plan_item(item):
    # Los perfiles, asistencias y valoraciones comparten tabla, pero no son planes.
    return isinstance(item, dict) and item.get("entityType") not in AUXILIARY_ENTITIES


def public_verification(profile):
    status = profile.get("verificationStatus")
    if status not in ("PENDING", "VERIFIED"):
        status = "NONE"
    result = {"status": status}
    if status == "VERIFIED":
        method = profile.get("verificationMethod")
        if isinstance(method, str) and method.strip():
            result["method"] = method.strip()
        verified_at = profile.get("verifiedAt")
        if isinstance(verified_at, str) and verified_at:
            result["verifiedAt"] = verified_at
    return result


def public_profile(profile, claims):
    result = {"userId": claims["sub"], "name": profile.get("name") or str(claims.get("name") or "Usuario PlanYa")[:80],
              "email": str(claims.get("email") or ""), "revision": profile.get("revision", 0),
              "interests": profile.get("interests", []), "saved": profile.get("entityType") == "PROFILE",
              "verification": public_verification(profile)}
    key = profile.get("avatarKey")
    if key and valid_avatar_key(key, claims["sub"]):
        result["avatarKey"] = key
        try:
            result["avatarUrl"] = s3_client().generate_presigned_url("get_object",
                Params={"Bucket": photo_bucket(), "Key": key, "ResponseCacheControl": "private, no-store"}, ExpiresIn=300)
        except Exception:
            logging.warning("No se pudo generar acceso temporal a la foto de perfil")
    return result


def profile_request(table, event):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return http_response(401, {"success": False, "error": "Inicia sesion para ver tu perfil"})
    copied_key = None
    try:
        route = event.get("routeKey")
        if route == "POST /perfil/foto":
            data = read_body(event)
            if type(data.get("size")) is not int or not 1 <= data["size"] <= MAX_PHOTO_BYTES or data.get("contentType") != "image/jpeg":
                raise ValueError("La foto debe ser JPEG y pesar como maximo 1.5 MB")
            key = f"fotos/perfiles/{photo_owner(claims['sub'])}/{uuid.uuid4()}.jpg"
            fields = {"Content-Type": "image/jpeg", "Cache-Control": "private, no-store", "x-amz-server-side-encryption": "AES256"}
            signed = s3_client().generate_presigned_post(Bucket=photo_bucket(), Key=key, Fields=fields,
                Conditions=[["content-length-range", 1, MAX_PHOTO_BYTES]] + [{k: v} for k, v in fields.items()], ExpiresIn=300)
            return http_response(200, {"success": True, "data": {"uploadUrl": signed["url"], "fields": signed["fields"], "imageKey": key, "maxBytes": MAX_PHOTO_BYTES}})
        profile = table.get_item(Key={"id": profile_id(claims["sub"])}, ConsistentRead=True).get("Item") or {}
        if route == "GET /perfil":
            return http_response(200, {"success": True, "data": public_profile(profile, claims)})
        if route != "PUT /perfil":
            return http_response(404, {"success": False, "error": "Ruta no disponible"})
        data = read_body(event)
        privileged = {"verified", "verification", "verificationStatus", "verificationMethod",
                      "verifiedAt", "verifiedBy", "ratingCount", "ratingSum", "activity",
                      "organized", "joined", "attended", "average"}
        if any(field in data for field in privileged):
            raise ValueError("No puedes modificar datos de confianza desde el perfil")
        name = data.get("name")
        revision = data.get("expectedRevision")
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 for c in name):
            raise ValueError("Escribe un nombre de 1 a 80 caracteres, sin saltos de linea")
        if type(revision) is not int or revision < 0:
            raise ValueError("La version del perfil no es valida")
        if revision != profile.get("revision", 0):
            return http_response(409, {"success": False, "error": "Tu perfil cambio en otro dispositivo. Recarga antes de guardar"})
        interests = data.get("interests", profile.get("interests", []))
        if not isinstance(interests, list) or len(interests) > len(CATEGORIES) or any(not isinstance(i, str) or i not in CATEGORIES for i in interests) or len(set(interests)) != len(interests):
            raise ValueError("Intereses no validos")
        if "removeAvatar" in data and type(data["removeAvatar"]) is not bool:
            raise ValueError("Solicitud de foto no valida")
        old_key = profile.get("avatarKey")
        key = None if data.get("removeAvatar") else data.get("avatarKey", old_key)
        pending_key = None
        if key is not None:
            if not isinstance(key, str):
                raise ValueError("Foto de perfil no valida")
            if key != old_key and not key.startswith("fotos/perfiles/"):
                raise ValueError("Vuelve a subir la foto de perfil antes de guardar")
            check_photo(key, claims["sub"], None, fresh=key != old_key, avatar=True)
            if key.startswith("fotos/perfiles/"):
                pending_key = key
                copied_key = f"perfiles/{photo_owner(claims['sub'])}/{uuid.uuid4()}.jpg"
                s3_client().copy_object(Bucket=photo_bucket(), Key=copied_key,
                    CopySource={"Bucket": photo_bucket(), "Key": pending_key}, ContentType="image/jpeg",
                    CacheControl="private, no-store", MetadataDirective="REPLACE", ServerSideEncryption="AES256")
                key = copied_key
        item = {"id": profile_id(claims["sub"]), "entityType": "PROFILE", "userId": claims["sub"],
                "name": name.strip(), "interests": interests, "revision": revision + 1,
                "updatedAt": datetime.now(timezone.utc).isoformat()}
        if key:
            item["avatarKey"] = key
        if profile:
            table.put_item(Item=item, ConditionExpression="#revision = :expected AND #user = :user",
                ExpressionAttributeNames={"#revision": "revision", "#user": "userId"},
                ExpressionAttributeValues={":expected": revision, ":user": claims["sub"]})
        else:
            table.put_item(Item=item, ConditionExpression="attribute_not_exists(id)")
        if old_key != key:
            delete_photo(old_key)
        delete_photo(pending_key)
        return http_response(200, {"success": True, "data": public_profile(item, claims)})
    except (ValueError, TypeError, UnicodeDecodeError) as error:
        return http_response(400, {"success": False, "error": str(error)})
    except Exception as error:
        if getattr(error, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException":
            delete_photo(copied_key)
            return http_response(409, {"success": False, "error": "Tu perfil cambio. Recarga antes de guardar"})
        raise


def activity_request(table, event):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return http_response(401, {"success": False, "error": "Inicia sesion para ver tu actividad"})
    user = claims["sub"]
    organized = joined = attended = 0
    options = {"ConsistentRead": True}
    while True:
        result = table.scan(**options)
        for item in result.get("Items", []):
            if not isinstance(item, dict):
                continue
            if item.get("entityType") == ATTENDANCE_ENTITY:
                # Solo una asistencia acreditada por el servidor cuenta; unirse no basta.
                if item.get("userId") == user:
                    attended += 1
                continue
            if not is_plan_item(item):
                continue
            if item.get("ownerId") == user:
                organized += 1
            elif user in (item.get("attendeeIds") or []):
                joined += 1
        last_key = result.get("LastEvaluatedKey")
        if not last_key:
            break
        options["ExclusiveStartKey"] = last_key
    return http_response(200, {"success": True, "data": {
        "userId": user, "organized": organized, "joined": joined, "attended": attended}})


def confidence_request(table, event):
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return http_response(401, {"success": False, "error": "Inicia sesion para ver tu confianza"})
    user = claims["sub"]
    profile = table.get_item(Key={"id": profile_id(user)}, ConsistentRead=True).get("Item") or {}
    try:
        count = int(profile.get("ratingCount", 0) or 0)
        total = float(profile.get("ratingSum", 0) or 0)
    except (TypeError, ValueError):
        count, total = 0, 0.0
    if count <= 0 or total <= 0:
        return http_response(200, {"success": True, "data": {"userId": user, "count": 0, "average": None}})
    average = round(total / count, 2)
    if not 1 <= average <= 5:
        return http_response(200, {"success": True, "data": {"userId": user, "count": 0, "average": None}})
    return http_response(200, {"success": True, "data": {"userId": user, "count": count, "average": average}})


def list_plans(table, next_key=None):
    # Una página por petición; nextKey permite solicitar la siguiente.
    options = {"Limit": 25, "ConsistentRead": True}
    if next_key:
        if not isinstance(next_key, str) or len(next_key) > 128:
            raise ValueError("nextKey no valido")
        options["ExclusiveStartKey"] = {"id": next_key}
    result = table.scan(**options)
    return {
        "success": True,
        "data": {
            "items": [public_plan(item) for item in result.get("Items", []) if is_plan_item(item) and item.get("status") != "CANCELLED"
                      and (item.get("expiresAt") is None or item["expiresAt"] > int(datetime.now(timezone.utc).timestamp()))],
            "nextKey": result.get("LastEvaluatedKey", {}).get("id"),
        },
    }


def get_plan(table, plan_id):
    if not isinstance(plan_id, str) or not 1 <= len(plan_id) <= 128:
        return http_response(400, {"success": False, "error": "Identificador no valido"})
    plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
    if not plan or not is_plan_item(plan) or plan.get("status") == "CANCELLED" or (plan.get("expiresAt") is not None and plan["expiresAt"] <= int(datetime.now(timezone.utc).timestamp())):
        return http_response(404, {"success": False, "error": "Plan no disponible"})
    return http_response(200, {"success": True, "data": public_plan(plan)})


def build_plan(event, owner=None):
    title = event.get("title", "")
    description = event.get("description", "")
    category = event.get("category", "CAFE")
    if not isinstance(title, str) or not 1 <= len(title.strip()) <= 120:
        raise ValueError("El titulo debe tener entre 1 y 120 caracteres")
    if not isinstance(description, str) or len(description) > 1000:
        raise ValueError("La descripcion admite hasta 1000 caracteres")
    if not isinstance(category, str) or category not in CATEGORIES:
        raise ValueError("Categoria no valida")
    duration = event.get("durationMinutes", 60)
    if type(duration) is not int or not 30 <= duration <= 120:
        raise ValueError("La duracion debe ser de 30 a 120 minutos")
    now = datetime.now(timezone.utc)
    starts_in = event.get("startsInMinutes", 0)
    if type(starts_in) is not int or not 0 <= starts_in < duration:
        raise ValueError("El inicio debe ocurrir antes del vencimiento del plan")
    plan = {
        "id": str(uuid.uuid4()),
        "title": title.strip(),
        "description": description.strip(),
        "category": category,
        "createdAt": now.isoformat(),
        "expiresAt": int(now.timestamp()) + duration * 60,
        "attendees": [],
        "startsAt": int(now.timestamp()) + starts_in * 60,
        "revision": 0,
    }
    latitude, longitude = event.get("latitude"), event.get("longitude")
    if latitude is not None or longitude is not None:
        for value, limit in ((latitude, 90), (longitude, 180)):
            if type(value) not in (int, float) or not math.isfinite(value) or not -limit <= value <= limit:
                raise ValueError("Las coordenadas no son validas; envia latitude y longitude")
        plan.update(latitude=Decimal(str(latitude)), longitude=Decimal(str(longitude)))
    for field, limit in (("meetingPlace", 400), ("authorName", 80), ("imageUrl", 2048), ("authorAvatarUrl", 2048)):
        if field in event:
            value = event[field]
            if not isinstance(value, str) or not 1 <= len(value.strip()) <= limit:
                raise ValueError(f"El campo {field} no es valido")
            value = value.strip()
            if field.endswith("Url") and not value.startswith("https://"):
                raise ValueError("Las imagenes deben usar HTTPS")
            plan[field] = value
    if "capacity" in event:
        capacity = event["capacity"]
        if type(capacity) is not int or not 1 <= capacity <= 100:
            raise ValueError("El cupo debe ser de 1 a 100 personas")
        plan["capacity"] = capacity
    if owner is not None:
        if latitude is None or longitude is None or not plan.get("meetingPlace") or not plan.get("capacity"):
            raise ValueError("Completa ubicacion y cupo del plan")
        name = str(owner.get("name") or "Usuario PlanYa")[:80]
        plan.update(ownerId=owner["sub"], authorName=name, attendeeIds=[owner["sub"]], attendees=[name])
        request_id = event.get("requestId")
        if request_id is not None:
            if not isinstance(request_id, str):
                raise ValueError("Identificador de solicitud no valido")
            uuid.UUID(request_id)
            plan["id"] = plan_request_id(owner["sub"], request_id)
        if "photos" in event:
            source = event["photos"]
            if not isinstance(source, list) or len(source) > MAX_PHOTOS:
                raise ValueError("Puedes incluir como maximo 4 fotos")
            validated_photos = []
            seen = set()
            for photo in source:
                if not isinstance(photo, dict):
                    raise ValueError("Foto no valida")
                key, url = photo.get("imageKey"), photo.get("imageUrl")
                if key:
                    target_id = event.get("_editingPlanId", plan["id"])
                    check_photo(key, owner["sub"], target_id, fresh=key not in event.get("_existingPhotoKeys", []))
                    item = {"imageKey": key}
                    identity = key
                elif isinstance(url, str) and 1 <= len(url) <= 2048 and url.startswith("https://"):
                    item = {"imageUrl": url}
                    identity = url
                else:
                    raise ValueError("Foto no valida")
                if identity in seen:
                    raise ValueError("No repitas la misma foto")
                seen.add(identity)
                validated_photos.append(item)
            plan["photos"] = validated_photos
            plan.pop("imageKey", None)
            plan.pop("imageUrl", None)
            if validated_photos:
                plan.update(validated_photos[0])
        elif event.get("imageKey"):
            # El plan editado tiene un ID diferente al del nuevo borrador de edición.
            target_id = event.get("_editingPlanId", plan["id"])
            check_photo(event["imageKey"], owner["sub"], target_id, fresh=event.get("_existingPhotoKey") != event["imageKey"])
            plan["imageKey"] = event["imageKey"]
            plan.pop("imageUrl", None)
    return plan


def create_plan(table, event):
    plan = build_plan(event)
    table.put_item(Item=plan, ConditionExpression="attribute_not_exists(id)")
    return {"mensaje": "Plan guardado correctamente", "plan": json.loads(json.dumps(plan, default=json_number))}


def write_plan(table, event):
    # Solo se confían claims que API Gateway haya verificado con su autorizador JWT.
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    if not claims.get("sub") or claims.get("token_use") != "id":
        return http_response(401, {"success": False, "error": "Inicia sesion para continuar"})
    route = event.get("routeKey")
    if route in ("POST /planes", "POST /planes/{id}/participantes"):
        profile = table.get_item(Key={"id": profile_id(claims["sub"])}, ConsistentRead=True).get("Item")
        if isinstance(profile, dict) and profile.get("entityType") == "PROFILE":
            claims = dict(claims, name=profile["name"])
    try:
        data = read_body(event)
        data.pop("_editingPlanId", None)
        data.pop("_existingPhotoKey", None)
        data.pop("_existingPhotoKeys", None)
        if route == "POST /planes":
            plan = build_plan(data, claims)
            try:
                table.put_item(Item=plan, ConditionExpression="attribute_not_exists(id)")
            except Exception as error:
                if data.get("requestId") and getattr(error, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException":
                    existing = table.get_item(Key={"id": plan["id"]}, ConsistentRead=True).get("Item")
                    if existing and existing.get("ownerId") == claims["sub"]:
                        if existing.get("status") == "CANCELLED" or existing.get("expiresAt", 0) <= int(datetime.now(timezone.utc).timestamp()):
                            return http_response(409, {"success": False, "error": "Este borrador ya fue publicado y termino; crea uno nuevo"})
                        return http_response(200, {"success": True, "data": public_plan(existing)})
                raise
            return http_response(201, {"success": True, "data": public_plan(plan)})
        plan_id = (event.get("pathParameters") or {}).get("id")
        if not isinstance(plan_id, str) or not 1 <= len(plan_id) <= 128:
            raise ValueError("Identificador no valido")
        plan = table.get_item(Key={"id": plan_id}, ConsistentRead=True).get("Item")
        now = int(datetime.now(timezone.utc).timestamp())
        if not plan or plan.get("status") == "CANCELLED" or plan.get("expiresAt", 0) <= now:
            return http_response(404, {"success": False, "error": "Plan no disponible"})
        owner = plan.get("ownerId") == claims["sub"]
        old_photos = {photo["imageKey"] for photo in plan_photos(plan) if photo.get("imageKey")}
        revision = plan.get("revision", 0)
        if route == "PUT /planes/{id}" and "expectedRevision" in data:
            expected = data["expectedRevision"]
            if type(expected) is not int or expected < 0:
                raise ValueError("La version del plan no es valida")
            if expected != revision:
                return http_response(409, {"success": False, "error": "El plan cambio desde que lo abriste. Recarga sus datos"})
        condition = "attribute_exists(#id) AND (attribute_not_exists(#revision) OR #revision = :previous) AND #expires > :now"
        names = {"#id": "id", "#revision": "revision", "#expires": "expiresAt"}
        values = {":previous": revision, ":now": now}
        if route in ("PUT /planes/{id}", "DELETE /planes/{id}"):
            if not owner:
                return http_response(403, {"success": False, "error": "Solo el anfitrion puede gestionar este plan"})
            condition += " AND #owner = :owner"
            names["#owner"] = "ownerId"
            values[":owner"] = claims["sub"]
            if route.startswith("DELETE"):
                plan.update(status="CANCELLED", expiresAt=now)
            else:
                if "photos" not in data and len(plan_photos(plan)) > 1:
                    return http_response(409, {"success": False, "error": "Actualiza la app para editar un plan con varias fotos"})
                data["_editingPlanId"] = plan_id
                data["_existingPhotoKey"] = plan.get("imageKey")
                data["_existingPhotoKeys"] = list(old_photos)
                validated = build_plan(data, claims)
                if validated["capacity"] < len(plan.get("attendees", [])):
                    raise ValueError("El cupo no puede ser menor a los asistentes confirmados")
                for field in ("title", "description", "category", "meetingPlace", "latitude", "longitude", "capacity", "imageUrl", "imageKey", "photos"):
                    if field in validated:
                        plan[field] = validated[field]
                for field in ("imageUrl", "imageKey", "photos"):
                    if field not in validated:
                        plan.pop(field, None)
                # Editar no reinicia la hora de creacion ni extiende la vigencia.
        else:
            ids = list(plan.get("attendeeIds", []))
            attendee_names = list(plan.get("attendees", []))
            if route == "POST /planes/{id}/participantes":
                if claims["sub"] in ids:
                    return http_response(200, {"success": True, "data": public_plan(plan)})
                if len(ids) >= plan.get("capacity", 0):
                    return http_response(409, {"success": False, "error": "El plan ya no tiene cupos"})
                ids.append(claims["sub"])
                attendee_names.append(str(claims.get("name") or "Usuario PlanYa")[:80])
            elif route == "DELETE /planes/{id}/participantes/me":
                if owner:
                    raise ValueError("El anfitrion debe cancelar el plan para salir")
                if claims["sub"] not in ids:
                    return http_response(200, {"success": True, "data": public_plan(plan)})
                index = ids.index(claims["sub"])
                ids.pop(index)
                if index < len(attendee_names):
                    attendee_names.pop(index)
            else:
                return http_response(404, {"success": False, "error": "Ruta no disponible"})
            plan.update(attendeeIds=ids, attendees=attendee_names)
        plan["revision"] = revision + 1
        table.put_item(Item=plan, ConditionExpression=condition, ExpressionAttributeNames=names, ExpressionAttributeValues=values)
        if owner:
            kept = {photo["imageKey"] for photo in plan_photos(plan) if photo.get("imageKey")}
            for key in old_photos if route == "DELETE /planes/{id}" else old_photos - kept:
                delete_photo(key)
        return http_response(200, {"success": True, "data": public_plan(plan)})
    except (ValueError, UnicodeDecodeError, TypeError) as error:
        return http_response(400, {"success": False, "error": str(error)})
    except Exception as error:
        if getattr(error, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException":
            return http_response(409, {"success": False, "error": "El plan cambio. Actualiza y vuelve a intentar"})
        raise


def lambda_handler(event, context):
    table = dynamodb.Table(os.environ["TABLE_NAME"])

    # Las escrituras HTTP requieren claims verificados por el autorizador JWT.
    # Los eventos de consola no se mezclan con parámetros enviados por la API.
    if "requestContext" in event:
        if event.get("routeKey") == "GET /perfil/actividad":
            return activity_request(table, event)
        if event.get("routeKey") == "GET /perfil/confianza":
            return confidence_request(table, event)
        if event.get("routeKey") in {"GET /perfil", "PUT /perfil", "POST /perfil/foto"}:
            return profile_request(table, event)
        if event.get("routeKey") == "POST /fotos/subida":
            return upload_photo(table, event)
        if event.get("routeKey") in {"POST /planes", "PUT /planes/{id}", "DELETE /planes/{id}",
                                     "POST /planes/{id}/participantes", "DELETE /planes/{id}/participantes/me"}:
            return write_plan(table, event)
        if event.get("routeKey") == "GET /planes/{id}":
            return get_plan(table, (event.get("pathParameters") or {}).get("id"))
        if event.get("routeKey") != "GET /planes":
            return http_response(404, {"success": False, "error": "Ruta no disponible"})
        query = event.get("queryStringParameters") or {}
        try:
            return http_response(200, list_plans(table, query.get("nextKey")))
        except ValueError as error:
            return http_response(400, {"success": False, "error": str(error)})

    # Estos eventos se ejecutan con Probar en la consola de Lambda.
    action = event.get("action", "connection")
    try:
        if action == "photoConnection":
            bucket = photo_bucket()
            s3_client().head_bucket(Bucket=bucket)
            return {"success": True, "bucket": bucket, "mensaje": "Bucket accesible desde Lambda; la subida aun debe probarse desde Android"}
        if action == "connection":
            result = table.scan(Select="COUNT", Limit=5)
            return {
                "mensaje": "Conexion con DynamoDB correcta",
                "tabla": table.name,
                "registrosEnEstaLectura": result["Count"],
            }
        if action == "create":
            return create_plan(table, event)
        if action == "list":
            return json.loads(json.dumps(list_plans(table, event.get("nextKey")), default=json_number))
        return {"error": "Accion no reconocida"}
    except ValueError as error:
        return {"error": str(error)}
