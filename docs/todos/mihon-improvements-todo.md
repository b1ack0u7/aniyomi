# Mejoras de Mihon — investigación y trabajo pendiente

Fecha de revisión: **2026-10-19**. Este documento conserva la investigación para
retomarla; las casillas representan trabajo pendiente, no cambios implementados.

## Punto de partida

- Fork: `dev`, HEAD `ba6adedacd8bdd047bccccd369e650f1f9eecb40`.
- Upstream: `mihon/main`, `deeb9b2584b6e246135cbd31da49a39e6b6e1e52`,
  confirmado mediante `git ls-remote --symref mihon HEAD` y `git fetch mihon`.
- Estable consultada: [Mihon 0.21.2](https://mihon.app/changelogs/v0.21.2),
  publicada el 13 de octubre de 2026. `main` incluye trabajo posterior y experimental.
- Último merge general identificado: `3ecca45bb`, del 30 de mayo de 2025.
  Hay adaptaciones posteriores independientes; esa fecha no describe por sí sola
  cuánto falta integrar.
- El checkout contenía cambios preparados de búsqueda global, migración, filtros,
  paginación y sugerencias, además de documentación. Se revisaron como parte del
  estado actual y no se modificaron durante la investigación.

Se compararon historial, parches, catálogos y código local. No se compilaron APKs,
no se ejecutaron tests y no se reprodujeron los fallos en dispositivo. Encontrar
el patrón anterior en el código identifica un candidato; no demuestra por sí solo
que el fallo ocurra con todas las extensiones o dispositivos.

Las versiones y migraciones técnicas se mantienen en
[Dependency updates](dependency-updates-todo.md). Los pendientes propios del
reproductor permanecen en [Video player](video-player-todo.md).

## 1. Estabilidad — primera tanda

Cada cambio necesita adaptación al código actual, pruebas enfocadas y revisión
independiente de anime y manga. Evitar un cherry-pick masivo.

- [ ] **Coordinar desafíos Cloudflare concurrentes.**
  [5a5ff22c3](https://github.com/mihonapp/mihon/commit/5a5ff22c3).
  Evita que muchas solicitudes al mismo sitio abran WebViews simultáneamente,
  con ralentizaciones y agotamiento de memoria. El interceptor local no tiene
  esa coordinación. Revisar limpieza, cancelación y compatibilidad con el pacing
  y los cooldowns propios. Afecta la red compartida por ambas pilas.
- [ ] **Resolver desafíos de solicitudes distintas de GET desde la raíz del sitio.**
  [7ae858e60](https://github.com/mihonapp/mihon/commit/7ae858e60).
  Incluye el fallo de login de Kitsu. El interceptor local todavía carga la URL
  original. Verificar que se conserve la solicitud original al reintentar.
- [ ] **Filtrar cabeceras inseguras de WebView, incluido `Origin`, y limitar las
  cabeceras de la fuente al host correspondiente.**
  [5f63c983d](https://github.com/mihonapp/mihon/commit/5f63c983d),
  [a01867dc3](https://github.com/mihonapp/mihon/commit/a01867dc3).
  Las rutas de `WebViewActivity` y `WebViewScreenModel` construyen mapas de cabeceras
  directamente; `WebViewScreenContent` no tiene la restricción de host upstream.
  Cubrir fuentes de anime y manga, redirecciones y navegación externa.
- [ ] **Manejar la caída del proceso renderer de WebView.**
  [649fc83ff](https://github.com/mihonapp/mihon/commit/649fc83ff).
  No se encontró `onRenderProcessGone` en las rutas locales revisadas. Adaptar
  tanto la WebView visible como la de Cloudflare y liberar correctamente sus recursos.
- [ ] **Tolerar filtros inválidos de extensiones.**
  [c9e1b6e96](https://github.com/mihonapp/mihon/commit/c9e1b6e96).
  `SourceFilterAnimeDialog` y `SourceFilterMangaDialog` aún lanzan excepción para
  un TriState desconocido; `SettingsItems` accede a `options[selectedIndex]`.
  Completar sin sobrescribir los cambios preparados de filtros y selección inicial.
- [ ] **Cancelar únicamente la instalación solicitada.**
  [ae749bb4f](https://github.com/mihonapp/mihon/commit/ae749bb4f).
  El patrón local de `InstallerManga.cancelQueue` permite seleccionar la instalación
  activa aunque no coincida el ID. Revisar también `InstallerAnime`.
  Evaluar en la misma área las correcciones
  [07b9fb695](https://github.com/mihonapp/mihon/commit/07b9fb695)
  y [2df48e19a](https://github.com/mihonapp/mihon/commit/2df48e19a): cancelación
  fuera del hilo principal y ausencia de actividad capaz de confirmar la instalación.
  Estas dos últimas necesitan comparación detallada antes de afirmar aplicabilidad.
- [ ] **Evitar desplazar la fecha inicial de los trackers por zona horaria.**
  [ee5939454](https://github.com/mihonapp/mihon/commit/ee5939454).
  `AddMangaTracks` y `AddAnimeTracks` conservan la conversión adicional afectada.
  Probar fechas cercanas a medianoche y zonas horarias positivas y negativas.
- [ ] **Corregir posición y páginas divididas del lector paginado.**
  [51b61a36e](https://github.com/mihonapp/mihon/commit/51b61a36e),
  [3156b4a1c](https://github.com/mihonapp/mihon/commit/3156b4a1c).
  `PagerViewerAdapter` conserva el mapa de páginas preprocesadas por índice sin
  identidad de capítulo y no tiene el reajuste nuevo de posición. Validar lectura
  RTL, carga de capítulos vecinos y división de páginas anchas. Solo manga:
  el reproductor de anime no comparte este adaptador.

## 2. Rendimiento y funcionalidades — segunda tanda

- [ ] **Cargar trackers de biblioteca solo cuando los filtros u orden los necesitan.**
  [940272f75](https://github.com/mihonapp/mihon/commit/940272f75).
  Ambos `LibraryScreenModel` locales se suscriben siempre a todos los tracks.
  Evaluar también reutilizar búsquedas de fuentes por actualización, como en
  [f027de8c2](https://github.com/mihonapp/mihon/commit/f027de8c2), sin asumir
  que todas las llamadas duplicadas de upstream existan aquí.
- [ ] **Consultar estados de descarga por lote en listas de capítulos/episodios.**
  [fb8e640d6](https://github.com/mihonapp/mihon/commit/fb8e640d6).
  Candidato para reducir trabajo por fila en detalles y actualizaciones; falta
  comparar completamente con las optimizaciones de descarga ya hechas en el fork.
- [ ] **Carga paralela y precarga del lector, con control por Wi-Fi.**
  [PR #2986](https://github.com/mihonapp/mihon/pull/2986).
  `HttpPageLoader` local mantiene `preloadSize = 4`. Adaptar límites, cancelación
  y coordinación por fuente; medir uso de memoria, datos y tiempo hasta la página.
  Solo manga; no trasladar esa política al streaming de anime automáticamente.
- [ ] **Búsqueda avanzada de biblioteca.**
  [PR #3554](https://github.com/mihonapp/mihon/pull/3554).
  Operadores `&&`, `||`, negación, paréntesis, campos y comparaciones, por ejemplo
  `genre:action && unread:>0`. El matching local sigue siendo simple.
  Adaptar campos y vocabulario a episodios/vistos en anime. Es distinta de la
  búsqueda global de extensiones actualmente preparada.
- [ ] **Carga de extensiones en segundo plano y espera explícita de inicialización.**
  [PR #3788](https://github.com/mihonapp/mihon/pull/3788),
  [PR #3841](https://github.com/mihonapp/mihon/pull/3841).
  Los managers locales inicializan extensiones sincrónicamente. Portar juntos
  carga y espera para no mostrar fuentes o búsquedas vacías al arrancar.
- [ ] **Diagnóstico de extensiones que no cargan y cambios sin reiniciar.**
  [PR #3953](https://github.com/mihonapp/mihon/pull/3953),
  [PR #3954](https://github.com/mihonapp/mihon/pull/3954).
  Evaluar errores visibles, stack trace copiable y posibilidad de actualizar
  extensiones fallidas. El fork ya muestra algunos errores de incompatibilidad
  al consultar fuentes; no equivale a este diagnóstico del gestor.
- [ ] **Vincular instalación y actualización con la firma del repositorio.**
  [093841105](https://github.com/mihonapp/mihon/commit/093841105).
  Requiere revisar modelos de repositorios, APK descargado y selección de updates
  en ambas pilas. Comparación preliminar; no tratado como parche pequeño.
- [ ] **Mejoras e integraciones de trackers.**
  Evaluar búsqueda `id:`, refresco de perfil, sistema de puntuación Kitsu y
  servicios como Comick, MangaBaka y Hikka según necesidad. Consultar las
  [notas oficiales](https://mihon.app/changelogs/v0.21.2) y comparar cada tracker
  local antes de marcarlo como ausente. Anime depende del soporte real del servicio.

## 3. Proyectos independientes — evaluar después

- [ ] **Base de datos y consultas de bibliotecas grandes:**
  [PR #3805](https://github.com/mihonapp/mihon/pull/3805) y
  [PR #4022](https://github.com/mihonapp/mihon/pull/4022).
  Mihon reestructuró esquema y fusionó duplicados. Adaptar dos bases y dos
  secuencias de migraciones; incluir las correcciones posteriores a esa migración.
  No importar reparaciones de problemas introducidos por ese esquema si el esquema
  nuevo no se adopta.
- [ ] **Backups por streaming:**
  [771ba33a3](https://github.com/mihonapp/mihon/commit/771ba33a3).
  Menor memoria y trabajo por bloques. Conservar campos anime, ProtoNumbers,
  seis tipos de preferencias y restauración desde versiones nuevas/antiguas.
  Revisar también la política upstream de preservar progreso y detalles existentes;
  el fork ya tiene parte de esa lógica y requiere un análisis de diferencias.
- [ ] **Lector de alta calidad, decoder y páginas muy altas:**
  [PR #3388](https://github.com/mihonapp/mihon/pull/3388),
  [65d7110e1](https://github.com/mihonapp/mihon/commit/65d7110e1),
  [b5856135c](https://github.com/mihonapp/mihon/commit/b5856135c).
  Separar la corrección del lector existente del renderer experimental. Upstream
  usa otro decoder; no se resuelve cambiando la revisión de la dependencia antigua.
  Probar GPU, memoria, imágenes animadas, orientación, color y Android antiguos.

## Trabajo que no debe repetirse

- Detección Cloudflare por `cf-mitigated`: ya adaptada, commit local `4a88ce862`.
- Sincronización transaccional de capítulos/episodios: `1e3ac052d`.
- Pacing, reintentos y recuperación de descargas/actualizaciones: mejoras propias
  recientes; conservarlas al adaptar cambios upstream.
- Coordinación, cancelación y resultados concurrentes de búsqueda: cubiertos por
  cambios preparados, con referencias a `a4780e4ed` y `4c88f0264` en `CHANGELOG.md`.
- Corrección de expiración OAuth de MAL `1e054ea14`: el código local ya tiene el
  sentido correcto de la comparación, aunque usa otra implementación.
- Dependencias ya alineadas y detalles de versiones: ver el TODO de dependencias.

## Cómo retomar y validar

1. Revisar `git status --short`, instrucciones y cambios preparados antes de editar.
2. Confirmar la rama predeterminada y actualizar `mihon`; este documento es una foto
   de revisión, no una garantía de las versiones futuras.
3. Empezar por la sección 1. Revisar dependencias mínimas de cada parche y subir
   alguna antes solo si el cambio la necesita. No esperar a completar todo este
   documento para abordar actualizaciones pequeñas e independientes.
4. Para cada tarea, registrar archivos, adaptación anime/manga, referencias upstream,
   pruebas y limitaciones; marcarla completada únicamente tras implementar y validar.
5. Ejecutar pruebas del área y formato; para cambios transversales,
   `./gradlew spotlessCheck` y `./gradlew testReleaseUnitTest`, además de compilación
   y pruebas de dispositivo cuando corresponda. Las APIs de extensiones necesitan
   comprobar compatibilidad binaria, no solo compilación de la app.
6. Mantener documentación y changelog al día. Dejar cambios sin stage ni commit
   salvo petición explícita.
