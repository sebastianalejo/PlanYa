# PlanYa

Aplicación Android con Kotlin y Jetpack Compose para planes sociales cercanos.

## Estado actual y reparto del equipo

Las seis pantallas de Steve están aceptadas en su alcance actual: Splash,
Acceso/Registro Google/correo, Intereses, Inicio, Crear/Editar y Detalle. El usuario
confirmó sus validaciones finales. Perfil tiene una base funcional que se amplía,
no se rehace. Mapa completo, Chat temporal y Beneficios quedan para el equipo.

- [Cuatro historias de usuario y sprint](docs/equipo/README.md).
- [Tabla actual de pantallas](docs/AVANCE_PANTALLAS.md).
- [Cierre y evidencia de las seis](docs/CIERRE_SEIS_PANTALLAS.md).
- [Preparación de GitHub y seguridad](docs/equipo/GITHUB.md).

Los apartados de avances iniciales que siguen son históricos: sus frases sobre
Cognito/rutas pendientes no reemplazan el estado actual de los documentos anteriores.
Google, correo, publicación, participación y Perfil ya fueron configurados y
probados por el usuario. AuthConfig contiene IDs públicos, no secretos de cliente.

## Primer avance conectado a AWS

La Home consulta la API real:

```text
GET https://g0msb4hkn6.execute-api.us-east-1.amazonaws.com/planes
```

La lista tiene carga, error con reintento, estado vacío, actualización manual y
paginación. La búsqueda y las categorías filtran los planes que ya se cargaron;
se puede pulsar **Cargar más planes** para consultar la siguiente página.

## Diseño y detalle

Se conserva la estructura de la Home de referencia: ubicación y avatar,
búsqueda, categorías horizontales, tarjetas con autor y descripción, y barra
inferior. Los ajustes de tamaño y espaciado se realizan sobre esa estructura.

La paleta sigue la referencia entregada: primario `#3F51B5`, secundario
`#D84315`, terciario `#8F4700` y neutro `#F5F5F7`. Las categorías reutilizan
estos tonos y sus variantes claras. Títulos y cuerpo usan **Plus Jakarta Sans**;
las etiquetas usan **Atkinson Hyperlegible Next**. Las fuentes están incluidas
localmente en `res/font`, por lo que no se descargan al abrir la aplicación.
Las licencias SIL OFL se incluyen en `assets/font_licenses`.

La Home usa tarjetas compactas, tipografía compartida y contenido centrado con
ancho máximo de 680 dp para pantallas grandes. Los botones de tarjetas conservan
al menos 48 dp de alto y la barra inferior reparte sus cuatro opciones por igual.
Tocar una tarjeta o **Ver detalles** abre la pantalla con descripción completa.
La acción para unirse está dentro del detalle: ahora pide sesión y tiene un
contrato de inscripción protegido. Todavía falta desplegar esas escrituras y
configurar Cognito para comprobar una inscripción real.

La Home y el detalle muestran exclusivamente los planes consultados a AWS.
No se incluyen controles de demostración ni planes ficticios locales.

Los registros iniciales pueden no incluir anfitrión, foto, horario o coordenadas.
La interfaz indica los datos pendientes y no inventa distancias ni participantes.
La lectura real está conectada; publicar, editar, cancelar, unirse y salir ya
tienen interfaz y lógica local, pero necesitan activar cuentas/rutas en AWS.

## Ubicación y detalle ampliado

Tocar la ubicación del encabezado permite obtener la posición del dispositivo
con permiso de uso en primer plano, o buscar mediante Teléfono (Geocoder) o
Google Maps (Places New). Google solo se consulta tras elegir ese proveedor.
No se solicita acceso en segundo plano. Las distancias se calculan en línea
recta respecto a las coordenadas del plan; las zonas elegidas manualmente o
las posiciones poco precisas se indican como aproximadas. El nombre de zona
depende del proveedor elegido. La ubicación nativa se conserva cifrada en el
dispositivo. Para Google solo se persiste el placeId: al reabrir, Recuperar mi
dirección consulta explícitamente por ID, sin precargas al abrir la app. La
ubicación personal seleccionada en Inicio no se envía al backend.

El selector se abre como un panel inferior con buscador, acción de GPS y
sugerencias seleccionables. Al escribir al menos tres caracteres, espera 500 ms
antes de consultar con Teléfono (900 ms para Google). Cambiar el texto cancela la consulta anterior; no se elige
un lugar automáticamente. Las respuestas tardías no reemplazan búsquedas nuevas.

La búsqueda local usa el país/ciudad y las coordenadas de la zona seleccionada.
Solicita al proveedor un rectángulo cercano y filtra los resultados por país
y por un radio de 100 km. Se ordenan por distancia. Así, una coincidencia de
otro país no aparece como resultado cercano. Si no hay posición elegida,
se muestra explícitamente «En Perú», región inicial del piloto. Cuando el GPS
o la selección cambia de país, ese contexto también se actualiza.

