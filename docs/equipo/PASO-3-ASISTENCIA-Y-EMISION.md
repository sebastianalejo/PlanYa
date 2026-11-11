# Paso 3 — Asistencia y emisión de cupón: backend local

11 de noviembre de 2026. No desplegado. Sin cambios en AWS ni GitHub.

## Implementado

`benefit_redemption.py` ahora también atiende `POST /asistencias`, mediante
`lambda_function.py`. Usa ID token verificado y el mismo operador administrativo
del paso 2. El participante no puede confirmar ni emitir; un operador tampoco
puede confirmarse a sí mismo.

Cuerpo exacto:

```json
{"planId":"4cd6a6a2-642b-46fe-82e4-b7691eec3800","userId":"SUB_PARTICIPANTE",
 "offerId":"OFERTA_CONFIGURADA","requestId":"2cb3f90e-e167-49de-adf1-2a5325c9c911"}
```

El operador seleccionará estos identificadores en su futura interfaz; no se pide
al usuario normal escribirlos. `planId` y `requestId` son UUID canónicos. El sub
del participante y el ID de oferta admiten letras, números, guion y guion bajo,
hasta 128 caracteres. Campos adicionales como descuento, local o propietario
alternativo se rechazan.

Respuesta: `{success:true,data:{benefitId,planId,attendanceConfirmed:true,requestId}}`.
No significa canje: el cupón sigue DISPONIBLE hasta `POST /canjes`.

## Política del piloto, explícita

- Administración crea una oferta y autoriza **planes concretos** para ella. Ni
  buscar una dirección ni publicar un plan cerca de un comercio dan derecho a
  un cupón. No hay ofertas/comercios ficticios precargados en la app.
- La persona debe seguir siendo anfitrión o participante al confirmar.
- Un plan cancelado no admite confirmaciones.
- Ventana: desde `startsAt` hasta antes de `expiresAt + 7200` segundos, además
  de la vigencia de la oferta. Es una política piloto configurable en código;
  no supone que la expiración del anuncio sea la hora real de fin del encuentro.
- Un operador comprueba presencialmente la asistencia. El servidor valida
  permisos y consistencia; no demuestra por sí mismo presencia física/GPS.
- Máximo **una asistencia y un cupón por persona y plan**, aun con dos ofertas,
  operadores distintos o nuevos requestId. El anfitrión también puede asistir,
  pero no confirmarse si actúa como operador.
- El descuento, texto, condiciones, categoría y límites salen de la oferta.
  El cupón vence en el menor de `ahora + couponDurationSeconds` y `validUntil`
  de la oferta. No se elige el descuento desde Android.

## Oferta administrativa — contrato, no crear aún

Registro de tipo `BENEFIT_OFFER`, con clave `oferta#ID`, `active` booleano,
`revision` entero positivo, `merchantId`, `planIds` lista/conjunto de UUID,
`validFrom`/`validUntil` epoch segundos, `couponDurationSeconds` entre 60 y
2592000, `merchantName`, `title`, `discount`, `conditions`, `category` del
catálogo existente y `limits` texto opcional. No contiene claves ni contraseñas.

Todo cambio administrativo de la oferta debe incrementar `revision`. No existe
API pública para editar este registro. Los permisos de operador se comprueban
mediante su registro administrativo, no mediante atributos de perfil editables.

## Atomicidad, reintentos e integración

Una transacción de seis operaciones verifica operador, oferta/revisión/plan
autorizado y plan/revisión/membresía/horario; después crea asistencia, cupón y
recibo. Todo o nada. Las claves de asistencia y cupón son deterministas por
plan/participante; la escritura exige que no existan.

El recibo liga operador, requestId y hash de plan/participante/oferta. Reintentar
exactamente la misma solicitud recupera la confirmación incluso tras vencer la
oferta; no emite otro cupón. Un requestId reutilizado para otra asistencia da
409. Un operador revocado no puede consultar el recibo. Con respuesta incierta
503, conservar requestId y reintentar: no anunciar un fallo definitivo ni éxito
local. No se añade TTL a recibos/asistencias; acordar retención en producción.

La asistencia usa `entityType=ATTENDANCE` y `userId` del participante: ya cuenta
en `GET /perfil/actividad` sin incrementar contadores desde Android. Los demás
registros son auxiliares y no se muestran como planes. Consulta, código y canje
existentes consumen el nuevo cupón sin cambiar sus DTO Android.

## Verificación

`python -m unittest discover -s backend/tests -q`: **131 pruebas correctas**.
Incluye ocho confirmaciones simultáneas entre dos operadores, reintentos,
pérdida de respuesta, invalidación antes del commit y rollback sin cupón parcial.
Una prueba integrada enlaza confirmar → consultar cupón → generar código con la
Lambda actual → canjear → consultar USADO → actividad con una asistencia.

Almacén transaccional simulado, sin red ni cuentas reales. No valida todavía las
expresiones/los permisos en DynamoDB real. Android no cambió en este paso.

## Pendiente para cerrar Beneficios

1. Interfaz de operador y consultas protegidas de ofertas/planes/participantes,
   con revisión humana antes de confirmar y requestId estable ante red incierta.
2. Cursores opacos: revisar tanto beneficios como listado público de planes;
   sus claves de continuación actuales pueden revelar claves auxiliares de la
   tabla compartida. No desplegar el flujo como terminado sin corregirlo.
3. Pruebas Android y recorrido con dos cuentas, operador/local incorrectos,
   doble canje y escaneo/entrada de código.
4. Solo después: actualizar paquete Lambda con los tres módulos, rutas JWT
   `/asistencias` y `/canjes`, comprobar permisos transaccionales del laboratorio
   y ofertas/operador de prueba provisionados de forma administrativa.

No habilitar servicios nuevos ni eludir políticas del laboratorio. No hay
descuentos reales ni aceptación al 100 % hasta esas validaciones.
