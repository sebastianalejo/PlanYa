# Paso 2 — Canje de beneficios: primera parte local

Fecha: 11 de noviembre de 2026. Sin despliegue ni cambios de permisos en AWS.

## Flujo acordado

Un participante no acredita su asistencia por pulsar «Unirme». Un operador de
prueba autorizado confirma la asistencia; el servidor entrega un cupón con reglas
de una oferta configurada. El usuario muestra su código y el operador lo canjea
una sola vez. No implica acuerdos reales con comercios ni descuentos comerciales.

Esta entrega implementa **solo el consumo autorizado del cupón**, no la emisión,
la confirmación de asistencia ni la interfaz del operador. Beneficios NO está al
100 %. Las pruebas usan datos aislados; no se insertaron cupones en la cuenta.

## Contrato implementado localmente

`POST /canjes` protegido por el autorizador JWT existente. Cuerpo exacto:

```json
{"code":"ABCDEF123456","requestId":"2cb3f90e-e167-49de-adf1-2a5325c9c911"}
```

`requestId` es un UUID generado antes del primer envío y conservado en todos sus
reintentos. No reutilizarlo para otro código. Solo se admiten ID tokens verificados
por API Gateway; la identidad no se obtiene del cuerpo ni de un campo `role`.

Respuesta: `{success:true,data:{benefitId,status:"USADO",usedAt,requestId}}`.
No devuelve el código ni el propietario al operador. 401 sin sesión; 403 sin
autorización/local incorrecto/autocanje; 404 inexistente; 410 vencido; 409 conflicto;
503 resultado incierto, reintentar **la misma solicitud**, no crear una nueva.

## Autorización y persistencia

En la tabla existente, administración provisionará un registro separado:

```json
{"id":"operador#SUB_DE_PRUEBA","entityType":"BENEFIT_OPERATOR",
 "active":true,"merchantIds":["LOCAL_DE_PRUEBA"]}
```

Es un contrato, NO una instrucción de crear ahora registros o cuentas. Ninguna
ruta de la aplicación permite crear/modificar este permiso. Cambiar nombre,
intereses o perfil no lo concede. No se utiliza el correo para autorizar.

El cupón requiere `entityType=BENEFIT`, propietario, `merchantId`, `validUntil`
en segundos y estado `DISPONIBLE`. Los cupones antiguos sin local/vigencia no
pueden consumirse con este flujo hasta una migración explícita; no se completan
con locales o fechas inventados.

Una transacción verifica operador activo/local, código vigente/propietario,
cupón disponible/local/propietario/vigencia, cambia el cupón a USADO y guarda el
recibo idempotente. Si una condición falla no hay consumo ni recibo parcial.
El recibo guarda hash del código, no el código. Está ligado al sub del operador
y al requestId. Repetir una solicitud confirmada recupera el recibo incluso si
el código venció; un operador revocado no puede consultarlo.

Los registros son auxiliares: las rutas públicas de planes los excluyen.
El recibo no tiene TTL: conservarlo permite resolver reintentos; acordar su
retención antes de producción. No activar TTL sobre `expiresAt` de planes.

## Evidencia local

`python -m unittest discover -s backend/tests -q`: **113 pruebas correctas**.
Incluye autorización, token incorrecto, usuario normal con rol falso, autocanje,
local incorrecto, límites de vencimiento, revocación, solicitud malformada,
reintento con otro código, pérdida de respuesta, ocho canjes simultáneos y cambios
de permisos/cupón justo antes del commit. También cubre el despacho Lambda.

La concurrencia usa un almacén transaccional simulado con bloqueo y comprobación
del contrato de expresiones. **No sustituye una prueba de DynamoDB real** ni
valida aún los permisos del laboratorio. No cambió Android en esta entrega.

## Siguiente, antes de desplegar

1. Implementar ofertas/confirmación de asistencia y emisión idempotente, sin que
   el usuario elija descuento o se confirme a sí mismo.
2. Reemplazar el cursor de listado de beneficios: hoy puede contener una clave
   auxiliar de la tabla; debe ser opaco y ligado a usuario/filtro antes de
   considerar cerrado el flujo de seguridad.
3. Interfaz de operador de prueba: seleccionar/confirmar asistencia, introducir
   código, revisar condiciones y confirmar canje. Persistir requestId ante red
   incierta; nunca mostrar éxito inventado ni descuentos de demostración al usuario.
4. Pruebas Android, dos cuentas y regresiones completas.
5. Después del cierre local: paquete Lambda con `lambda_function.py`, `chat.py`
   **y `benefit_redemption.py`**. Mantener variables/roles actuales. Verificar permiso
   transaccional del laboratorio sin intentar eludir denegaciones. Configurar
   `POST /canjes` con JWT y la misma integración, sin abrirla al público.
6. Probar transacción real, doble canje, reintentos y lectura del estado en el
   teléfono; solo después actualizar porcentaje de aceptación.
