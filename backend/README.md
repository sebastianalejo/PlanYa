# Backend inicial de PlanYa

## Actualización: valoraciones (11 de noviembre)

El ZIP actual contiene **cinco** módulos en la raíz: `lambda_function.py`,
`chat.py`, `benefit_redemption.py`, `operator_panel.py` y **`ratings.py`**.
No usar el paquete histórico de cuatro módulos para esta versión. Nuevas rutas
JWT: GET /valoraciones y POST /valoraciones, integración PlanYaApi y autorizador
PlanYaUsuarios. Guía y limitaciones:
[Valoraciones para presentación](../docs/equipo/VALORACIONES-PRESENTACION.md).
Los apartados inferiores que dicen «no desplegado» o «cuatro módulos» son
históricos anteriores a esta actualización.

## Estado de esta versión local

Además de la lectura pública ya desplegada, el archivo local incluye creación,
edición, cancelación e inscripciones con identidad Cognito verificada por API
Gateway. El usuario mostró despliegue de rutas JWT y creación con foto. La revisión
de galería y Perfil fue desplegada por el usuario. Confirmó crear/editar, galería,
unirse/salir y respuesta de cancelación; quedan casos detallados en la lista de
cierre. La plantilla de correo y las pruebas Android no requieren otro Deploy.
La guía de configuración es `../docs/AUTENTICACION_AWS.md` y la tabla completa
de flujos está en `../docs/AVANCE_PANTALLAS.md`. Los párrafos de primera conexión
que siguen conservan el procedimiento del avance inicial.

El creador ocupa un cupo; el servidor no acepta el ownerId/authorName del cuerpo
para atribuir propiedad. Unirse es idempotente y respeta cupo/vencimiento; salir
libera el cupo. Editar no extiende el vencimiento. Cancelar marca CANCELLED y
vence el registro, sin borrado físico inmediato. Las escrituras comparan una
revisión para impedir que dos solicitudes sobrescriban participantes.
PUT admite además expectedRevision para detectar cambios desde que se abrió el
editor, no solo los ocurridos durante una escritura. Un 409 conserva el borrador
en Android y permite recargar con confirmación. Guía: ../docs/VERIFICAR_PLANES.md.

POST /planes admite requestId (UUID del borrador) para que reintentar la misma
publicación no cree otro ID si se perdió una respuesta. Cada borrador nuevo
genera otra clave. No se configura TTL automáticamente.

El archivo `lambda_function.py` se copia completo en la función AWS `PlanYaApi`.
Esta carpeta no se compila dentro de la aplicación Android.

## Configuración de Lambda

- Runtime: Python 3.13.
- Handler: `lambda_function.lambda_handler`.
- Variable de entorno: `TABLE_NAME=PlanYaPlanes`.
- Región de la tabla y la función: `us-east-1`.
- Rol: el rol del laboratorio con permisos efectivos de Scan y PutItem sobre
  PlanYaPlanes, además de los permisos necesarios para los logs.
- Después de copiar el código, pulsar Deploy antes de Probar.

Los JSON de `events` permiten probar conexión (`{}`), creación, listado y la
petición HTTP sin crear una API todavía. Crear nuevamente genera otro registro.

## API Gateway

1. Crear una **HTTP API**, llamada `PlanYaApiHttp`.
2. Agregar una integración Lambda con `PlanYaApi`, payload **2.0**.
3. Crear las rutas **GET /planes** y **GET /planes/{id}**, ambas con esa integración.
4. Usar la etapa **$default**, con implementación automática.
5. Conceder a API Gateway permiso para invocar Lambda cuando la consola lo solicite.
6. Abrir la URL de invocación seguida de `/planes`.

La respuesta es `{"success":true,"data":{"items":[],"nextKey":null}}`.
`items` contiene la página de planes. Si `nextKey` tiene valor, la siguiente
petición es `/planes?nextKey=VALOR` (codificar el valor como parámetro de URL).
No se promete un orden de resultados con Scan.

Las rutas de escritura y fotos requieren el autorizador JWT de Cognito. El listado
y detalle siguen siendo públicos; no publicar información privada en los planes.

Android se conectará por HTTPS a API Gateway; no necesita claves AWS en el APK.
Las pruebas locales usan un doble de DynamoDB y no acceden a AWS:

```text
python -m unittest discover -s backend/tests -v
```

## Actualización de ubicación y detalle

El código local incorpora `GET /planes/{id}`. Su respuesta es
`{"success":true,"data":{"id":"...","title":"..."}}`. Un plan inexistente
o vencido devuelve 404. La lectura usa `GetItem`, por lo que el rol de Lambda
necesita ese permiso sobre PlanYaPlanes, además de Scan y PutItem.

Para activar este avance en la cuenta:

