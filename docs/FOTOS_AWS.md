# Fotos temporales de planes

## Estado y límites

Actualización de galería: hasta 4 fotos por plan, una portada y orden conservado.
Android permite añadir una por vez con cámara/galería, tocar miniaturas, quitar
cada foto y elegir portada; Revisión y Detalle permiten deslizar con contador.
El usuario confirmó edición de texto y reemplazo de la foto única en AWS.
El usuario confirmó desplegar la Lambda de galería y conservar las cuatro fotos,
la portada y la retirada de una foto al reabrir. No necesita repetir ese despliegue
por cambios exclusivos de pruebas Android o por la plantilla de correo.

Contrato nuevo: `photos` es una lista de 0 a 4 objetos `{imageKey}`; URLs HTTPS
heredadas se conservan solo cuando no hay clave. Se almacenan claves, nunca URLs
firmadas. La respuesta añade una URL temporal por foto. `imageKey/imageUrl` se
mantienen como portada para compatibilidad. Lista vacía quita todas las fotos.
Las fotos existentes se reutilizan sin exigir que sean subidas recientes; cada
foto nueva debe pertenecer al mismo anfitrión y plan y cumplir límites JPEG.
Solo después de guardar se eliminan claves retiradas; cancelar limpia todas.
Un cliente antiguo no puede editar un plan de varias fotos omitiendo su galería.
No hacen falta nuevas rutas, buckets ni permisos para esta ampliación.
Verificación local de galería: 38 pruebas JVM, 39 Python y 21 de instrumentación
seleccionadas aprobadas, incluidas límite, portada, borrado, deslizar y reintento
de subida parcial. No acreditan la configuración real de AWS ni Lifecycle.

El usuario creó el bucket privado `planya-fotos-steve-23858` en us-east-1 y
confirmó agregar `FOTOS_BUCKET` en PlanYaApi. El código Android y Lambda está
preparado. El usuario desplegó el Python y muestra el autorizador JWT en las
rutas. Sus capturas muestran un plan nuevo con foto propia visible en Detalle.
Esto aporta evidencia de publicación/subida real; el agente no inspeccionó S3 ni
ejecutó esa escritura. Faltan verificación entre dispositivos, edición, cancelación,
expiración y configurar/comprobar Lifecycle. Los pasos de abajo quedan como guía.

No hay claves AWS en Android, URLs públicas permanentes ni ACL públicas.
El campo de enlace HTTPS desaparece del formulario. Se conservan URLs heredadas
solo por compatibilidad de registros existentes; no se ofrece introducirlas.

## Configuración de AWS, en orden

1. Descargar un respaldo del código actual de Lambda si tiene cambios propios.
   Reemplazar el código de `PlanYaApi` por `backend/lambda_function.py` completo
   y pulsar Deploy. Mantener `TABLE_NAME=PlanYaPlanes` y
   `FOTOS_BUCKET=planya-fotos-steve-23858`. Usar tiempo máximo de 30 segundos
   para las consultas de S3 y DynamoDB; no habilitar servicios adicionales.
2. Probar desde la consola con `backend/events/photo-connection.json`.
   Hace únicamente HeadBucket; acredita acceso de lectura al bucket, NO una
   subida ni todos los permisos necesarios.
3. En API Gateway, PlanYaApiHttp, agregar `POST /fotos/subida` con integración
   PlanYaApi, formato 2.0 y el mismo autorizador JWT Cognito de las escrituras.
   No dejar esta ruta abierta. Confirmar despliegue de la etapa $default.
4. Conservar las rutas protegidas de crear, editar, cancelar, unirse y salir.
   GET /planes y GET /planes/{id} siguen públicos para explorar.
5. Verificar permisos del rol de ejecución Lambda en ESTE bucket: s3:ListBucket
   para HeadBucket y s3:PutObject, s3:GetObject, s3:DeleteObject sobre
   `arn:aws:s3:::planya-fotos-steve-23858/fotos/*`. Si el laboratorio lo deniega,
   solicitar revisión al profesor; no intentar saltar una denegación explícita.
6. En S3 → bucket → Administración → Crear regla de ciclo de vida:
   nombre `planya-fotos-temporales`, limitar al prefijo `fotos/`, habilitar
   expiración de versiones actuales después de 1 día. No seleccionar transiciones
   a IA/Glacier ni aplicar la regla a todo el bucket. Mantener versiones apagadas.
   `backend/s3-lifecycle.json` documenta la regla; no se aplica automáticamente.
   Si ya existen reglas, conservarlas: no reemplazar toda la configuración.

## Funcionamiento del formulario

- Tomar foto: delega en la cámara instalada y entrega permiso temporal a un
  único archivo mediante FileProvider no exportado. No pide acceso a toda la
  galería ni permiso de cámara para una cámara propia.
- Elegir foto: Photo Picker de Android; el contrato ofrece selector de documentos
  como alternativa en dispositivos sin Photo Picker. Cancelar conserva lo anterior.
- Preparación: limita origen a 25 MB/120 megapíxeles, decodifica con muestreo,
  corrige orientación/reflejo EXIF, vuelve a codificar JPEG y elimina metadatos
  originales (incluido GPS). Lado máximo 1600 px y archivo final máximo 1.5 MB.
