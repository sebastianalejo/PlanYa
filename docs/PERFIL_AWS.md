# Perfil propio de PlanYa

## Qué implementa

Mi perfil muestra nombre/correo reales, foto privada y las opciones existentes.
Editar perfil es una pantalla aparte: nombre visible, cámara/galería, cambiar o
quitar foto, guardar, descartar con confirmación y recargar ante conflicto.
Los errores conservan el borrador. No se modifica la cuenta de Google ni el correo.
No se inventan estrellas, verificación, estadísticas ni datos de otros usuarios.

El perfil se consulta al abrirlo; al reabrir la app se recupera desde AWS, no de
una pantalla de demostración. Sin rutas disponibles se muestra un error explícito
y no se simula un guardado exitoso. El nombre guardado se usa en nuevos planes y
nuevas inscripciones. Los planes/participaciones anteriores conservan su nombre
histórico. Los intereses se sincronizan automáticamente al guardar la selección,
al ingresar y al reabrir la app. Sin red quedan locales y Perfil permite reintentar.
El perfil conserva también los intereses actuales al guardar su nombre/foto.

## Activación en AWS

1. Lambda → PlanYaApi → Código: copiar completo `backend/lambda_function.py` y
   pulsar Deploy. Conservar TABLE_NAME y FOTOS_BUCKET, rol y región us-east-1.
2. API Gateway → PlanYaApiHttp → Rutas: crear las siguientes y asociar cada una
   a Lambda PlanYaApi con formato de carga 2.0.

| Método | Ruta | Autorizador |
| --- | --- | --- |
| GET | /perfil | PlanYaUsuarios (JWT existente) |
| PUT | /perfil | PlanYaUsuarios (JWT existente) |
| POST | /perfil/foto | PlanYaUsuarios (JWT existente) |

3. En Autorización comprobar el distintivo JWT en las tres. No dejar GET /perfil
   público ni usar IAM integrado. Conservar el mismo emisor y audiencia del resto.
4. Mantener implementación automática en $default; si no está activa, implementar
   los cambios. No crear otra API, otro bucket, otra tabla ni claves AWS en Android.
5. Confirmar tiempo máximo de Lambda de 30 segundos para validar y copiar la foto.
6. El rol Lambda necesita GetItem/PutItem sobre PlanYaPlanes. En el bucket privado
   necesita s3:GetObject, s3:PutObject y s3:DeleteObject sobre `fotos/*` y `perfiles/*`.
   Conservar los permisos ya existentes; si están limitados a fotos/*, revisar el
   nuevo prefijo con el responsable del laboratorio. No intentar eludir denegaciones.
   Copiar requiere GetObject en origen y PutObject en destino, según la
   [documentación de AWS](https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html).

## Datos y privacidad

Se utiliza PlanYaPlanes con registros separados por `id=perfil#<sub Cognito>` y
`entityType=PROFILE`, sin expiresAt ni TTL de planes. El listado y detalle público
de planes excluyen estos registros. GET/PUT /perfil siempre usa el sub verificado
del JWT, nunca un userId que mande Android. El correo procede de Cognito y no se
actualiza por PUT. expectedRevision protege contra cambios entre dispositivos.

Las fotos son JPEG hasta 1.5 MB, corregidas de orientación y sin EXIF original.
Una subida pendiente vive en `fotos/perfiles/<hash usuario>/<uuid>.jpg`; la regla
Lifecycle fotos/ de un día, si está configurada, también limpia abandonos.
Al guardar se valida, copia a `perfiles/<hash usuario>/<uuid nuevo>.jpg` y se guarda
su clave. Solo después del guardado se elimina la foto anterior y la pendiente.
Una disputa de revisión limpia su copia nueva, nunca la foto que ganó otra petición.
Si una escritura falla con resultado desconocido tras copiar, podría quedar una
copia huérfana persistente: revisar logs/objetos antes de borrarla; no hay un janitor
automático para ese caso ambiguo. No aplicar Lifecycle de un día a perfiles/*.

No hay ACL pública. La respuesta firma URLs de lectura por cinco minutos; volver
al perfil las actualiza. El cliente no mantiene caché de imagen en memoria/disco.
Las copias locales del borrador se eliminan al guardar/descartar; las fotos de la
galería original no se borran. La dirección de búsqueda no se envía al perfil.

## Comprobación real pendiente

1. Ingresar con Google → Perfil → Editar perfil.
2. Cambiar nombre y guardar. Volver a Inicio y al Perfil: debe recuperar el nombre.
3. Cerrar/abrir app y repetir; la sesión de Google no debe pedir cambiar sus datos.
4. Elegir foto → guardar → reabrir Perfil; probar cámara y quitar foto por separado.
5. Cuenta B no debe poder consultar el perfil privado de A ni elegir su clave S3.
6. Dos dispositivos editan la misma revisión: el segundo ve conflicto y conserva
   su borrador. Recargar pide confirmación; si falla, conserva los cambios locales.
7. Crear un plan nuevo: el nombre del anfitrión debe ser el guardado en PlanYa.

Estas operaciones todavía no se consideran verificadas contra la cuenta real.

Verificación local: compilación correcta; 39 pruebas JVM y 49 Python aprobadas.
23 casos seleccionados de instrumentación aprobados (6 nuevos de edición de
Perfil), más revisión visual del editor. Los repositorios de prueba son aislados:
no se guardaron perfiles reales ni se cerró la sesión del usuario.
