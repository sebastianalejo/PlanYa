# Entrega en GitHub

Repositorio: `https://github.com/sebastianalejo/PlanYa.git`.
Rama de entrega autorizada por Steve: `steve`. No hacer force push ni fusionar a
la rama principal automáticamente. La subida incluye Android, backend, recursos,
pruebas y documentación; NO despliega Lambda, Cognito, API Gateway ni S3.

## Revisión de seguridad de esta entrega

- `secrets.properties`, `auth.secrets.properties`, `local.properties`, firmas,
  google-services.json y salidas de compilación están ignorados por Git.
- `.env` y variantes locales también se excluyen; se permiten ejemplos sin secretos.
- No se detectaron patrones comunes de API key de Google, secreto OAuth, access
  key AWS o clave privada en el árbol candidato a subir durante esta revisión.
  Se revisaron rutas de coincidencia, sin imprimir valores de secretos.
- Esto no es una auditoría de todo el historial, ni demuestra que ninguna cadena
  arbitraria sea sensible. Revisar el diff/PR y cualquier alerta de secret scanning.
- No hay que quitar del código los IDs públicos de Cognito ni la URL pública de
  la API para sustituirlos por claves AWS dentro del APK: Android no necesita IAM keys.
- Claves/secreto OAuth compartidos anteriormente por chat deben rotarse desde sus
  consolas por el propietario, aunque no entren a Git. La rotación requiere actualizar
  sus consumidores de forma coordinada; no se hizo automáticamente en esta entrega.
- Capturas y APKs de `app/build` no se suben. Las imágenes/fuentes de interfaz sí;
  licencias/procedencia quedan en assets y documentación.

## Cómo empezar cada compañero

1. Obtener la rama `steve` actualizada desde origin sin descartar cambios locales.
2. Crear su rama `feature/mapa`, `feature/chat`, `feature/beneficios` o `feature/perfil`
   desde ese commit. No empezar desde una copia antigua de main.
3. Leer la US asignada y confirmar contratos/decisiones antes de crear recursos AWS.
4. Configurar su SDK y, para mapas, clave local restringida a su certificado Android.
5. Hacer PR hacia `steve`, con pruebas y capturas. El integrador revisa navegación,
   DI y Lambda antes de fusionar; evitar editar esos tres puntos a la vez.

Comandos orientativos SOLO con el árbol local sin cambios pendientes:

```powershell
git fetch origin
git switch steve
git pull --ff-only origin steve
git switch -c feature/mapa
```

Cambiar la última rama por la tarea asignada. Si la rama ya existe, usarla; si
hay cambios locales o divergencia, revisar antes de continuar. Nunca ejecutar
reset --hard, clean -fd o push --force para «solucionar» conflictos.

## Verificación de integración

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
python -m unittest discover -s backend/tests -v
git diff --check
```

Los tests Android instrumentados se ejecutan por el procedimiento seguro del
índice del equipo; no desinstalar la app principal del teléfono de Steve.
La cuenta AWS de estudiante debe estar activa para probar operaciones reales.
