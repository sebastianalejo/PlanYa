# Entrega del equipo: cuatro pantallas de PlanYa

Base: rama `steve`, Kotlin/Jetpack Compose, arquitectura por capas y AWS existente.
Fecha: 5 de noviembre de 2026. Responsables sin nombre hasta repartir el trabajo.
Estas historias son trabajo pendiente, no funcionalidades ya entregadas.

| Responsable | Historia principal | Estado inicial | Rama propuesta |
| --- | --- | --- | --- |
| Compañero 1 | [US-07 Mapa](US-07-MAPA.md) | Selector de ubicación existe; mapa de planes no. | feature/mapa |
| Compañero 2 | [US-08 Chat temporal](US-08-CHAT.md) | No hay pantalla ni API de mensajes. | feature/chat |
| Compañero 3 | [US-09 Beneficios](US-09-BENEFICIOS.md) | No hay pantalla ni API de cupones. | feature/beneficios |
| Compañero 4 | [US-10 Perfil](US-10-PERFIL.md) | Pantalla, edición, avatar, intereses y sesión ya funcionan. Ampliar, no rehacer. | feature/perfil |

## Base que no se debe romper

Splash, Google/correo, registro/recuperación, intereses, Inicio, Crear/Editar y
Detalle están aceptados en su alcance actual. Hay galería de hasta cuatro fotos,
participación/salida, cancelación, direcciones y enlaces para llegar/compartir.
La tabla general está en [AVANCE_PANTALLAS](../AVANCE_PANTALLAS.md).

Reutilizar:

- `presentation/screens/<modulo>`: Screen y ViewModel; estado de UI explícito.
- `domain/model`, `domain/repository`, `domain/usecase/<modulo>`: modelos y contratos.
- `data/remote/api`, `data/remote/dto`, `data/mapper`, `data/repository`: transporte.
- `di/NetworkModule.kt`, `di/RepositoryModule.kt`: construir dependencias, sin URLs
  repetidas en cada pantalla. Perfil actualmente está agrupado en `data/profile`;
  no moverlo por estética si eso aumenta conflictos.
- `AuthRepository.token(expectedUserId)`: autenticación existente. No fabricar
  usuarios, pegar tokens ni guardar contraseñas. El backend obtiene identidad del JWT.
- `Plan`, `PlanCategory`, `GeoPoint`, `PlanPage`, `PlanRepository` existentes.
- Tema y fuentes locales: índigo #3F51B5, naranja #D84315, marrón #8F4700,
  fondo #F5F5F7, tarjetas blancas. No usar los tonos lila predeterminados de Material.
- Controles de al menos 48 dp, texto ampliado, teclado, permisos denegados,
  errores/reintento, vacío y carga. Sin datos ficticios en la entrega real.

## Contratos y navegación

Ya existen GET `/planes`, GET `/planes/{id}`, escrituras protegidas de planes,
POST `/fotos/subida`, GET/PUT `/perfil` y POST `/perfil/foto`.
Para el contrato exacto revisar las interfaces actuales y `backend/lambda_function.py`.
Los endpoints marcados PROPUESTOS en las US necesitan implementación, permisos,
autorizador y despliegue: no basta con agregar una llamada Retrofit.

La barra actual es Inicio / Mapa / Crear / Perfil. Integración propuesta:
Mapa se conecta a su opción existente; Chat desde Detalle y «Mis conversaciones»
en Perfil; Beneficios desde Perfil. No añadir seis botones a la barra ni cambiar
la Home sin acordarlo. Un integrador hace los cambios compartidos en
`AppNavigation.kt`, `AppBottomBar.kt` y los módulos DI; cada compañero entrega
sus callbacks/rutas esperadas en su PR.

No editar simultáneamente la misma Lambda gigante. Entregar nuevos manejadores
por módulo y pruebas, manteniendo `lambda_function.lambda_handler` como entrada;
el integrador agrega el despacho. Nada se despliega automáticamente por un push.

## Sprint propuesto: entrega funcional de cuatro módulos

Duración de referencia: 10 días hábiles, ajustable después de estimar en equipo.
Las US principales son amplias: dividir sus tareas backend/UI/pruebas en subtareas.
No prometer todo el sistema de confianza en una tarde.

1. Días 1–2: confirmar contratos, navegación, permisos del laboratorio, modelos de
   datos y decisiones pendientes de Chat/Beneficios/Perfil. Aprobar mockups.
2. Días 3–6: implementar UI y lógica por rama, APIs y pruebas aisladas. Mapa puede
   avanzar con la lectura de planes existente. Perfil reutiliza lo ya terminado.
3. Días 7–8: integración en AWS, casos entre dos cuentas y conflictos. Desplegar
   por responsable autorizado; no mezclar configuración con secretos en Git.
4. Días 9–10: revisión cruzada, accesibilidad, regresión de las seis pantallas y PR.

Dependencias: Detalle habilita la entrada a Chat; Chat valida participantes en el
backend. Beneficios debe acordar cómo se acredita asistencia (unirse NO prueba
asistencia). Perfil necesita datos reales de estadísticas/confianza, no números fijos.

## Definición de terminado común

- Compila y cumple los criterios Dado/Cuando/Entonces de su historia.
- Botones conectados a acciones reales, con validación/errores y sin dobles envíos.
- Pruebas unitarias y UI relevantes; autorización comprobada en servidor.
- Sin claves/tokens/logs privados en Git, APKs ni datos de demo presentados como reales.
- PR con alcance, capturas, contrato API, instrucciones AWS y evidencia de pruebas.
- Regresión del acceso, publicación, edición, galería, inscripción y cierre de sesión.
- No usar `connectedDebugAndroidTest` con el teléfono principal: en este entorno
  puede desinstalar la app. Compilar pruebas, instalar con `adb install -r`, ejecutar
  `am instrument` y desinstalar SOLO `com.example.planya.test` al acabar.
- Revisar consumo/permiso antes de nuevos recursos. No abrir S3, no desactivar JWT,
  no activar servicios de pago para sortear una limitación del laboratorio.

## Git

Todos parten del commit de entrega que se subirá a `origin/steve`. Crear ramas
propias desde esa base; no borrar ni sobrescribir trabajo local para actualizar.
Hacer PR hacia `steve`, sin force push. Revisar el PR de un compañero antes de fusionar.
El integrador acuerda posteriormente el PR de `steve` a la rama principal del equipo.

Antes de empezar, instalar Android Studio/SDK y usar su JBR. `local.properties`
contiene la ruta local del SDK y no se comparte. Para mapas, cada desarrollador
configura su `secrets.properties` privado: GOOGLE_PLACES_API_KEY,
GOOGLE_PLACES_ENABLED y GOOGLE_MAPS_ENABLED. Nunca pegar la clave en un issue.
Si la clave Android está restringida, el administrador debe autorizar el paquete
`com.example.planya` y la huella SHA-1 de ese desarrollador. No quitar restricciones.
Los IDs públicos de Cognito ya están en AuthConfig; el secreto OAuth Google solo
se administra en Google/Cognito, nunca dentro de Android.
