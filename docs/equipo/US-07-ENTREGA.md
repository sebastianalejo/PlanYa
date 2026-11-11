# US-07 Mapa: entrega

Rama: `feature/mapa` (creada desde `steve`). Responsable: Alfonso López.

## Archivos

| Archivo | Qué hace |
| --- | --- |
| `domain/usecase/plan/MapPins.kt` | Arma los pines: quita planes vencidos y sin coordenadas, aplica el filtro de categoría, deduplica por ID y agrupa los planes que comparten punto. |
| `presentation/screens/map/MapUiState.kt` | Estado del mapa: planes, carga, error, cursor, filtro, pin seleccionado, cámara y GPS. |
| `presentation/screens/map/MapViewModel.kt` | Consulta `GET /planes` con `nextKey`, reintento, filtro, cámara guardada y Mi ubicación. |
| `presentation/screens/map/MapScreen.kt` | Pantalla: chips de categoría, Actualizar, mapa con pines por color, Mi ubicación, ficha inferior, lista si hay varios planes en el mismo punto, Ver detalles. |
| `presentation/navigation/AppNavigation.kt` | Ruta `mapa`; la opción Mapa de la barra ya no muestra el aviso. Unirse/salir/editar/cancelar en Detalle también actualiza el mapa. |
| `presentation/screens/home/HomeScreen.kt` | Solo `CategoryFilter` pasa de `private` a `internal` para reutilizar el mismo chip. |
| `test/.../data/MapPinsTest.kt` | 8 pruebas unitarias de los pines. |

No se tocó la Lambda ni AWS: se usa la API existente.

## Criterios de aceptación

| # | Criterio | Cómo se cumple |
| --- | --- | --- |
| 1 | Pines de planes reales | Los pines salen de `GET /planes`; no hay marcadores de demostración. |
| 2 | Sin coordenadas o vencido | `buildMapPins` los excluye; el encabezado indica cuántos planes no tienen ubicación. |
| 3 | Ficha del pin | Categoría, título, anfitrión, punto de encuentro, vencimiento y distancia en línea recta (o "desconocida"). |
| 4 | Ver detalles | Navega a `plan/{id}` con el ID del plan; atrás regresa al mapa, no crea otra Home. |
| 5 | GPS negado | Se puede mover el mapa; aparece un aviso. Sin ubicación, la vista se rotula "Vista inicial de Lima, no tu ubicación". |
| 6 | Filtro | Chips Todos/Café/…; al cambiar se cierra la ficha. No modifica los intereses guardados. |
| 7 | Mismo punto | Un solo pin con "N planes en este punto" y una lista para elegir. |
| 8 | Error de red | Sin datos: tarjeta con Reintentar. Con datos previos: banner "se muestran los planes cargados antes". |
| 9 | Vence con el mapa abierto | Los pines se recalculan con el reloj de 15 s; si el plan seleccionado vence, la ficha se cierra. |
| 10 | Invitado | El detalle se abre sin cuenta; Unirme usa el acceso existente. |
| 11 | Texto grande | La ficha tiene scroll y altura máxima; los botones miden al menos 48 dp; la barra inferior queda visible. |

Cámara, filtro y ficha se conservan al volver del detalle porque `MapViewModel` vive a nivel de actividad.

## Pendiente o limitaciones

**Validación posterior del integrador (10/11/2026):** la integración en `steve`
compila y pasa 77 pruebas JVM, 90 Python y 47 instrumentadas (incluidas 12 nuevas
de Mapa). Hallazgos, correcciones y límites de esta evidencia en
[Revisión gradual de merges](REVISION_MERGES_2026-11-10.md).
Las advertencias de falta de compilación que siguen corresponden a la entrega
original; mosaicos/GPS reales todavía requieren comprobación separada.

- No se compiló en este entorno. Hay que correr `gradlew.bat :app:assembleDebug :app:testDebugUnitTest` en Android Studio.
- Necesita `GOOGLE_MAPS_ENABLED=true` y la clave en `secrets.properties` (fuera de Git). Sin eso, la pantalla avisa que el mapa no está configurado.
- Solo muestra los planes cargados. "Cargar más planes" pide la siguiente página; no hay búsqueda geográfica por radio en el servidor (sería otra ampliación).
- Falta tomar las capturas en el dispositivo: mapa con pines, ficha, lista de varios planes, permiso negado y error sin Internet.
