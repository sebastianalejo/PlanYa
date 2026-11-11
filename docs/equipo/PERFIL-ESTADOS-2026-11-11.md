# Perfil: actividad y valoraciones

Mejora de presentación, sin modificar Lambda, permisos ni el contrato de la API.

- Carga inicial y actualización tienen mensajes distintos; un fallo de red no
  se presenta como cero valoraciones.
- Los errores conservan el reintento y explican cuándo se muestran datos previos.
- Sin valoraciones no significa una nota negativa. Se informa que la creación
  de opiniones todavía no está habilitada; no se inventan estrellas ni promedios.
- La actividad distingue unirse de la asistencia acreditada por un operador.
- Los indicadores se apilan con poco ancho o letra grande.

La creación de valoraciones, sus reglas de elegibilidad y los permisos de
verificación siguen pendientes. No se declara esa funcionalidad terminada.

Verificación: assembleDebug/assembleDebugAndroidTest correctos, 98 pruebas JVM
sin errores; 13 pruebas ProfileScreenTest y 9 ProfileEditingTest correctas en
Motorola. La primera pasada detectó una prueba que buscaba una opción fuera del
área compuesta de LazyColumn; se corrigió su desplazamiento por lista y se
repitieron las 13 pruebas visuales completas (20,714 s). Datos de prueba aislados,
sin modificar perfiles o valoraciones reales ni cerrar la sesión del usuario.