«Ampliar búsqueda» permite buscar fuera de esa región. Las sugerencias incluyen
ciudad/país cuando el proveedor los devuelve, para distinguir nombres repetidos.
Si no hay coincidencias, se informa y se permite ampliar o editar la búsqueda.
El selector de Inicio no necesita cambios de AWS. Google requiere cuenta de
facturación y usa las cuotas del proyecto Novatech; no se garantiza gasto cero.
La clave se obtiene de secrets.properties, fuera de Git, y nunca se imprime.

El catálogo y la calidad de las coincidencias parciales dependen del
[geocodificador de Android](https://developer.android.com/reference/android/location/Geocoder).
La opción Google añade su propio catálogo, pero tampoco garantiza todas las
direcciones o comercios. No se han hecho consultas reales para verificar la clave.
En Android 13 o posterior el buscador del teléfono usa consultas asíncronas con un tiempo
máximo de espera; las versiones anteriores consultan en un hilo de trabajo.

El detalle sigue la referencia visual: imagen, ficha del anfitrión, categoría,
título y descripción, distancia, vencimiento y asistentes. Admite fotos HTTPS
y un respaldo local para café, identificado como referencial. Véase
`docs/IMAGENES.md` para su procedencia.

Los planes con expiresAt vencido desaparecen del listado; el reloj se actualiza
cada 15 segundos con la app visible. Sin ese campo no se inventa un vencimiento.
El botón Unirme ahora queda deshabilitado al vencer, llenarse el cupo conocido
o faltar horario. Un invitado debe ingresar para inscribirse. El anfitrión ve
Editar/Cancelar; un participante ve Salir. Esas escrituras requieren el despliegue
protegido descrito en docs/AUTENTICACION_AWS.md.

Android intenta consultar `GET /planes/{id}` al abrir el detalle. Si la ruta no
está desplegada o falla, conserva los datos disponibles del listado y muestra
un aviso con reintento. Las instrucciones de despliegue están en backend/README.md.
Esta versión no modifica automáticamente la configuración ni los datos de AWS.

## Las seis pantallas de Steve

Splash → Acceso/Registro → Intereses → Inicio → Crear plan → Detalle están
implementadas en Android. Acceso también incluye confirmación de correo y
recuperación. Explorar sin cuenta mantiene la lectura pública; los borradores
se pueden preparar antes de ingresar y se retoman después. Las preferencias de
intereses se guardan localmente por usuario/invitado.

AuthConfig.kt está sin identificadores reales: hay que crear un User Pool
Cognito y cliente móvil sin secreto, completar esos IDs públicos y proteger las
rutas de escritura con un autorizador JWT. No hay contraseñas ni claves IAM en
el código; los tokens de sesión se cifran con Android Keystore y se excluyen
de copias de seguridad. Continuar con Google ya tiene flujo Android AppAuth +
Cognito; el usuario creó grupo/cliente/proveedor y falta verificar el cliente
OAuth e ingresar realmente. Apple y el perfil remoto siguen pendientes.

El selector de ubicación conserva la dirección completa, la calle y el número
cuando los devuelve Geocoder. GPS o una sugerencia abren una confirmación;
si solo se conoce una zona se indica expresamente. Permite cambiar/completar la
búsqueda y añadir una referencia. La selección nunca inventa una puerta exacta.

La tabla de pantallas, cada botón y sus flujos se encuentra en
docs/AVANCE_PANTALLAS.md. Mapa, Chat, Beneficios y Perfil quedan para el equipo.

## Organización

```text
core/network             URL de la API
data/remote/api          Contrato Retrofit
data/remote/dto          JSON de AWS
data/mapper              Conversión de DTO a modelo
data/repository          Implementación de acceso a datos
domain/model             Plan, categoría y página de resultados
domain/repository        Contrato de acceso a planes
domain/usecase/plan      Consulta de planes
di                       Construcción compartida de las dependencias
presentation/components Tarjetas, barra inferior y elementos visuales compartidos
presentation/navigation Navegación Inicio → Detalle
presentation/screens/home Pantalla, estado y ViewModel
presentation/screens/plandetail Descripción completa y estado de inscripción
ui/theme                 Colores y tema
backend                  Código Python y pruebas de Lambda
```

Las dependencias se construyen en `di` y el ViewModel se obtiene con una Factory
gestionada por Android. Retrofit ejecuta la petición suspendida y Compose observa
el estado con `collectAsStateWithLifecycle`.

## Abrir y probar

1. Abrir esta carpeta en Android Studio.
2. Sincronizar Gradle después de los cambios en dependencias.
3. Seleccionar `app` y un dispositivo con Internet.
4. Pulsar Run. La Home debe mostrar los registros de `PlanYaPlanes`.
5. Para volver a consultar los registros, pulsar **Actualizar**.

La URL está en `core/network/ApiConfig.kt` y la ruta en `PlanApiService.kt`.
No se necesitan credenciales AWS en Android.

## Comprobaciones

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest
python -m unittest discover -s backend/tests -v
```

Las pruebas del repositorio verifican el JSON de AWS, la paginación, datos
opcionales, listas vacías y errores de contrato sin llamar a AWS.