- Vista previa local, reemplazar o quitar. La galería original nunca se borra.
  Copias privadas excluidas de copias de seguridad y transferencia de dispositivo.
- Revisar/confirmar el plan antes de subir. Hasta entonces no se envía la imagen
  a AWS. Un invitado prepara el borrador, pero debe ingresar para publicar.
- Subir primero la foto, después guardar el plan. Un fallo no publica silenciosamente
  un plan sin la foto elegida. Conserva el borrador y permite reintentar o quitarla.
  Si la subida terminó y falló guardar, reutiliza su clave al reintentar.
  Después de 20 minutos la vuelve a subir; Lambda rechaza adjuntar fotos nuevas
  de más de 30 minutos para evitar publicar un borrador próximo a limpieza.
- Al guardar o descartar se borra la copia local actual. Archivos abandonados del
  proceso/cámara se limpian después de 24 h al abrir otro editor.

## Contrato y organización

| Componente | Responsabilidad |
| --- | --- |
| PlanPhotoPicker | Cámara/galería, cancelación, vista previa y quitar. |
| PlanPhotoStore | Archivos privados, preparación, rotación y eliminación local. |
| PlanPhotoGateway / PlanPhotoRepository | Contrato de subida y transporte real a S3. |
| PlanPhotoApi | POST /fotos/subida con token Cognito. |
| PlanEditorViewModel | Borrador, preparación, subida, guardar y recuperación de fallos. |
| PlanActions / PlanActionsRepository | Contrato de mutaciones de planes y implementación AWS. |
| Lambda | Autoría, plan destino, política de subida, validación y enlaces temporales. |

POST /fotos/subida recibe `requestId` UUID del borrador, `size`,
`contentType=image/jpeg` y opcionalmente `planId` al editar. Devuelve `uploadUrl`,
`fields`, `imageKey` y `maxBytes`. Es una firma POST de 5 minutos, con clave exacta,
tipo, tamaño, cifrado y Cache-Control restringidos. Android manda formulario
multipart a S3 con el archivo al final y SIN el token Cognito.

La clave es `fotos/{usuario-hash}/{plan-uuid}/{foto-uuid}.jpg`. El ID final del
plan nuevo se deriva del usuario y requestId; una foto no puede adjuntarse a otro
usuario o plan. En edición se comprueba anfitrión y vigencia. Lambda verifica
HeadObject, MIME, tamaño y cabecera JPEG antes de guardar. No es un análisis
antivirus ni una decodificación completa del JPEG en servidor.

POST /planes y PUT /planes/{id} reciben imageKey, nunca la ruta privada local.
DynamoDB conserva la clave, no el enlace firmado. GET de listado/detalle y
respuestas de escritura generan acceso temporal a la foto, máximo 5 minutos y
nunca más allá de expiresAt. Para renovar un enlace, actualizar/reabrir detalle.
Los planes son explorables públicamente: bucket privado no equivale a plan privado.

## Vigencia y borrado, sin promesas de instantaneidad

- Cuando vence el plan, GET deja de devolverlo y no emite enlaces nuevos.
  La pantalla abierta revisa vencimiento cada 15 segundos y oculta la foto propia.
  Una cancelación hecha en otro dispositivo se conoce al actualizar/consultar
  nuevamente el plan; esta entrega no tiene notificaciones push de cancelación.
- Cancelar o quitar/reemplazar foto intenta borrar el objeto anterior DESPUÉS de
  guardar correctamente en DynamoDB. Un conflicto no elimina la foto válida.
- Si ese borrado falla, se registra un aviso sin firma/credenciales. La regla de
  S3 es respaldo para fotos vencidas, reemplazadas y subidas abandonadas.
- Lifecycle por días es asíncrono: no equivale a borrar al minuto en que termina
  el encuentro. No hay tarea programada de limpieza por minuto en esta entrega.
- Desactivamos caché persistente/memoria de estas imágenes en Coil y solicitamos
  no-store al servirlas. No es posible retirar capturas ni copias ya descargadas
  por otra persona; un enlace firmado tampoco revoca una descarga ya iniciada.
- S3 y solicitudes consumen recursos/créditos. Esta configuración no garantiza
  gratuidad; controlar el laboratorio y no añadir facturación personal.

## Prueba final pendiente

Con dos cuentas reales: crear con cámara, crear con galería, publicar sin foto,
abrir desde la otra cuenta, editar conservando/reemplazando/quitando foto, denegar
edición ajena, cancelar, probar vencimiento y verificar ausencia de foto pública.
Comprobar también red caída y reintento después de subir pero antes de guardar.
Las pruebas aisladas de Android/Python no sustituyen esta prueba real.

Verificación local: 34 pruebas JVM, 37 instrumentadas en Motorola y 29 Python
pasaron. Cámara/galería usan resultados simulados en las pruebas automáticas;
queda comprobar manualmente la aplicación de cámara/selector del teléfono y el
recorrido real con S3. Preparación de archivos, orientación, GPS, cancelación,
conservación del borrador y reintento están cubiertos sin escrituras en AWS.

Fuentes:
- https://developer.android.com/training/data-storage/shared/photo-picker
- https://developer.android.com/media/camera/camera-deprecated/photobasics
- https://docs.aws.amazon.com/boto3/latest/reference/services/s3/client/generate_presigned_post.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html
