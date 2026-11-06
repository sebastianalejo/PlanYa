# US-07 — Explorar planes en el mapa

Responsable: Compañero 1. Rama propuesta: `feature/mapa`. Prioridad: alta.

**Como** persona que busca un encuentro cercano,
**quiero** ver los planes activos en un mapa, seleccionar uno y consultar su ficha,
**para** decidir rápidamente dónde ir y abrir su detalle sin perder mi búsqueda.

## Alcance y estado inicial

Existe Maps Compose, permiso de ubicación, selector de punto y modelos de planes.
El botón Mapa todavía muestra un aviso; eso no es esta pantalla. Crear mapa de
planes reales con ficha inferior. No rehacer la selección de dirección ni agregar
Routes, Street View, navegación giro a giro o nuevos proveedores de mapas.

## Botones y flujo

Inicio → Mapa → pin → ficha inferior → Ver detalles → Detalle existente.
Acciones: Mi ubicación, filtros por categoría, Actualizar, cerrar ficha, Ver detalles,
barra inferior con Mapa seleccionado. Unirse se hace en Detalle, no al tocar un pin.
Si vuelve de Detalle, conservar cámara/filtros dentro de la sesión de navegación.

## Criterios de aceptación

1. **Dado** que hay planes activos con coordenadas, **cuando** abro Mapa,
   **entonces** veo pines de esos planes, no marcadores de demostración.
2. **Dado** que un plan no tiene coordenadas o ya venció, **cuando** se calculan
   los pines, **entonces** no se inventa su ubicación ni se muestra como activo.
3. **Dado** un pin, **cuando** lo toco, **entonces** la ficha muestra categoría,
   título, anfitrión, vencimiento y distancia aproximada si se conoce mi ubicación.
4. **Dado** que seleccioné un plan, **cuando** pulso Ver detalles, **entonces**
   abre el detalle existente con su ID; regresar no crea una segunda Home.
5. **Dado** que niego GPS, **cuando** abro el mapa, **entonces** puedo explorar
   manualmente o usar una zona elegida; no dice «ubicación actual» sobre Lima fija.
6. **Dado** un filtro de categoría, **cuando** lo cambio, **entonces** pines y ficha
   quedan coherentes y puedo volver a Todos. No se cambian mis intereses guardados.
7. **Dado** que varios planes comparten coordenadas, **cuando** selecciono esa
   ubicación, **entonces** puedo elegir entre ellos mediante una lista/ficha múltiple;
   no se ocultan permanentemente detrás del mismo pin.
8. **Dado** un fallo de red o cuota, **cuando** actualizo, **entonces** aparece un
   mensaje con reintento y se distingue información previa de una carga exitosa.
9. **Dado** un plan que vence mientras el mapa está visible, **cuando** se actualiza
   el reloj o regreso a la app, **entonces** se retira su pin y se cierra su ficha.
10. **Dado** un invitado, **cuando** abre un detalle desde el mapa, **entonces**
    puede leerlo; al intentar unirse se reutiliza el acceso actual.
11. **Dado** texto grande o una pantalla pequeña, **cuando** uso la ficha, **entonces**
    Ver detalles y cerrar siguen accesibles; la ficha no tapa la navegación.

## Tareas técnicas

- Crear `presentation/screens/map/MapScreen.kt`, `MapViewModel.kt`, `MapUiState.kt`.
- Reutilizar `PlanRepository`, `GetPlansUseCase`, `GeoPoint` y categorías existentes.
- Añadir casos de uso/filtrado de pines sin duplicar el modelo Plan.
- Conectar la opción MAPA en navegación, cámara guardada y estado de la ficha.
- Mantener atribución Google visible. Pedir GPS solo por acción explícita y no
  enviar la ubicación personal al servidor para esta entrega.
- Consumir todas las páginas solicitadas con cursor y deduplicar por ID. Si solo
  hay páginas parciales, indicar «planes cargados»; no fingir búsqueda geográfica
  global. No hacer Scan completo al mover el mapa ni pedir en cada recomposición.

API existente: GET `/planes?nextKey=...` y GET `/planes/{id}`. No exige nuevas rutas
para el primer alcance. Una consulta por radio/bounding box en servidor es una
ampliación separada, con índice y contrato revisados, no un parámetro inventado.

## Pruebas y entrega

Pines activos/sin coordenadas, filtros, superposición, permiso denegado, error,
vencimiento, paginación sin duplicados y navegación pin→detalle→mapa. Una prueba
con datos aislados no acredita GPS real: entregar también captura en dispositivo
con ubicación y evidencia de lectura AWS. No introducir cobros ni claves en PR.

Terminado cuando cumple estos criterios y la definición común del índice.
