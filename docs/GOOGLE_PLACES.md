# Activar direcciones con Google Places

Estado: SDK 4.3.1 integrado en el selector de ubicación de Inicio/Detalle.
Un solo buscador usa Google Places cuando está configurado y pasa a una
alternativa nativa internamente si falla. No muestra selectores de proveedor.
El usuario confirmó la carga real del mapa mediante captura; la nueva consulta
de dirección al tocar un pin requiere validación manual en su dispositivo.
La configuración se carga del archivo local secrets.properties, ignorado por Git.
GOOGLE_PLACES_ENABLED permite utilizar Places al escribir. No inicializa
el cliente ni consulta Google al construir el servicio o al abrir Inicio.
El usuario decidió mantener la clave compartida tras advertirse la exposición.
No registrar la clave ni copiarla a esta documentación. Restringirla sigue siendo
necesario; reemplazarla continúa recomendado.
No confundir Google Cloud con la cuenta de AWS Academy.

Las pruebas de interfaz inyectan GooglePlacesSource sin red: no usan la clave ni
consumen cuotas. GooglePlacesSafetyTest fuerza enabled=false y comprueba rechazo
antes de inicializar el SDK. No se modifican cuotas ni recursos de AWS/Cloud.
Última comprobación: assembleDebug, 31 pruebas unitarias y 26 pruebas en el
Motorola conectado pasaron (57 en total). Incluyen selección/confirmación con
Google sin red, alternativa interna, recuperación por ID, cifrado, borrador,
revisión antes de publicar, filtros, sesión OAuth y altura del selector de mapa.
La captura de diseño de pruebas usa un fondo de mapa inyectado: no demuestra
una consulta real de dirección ni sustituye la revisión del mapa de Google.

## Uso y guardado en esta versión

- Escribir al menos 3 caracteres: espera 900 ms antes de
  pedir predicciones; cada respuesta antigua se cancela al cambiar la búsqueda.
- Prioriza geográficamente la ubicación disponible mediante locationBias, sin
  bloqueo por país o radio. Se puede escribir otra ciudad o país sin cambiar modos.
  Sin ubicación se usa Lima como contexto del piloto, no como posición detectada.
- Solo se consulta el detalle de la sugerencia elegida; solicita campos
  Essentials, no fotos, reseñas ni DISPLAY_NAME. Después se confirma la dirección.
- Las predicciones y datos de Google permanecen en memoria. En disco solo se
  guarda, cifrado, googlePlaceId. Nunca se almacena como si fuera una dirección
  nativa ni se envía a DynamoDB desde este selector de Inicio.
- Al reabrir no se hace una consulta automática. Inicio ofrece Recuperar mi
  dirección; en el selector, Recuperar mi dirección con Google hace la consulta
  explícita por ID. Hasta confirmar no hay dirección ni distancia inventadas.
- Una dirección elegida con GPS, geocodificación nativa o pin conserva guardado cifrado.
- Crear/Editar usa todavía el buscador nativo: integrar Places para publicar
  exige un contrato por placeId en backend, sin persistir indebidamente contenido
  del proveedor. No se presenta esa parte como terminada.
- Atribuciones Google Maps y de proveedores en selección e Inicio/Detalle.
  Enlaces a términos y privacidad de Google. Antes de distribución pública,
  PlanYa aún debe publicar sus propios términos y política de privacidad.
- Las cuotas de 10/día fueron indicadas por el usuario; no verificadas por un
  conector administrativo. La app no ofrece garantía de cero cargos de la cuenta.

## Lo que debe preparar el titular de la cuenta

1. Entrar a https://console.cloud.google.com/ y seleccionar Novatech, que es el
   proyecto usado por el titular. No existe un proyecto Google separado para PlanYa.
2. Vincular una cuenta de facturación si se decide contratar el servicio. No se
   puede prometer coste cero: depende del uso, cuotas y precios vigentes.
3. Habilitar Places API (New). No hace falta habilitar todas las APIs de Maps.
4. Crear una clave con restricción de aplicación Android: paquete
   `com.example.planya` y SHA-1 del certificado de firma correspondiente.
   Obtenerlo con `gradlew.bat :app:signingReport`. Agregar por separado las
   firmas de depuración y de producción cuando corresponda.
5. Restringir la clave únicamente a la API necesaria. Definir cuotas y alertas
   de presupuesto: una alerta no es un límite duro de gasto.
6. Guardar la clave localmente en `secrets.properties` (ya ignorado por Git),
   nunca en documentación, capturas públicas, mensajes o commits.

## Contrato de integración y pendientes antes de distribución

- SDK oficial Places para Android, Autocomplete (New), con token de sesión.
- Región y país elegidos por el usuario; priorizar proximidad sin fingir que
  una coincidencia mundial es cercana. Permitir ampliar búsqueda explícitamente.
- Pedir detalles únicamente del resultado seleccionado, con los campos mínimos
  necesarios para dirección y coordenadas, y tratar errores de cuota/red/clave.
- Mantener confirmación antes de actualizar Inicio o publicar un encuentro.
- Conservar las atribuciones Google Maps y las de los proveedores.
- Revisar restricciones de almacenamiento: no copiar resultados completos de
  Google a la caché actual ni a DynamoDB como si fueran datos sin restricciones.
  Los place IDs tienen una excepción de almacenamiento. Adaptar el contrato de
  ubicaciones y su recuperación antes de activar guardado de contenido de Places.
- Publicar términos de uso y política de privacidad exigidos por el proveedor.
- Probar búsquedas, cancelaciones, cambios de región, ausencia de GPS, cuotas,
  denegación de permisos y creación de un plan con la dirección seleccionada.

El servicio no hace precargas ni reintentos automáticos de red. Cada consulta
tiene un tiempo máximo de 12 segundos y admite cancelación. Ante error se ofrece
reintentar explícitamente o volver a Teléfono/GPS.

Referencias oficiales:
- https://developers.google.com/maps/documentation/places/android-sdk/get-api-key
- https://developers.google.com/maps/documentation/places/android-sdk/place-autocomplete
- https://developers.google.com/maps/documentation/places/android-sdk/policies

## Selección manual en Google Maps

El selector de dirección ofrece «Elegir en mapa». No sustituye la pantalla de
Mapa de planes asignada al equipo. Requiere Maps SDK for Android habilitado en
Novatech, la clave Android autorizada para ese SDK y Places API (New), y la
opción local GOOGLE_MAPS_ENABLED=true en secrets.properties.

El mapa ocupa la altura restante, sin desplazamiento vertical que compita con
sus gestos. Tiene buscador, resultados y «Mi ubicación». Se centra en el punto
previo; si no existe y ya hay permiso, obtiene GPS. Sin permiso no lo solicita
automáticamente. Lima es solo la vista inicial de respaldo, indicada como tal.
Al tocar un pin consulta Geocoder de Android, con cancelación/debounce, y muestra
la calle encontrada conservando las coordenadas exactas tocadas. Si no hay calle
o número, permite completar la referencia sin inventarlos. Se confirma una sola
vez desde el mapa. No utiliza Routes, Street View ni Geocoding API de pago.
Places solo se consulta al escribir/elegir resultados en contextos habilitados.
Se conserva la atribución del SDK. No se registra la clave.

La restricción de depuración no cubre automáticamente una futura firma de
producción; habrá que agregar su SHA-1 correspondiente al distribuir la app.
Las pruebas MapPointPickerTest usan una superficie inyectada sin consumir APIs;
la carga real del mapa necesita una comprobación con la app instalada y red.