1. Copiar completo `lambda_function.py` al editor de PlanYaApi y pulsar Deploy.
2. Agregar `GET /planes/{id}` en API Gateway y asociarla a PlanYaApi (payload 2.0).
3. Verificar permisos GetItem del rol. La etapa $default debe desplegar el cambio.
4. Si deseas crear un plan para comprobar el recorrido, revisar y ejecutar en
   Probar el evento `events/create_with_location.json`. Crea un registro real;
   no se ejecuta automáticamente desde este repositorio. Ajustar el título,
   ubicación y cupos al plan que realmente se quiera publicar.

Los campos adicionales son opcionales para conservar registros antiguos:

| Campo | Tipo | Uso |
| --- | --- | --- |
| latitude / longitude | Número | Coordenadas del lugar, se envían juntas. |
| meetingPlace | Cadena | Dirección o nombre del punto de encuentro. |
| imageUrl / authorAvatarUrl | Cadena HTTPS | Foto del plan y avatar. |
| authorName | Cadena | Nombre mostrado del anfitrión; por ahora solo evento de consola. |
| capacity | Entero 1–100 | Cupo total. |
| durationMinutes | Entero 30–120 | Entrada de creación; predeterminado 60. |
| expiresAt | Número entero | Salida: instante de vencimiento, Unix UTC en segundos. |
| attendees | Lista | Se inicializa vacía; todavía no hay API de inscripción. |

Los nombres de rutas son españoles (`/planes`); se conservan los nombres de
campos existentes para no romper el contrato inicial de Android.

El cliente oculta planes vencidos y deshabilita su inscripción. Este cambio
no configura automáticamente DynamoDB TTL ni elimina registros de la tabla.
La cuenta puede configurar TTL con `expiresAt` después; la limpieza de DynamoDB
es asíncrona y no sustituye la comprobación de vigencia de la app/API.

La lista sigue siendo paginada por Scan; el cliente descarta vencidos de cada
página. Una página puede no tener planes visibles y aun así ofrecer «Cargar más».
Los registros antiguos sin expiresAt se mantienen visibles hasta que se actualicen.

## Chat temporal (US-08)

`chat.py` contiene las rutas `GET/POST /planes/{id}/mensajes`, con JWT y la tabla
`PlanYaMensajes` (planId + sk, TTL `expiresAt`). Al desplegar, `chat.py` se agrega como
segundo archivo de la función PlanYaApi junto a `lambda_function.py`. Pasos completos,
contrato y pruebas: `../docs/equipo/US-08-ENTREGA.md`.

Con Chat habilitado, **no activar TTL en `PlanYaPlanes` con `expiresAt`**: el registro
se necesita para comprobar participantes durante las dos horas posteriores. El TTL de
mensajes corresponde únicamente a `PlanYaMensajes.expiresAt`; el servidor bloquea el
acceso al llegar la hora aunque la eliminación física se retrase.

`GET /conversaciones` (JWT) lista los chats autorizados todavía abiertos sin pasar
por el detalle público del plan. Usa cursores opacos ligados a la cuenta y TTL de
15 minutos en PlanYaMensajes; no retorna LastEvaluatedKey ni mensajes. Nueva ruta
pendiente de despliegue: `../docs/equipo/PASO-1-CONVERSACIONES.md`.

## Canje de beneficios — primera parte local

`benefit_redemption.py` implementa `POST /canjes` con operador autorizado por
registro administrativo, local asociado y transacción de consumo/recibo.
Todavía no se ha desplegado. No concede cupones ni acredita asistencia.
El paquete final necesita este tercer archivo además de `lambda_function.py`
y `chat.py`; no desplegar solo uno. Contrato, pruebas y pendientes:
`../docs/equipo/PASO-2-CANJE-BENEFICIOS.md`.

Actualización local: el mismo módulo también implementa `POST /asistencias`
para confirmar y emitir atómicamente un cupón desde una oferta administrativa.
No está desplegado ni tiene aún interfaz de operador. Política y pruebas:
`../docs/equipo/PASO-3-ASISTENCIA-Y-EMISION.md`.

El panel Android añade dos lecturas JWT en `operator_panel.py`: ofertas de los
locales autorizados y participantes de sus encuentros. Los cursores privados de
este panel reutilizan PlanYaMensajes (TTL 15 minutos). El paquete final pasa a
cuatro módulos: Lambda, Chat, canje/asistencia y panel. Todavía no desplegado.
Contrato y pendientes: `../docs/equipo/PASO-4-PANEL-OPERADOR.md`.

## Continuaciones HTTP privadas

GET `/planes` y GET `/beneficios` devuelven ahora `nextKey` opaco con vigencia de
15 minutos en PlanYaMensajes. Los beneficios vinculan la continuación a cuenta y
filtro. Android conserva tarjetas/pines/cupones y permite actualizar ante
`CURSOR_EXPIRED`/`CURSOR_INVALID`; no interpreta una clave interna de DynamoDB.
No activar TTL sobre los planes. El paquete local con cuatro módulos y el orden
de conexión se documentan en `../docs/equipo/PASO-5-LISTAS-PRIVADAS-Y-CONEXION.md`.
La política de cursores exige comprobar tabla/permisos de mensajes antes de
desplegar, también para la paginación del listado público.
