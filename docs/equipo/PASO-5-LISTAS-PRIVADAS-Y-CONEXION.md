# Paso 5 — Continuaciones privadas y preparación de conexión AWS

11 de noviembre de 2026. Implementación local; sin despliegue ni push de este paso.

## Corrección y conexión con Android

GET `/planes` y GET `/beneficios` ya no devuelven el ID de la última fila evaluada
por Scan. En una tabla compartida esa fila podía ser un perfil o un código de
canje, aunque los elementos visibles estuviesen filtrados correctamente.
Codificarla en base64 tampoco habría resuelto su exposición.

`nextKey` ahora es un UUID aleatorio, sin datos del usuario ni claves de tabla.
Su registro privado `PAGE_CURSOR` vive en PlanYaMensajes con `planId=page#UUID`,
`sk=cursor`, ámbito, clave interna y `expiresAt=ahora+900`. Se reutiliza el TTL de
mensajes; no se crea otra tabla, servicio, clave o secreto.

- Planes públicos: ámbito `public-plans`. No requiere cuenta, porque el listado
  ya es público. Un cursor de beneficios no sirve en esta ruta ni viceversa.
- Beneficios: ámbito ligado a cuenta y filtro Disponible/Usado/Vencido. Cambiar
  cualquiera invalida la continuación antes de consultar la tabla de planes.
- La clave privada nunca se acepta directamente desde HTTP, ni se devuelve
  como alternativa si falla el almacenamiento del cursor.
- La vigencia se valida en servidor aunque TTL todavía no haya borrado la fila.
  El cursor se puede reintentar durante su ventana; no se consume al leerlo.
- Una página vacía puede ofrecer continuación. Sin LastEvaluatedKey no hay
  escritura/lectura de cursor adicional. Cuando sí la hay se realizan accesos
  normales a DynamoDB: no se garantiza consumo cloud gratuito.
- Beneficios usa lectura consistente y vuelve a comprobar tipo, propietario y
  estado de cada elemento después del filtro de Scan.

400 `CURSOR_INVALID` / 409 `CURSOR_EXPIRED` tienen mensaje fijo sin eco de la clave.
Android los distingue de «cupón ya usado». Inicio, Mapa y Beneficios conservan
sus tarjetas/pines/cupones, descartan solo la continuación y permiten actualizar
desde la primera página al pulsar Reintentar. Una caída de conexión conserva el
cursor para reintentar esa misma página. No hay recarga infinita ni automática.

Los eventos administrativos de consola Lambda `action=list` mantienen su contrato
interno; no están disponibles como acción de un cliente HTTP. La app usa solo las
rutas HTTP. Las apps antiguas con una continuación guardada deberán actualizar
la lista, no intentar convertir ese identificador a un cursor nuevo.

## Evidencia local

152 pruebas Python correctas; diez casos nuevos cubren claves auxiliares,
páginas vacías, cuenta/filtro/ruta incorrectos, caducidad, reutilización, límites
de longitud, última página y fallo de almacenamiento sin filtrar una clave.
98 pruebas JVM correctas. Cuatro pruebas Android nuevas de Inicio/Mapa/Beneficios
pasaron con repositorios aislados; el acta final añade la regresión seleccionada.
No se enviaron asistencias, canjes, publicaciones ni mensajes reales a AWS.

Regresión seleccionada final: **73/73 Android correctas, 381,824 segundos**,
incluyendo las cuatro nuevas y las 69 del conjunto anterior. Compilaciones
`assembleDebug`, `assembleDebugAndroidTest` y `testDebugUnitTest` correctas.
Se instaló con `adb install -r`; no se desinstaló la app principal ni se borró su
sesión. Al terminar se retiró solo el APK auxiliar de pruebas y se abrió PlanYa.

## Comprobación real de solo lectura

Antes de que el usuario encendiese el laboratorio: GET `/planes` → 500.
Después de encenderlo: GET `/planes` → 200, success=true, dos elementos.
GET `/conversaciones` y GET `/operador/ofertas` continúan devolviendo 404.
La recuperación no demuestra la causa exacta del 500 sin registros, pero el
listado actual volvió a funcionar. No se confundió 404 con un listado vacío.

La habilidad `computer-use` no pudo acceder a Windows: el helper nativo no estaba
disponible en dos intentos. Tampoco hay AWS CLI instalada. No se automatizó login,
se pidieron credenciales ni se cambiaron permisos. La revisión autenticada de
consola necesita intervención del usuario; se solicitó confirmar la existencia
de PlanYaMensajes antes de recomendar un despliegue.

## Paquete integrado preparado, NO subido

`app/build/backend/PlanYaApi.zip`, con exactamente estos módulos en la raíz:

1. `lambda_function.py`
2. `chat.py`
3. `benefit_redemption.py`
4. `operator_panel.py`

SHA-256 del paquete preparado:
`27B8C544632338338203256F6E9DA39CE923D5BD4A050FE8B6DDB4A5CA69226E`.

No incluye tests, archivos personales, claves, preferencias, fotos ni configuración
local. Es un artefacto ignorado por Git; debe regenerarse después de cambiar un
módulo. Se verificaron los cuatro nombres de entrada. Se usaron cmdlets nativos
de compresión; no se alteró la política de ejecución de scripts de Windows.

Desde PowerShell en la raíz del proyecto, se puede regenerar con:

```powershell
python -m unittest discover -s backend/tests -q
New-Item -ItemType Directory -Path app/build/backend -Force | Out-Null
Compress-Archive -LiteralPath @('backend/lambda_function.py','backend/chat.py','backend/benefit_redemption.py','backend/operator_panel.py') -DestinationPath app/build/backend/PlanYaApi.zip -Force
```

Ejecutar la compresión solo si las pruebas terminan correctamente. No usar
ExecutionPolicy Bypass ni pegar solo una versión antigua de lambda_function.py.

## Orden de la siguiente verificación guiada

1. Confirmar laboratorio activo y tabla PlanYaMensajes: PK `planId` cadena,
   SK `sk` cadena. Comprobar TTL `expiresAt`, sin activarlo sobre PlanYaPlanes.
2. Verificar permisos del rol actual para las tablas y las transacciones usadas.
   Ante una denegación explícita del laboratorio, no intentar eludirla.
3. Con autorización de despliegue, conservar rol/variables/handler y reemplazar
   conjuntamente los cuatro módulos mediante el ZIP; no guardar solo uno.
4. Revisar integración de las rutas nuevas: GET `/conversaciones`, GET
   `/perfil/actividad`, GET `/perfil/confianza`, GET `/operador/ofertas`, GET
   `/operador/ofertas/{id}/planes/{planId}`, POST `/asistencias`, POST `/canjes`.
   Usar JWT existente en todas; no poner JWT en GET público de planes.
5. Comprobar 401 sin token en rutas protegidas, rechazo de usuario normal al
   panel, conversación propia, paginación real y renovación de sesión.
6. Aprovisionar de forma administrativa operador/oferta/plan piloto solo con los
   datos y cuentas acordados. Confirmar asistencia, emitir, mostrar código,
   canjear una vez, repetir solicitud y verificar estado desde la otra cuenta.
7. Probar mapa/GPS real y los pendientes de Perfil antes de cerrar porcentajes.

Este paso no ha desplegado código, creado rutas/tablas/ofertas/cuentas ni enviado
el paquete a terceros. No certifica la aplicación al 100 % por pasar pruebas
aisladas; queda pendiente el recorrido real y la configuración del laboratorio.
