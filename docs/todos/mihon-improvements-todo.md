# Mejoras de Mihon — investigación y trabajo pendiente

Fecha de investigación inicial: **2026-10-19**. Adaptación de las tandas 1 y 2:
**2026-10-21**, con continuación de trackers existentes el **2026-10-22**.
Las casillas reflejan el estado de implementación y validación;
los límites de las pruebas se registran junto a cada tanda.

## Punto de partida de la investigación

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

En la investigación inicial se compararon historial, parches, catálogos y código local. No se compilaron APKs,
no se ejecutaron tests y no se reprodujeron los fallos en dispositivo. Encontrar
el patrón anterior en el código identifica un candidato; no demuestra por sí solo
que el fallo ocurra con todas las extensiones o dispositivos.

Las versiones y migraciones técnicas se mantienen en
[Dependency updates](dependency-updates-todo.md). Los pendientes propios del
reproductor permanecen en [Video player](video-player-todo.md).

## 1. Estabilidad — primera tanda

Los cambios siguientes se adaptaron al código del fork y se revisaron por separado
en anime y manga. El alcance de las pruebas figura al final de esta sección.

- [x] **Coordinar desafíos Cloudflare concurrentes.**
  [5a5ff22c3](https://github.com/mihonapp/mihon/commit/5a5ff22c3).
  Un desafío por host para solicitudes en vuelo, con cancelación, limpieza y
  conservación de los cooldowns propios. Red compartida por ambas pilas.
- [x] **Resolver desafíos de solicitudes distintas de GET desde la raíz del sitio.**
  [7ae858e60](https://github.com/mihonapp/mihon/commit/7ae858e60).
  WebView visita la raíz; el reintento conserva método, cuerpo y cabeceras originales.
  La regresión de login de Kitsu se cubre por esta política, sin iniciar sesión real.
- [x] **Filtrar cabeceras inseguras de WebView, incluido `Origin`, y limitar las
  cabeceras de la fuente al host correspondiente.**
  [5f63c983d](https://github.com/mihonapp/mihon/commit/5f63c983d),
  [a01867dc3](https://github.com/mihonapp/mihon/commit/a01867dc3).
  Ambas rutas visibles usan el filtro común, seleccionan la pila correcta y
  filtran las cabeceras en cada salto de redirección de OkHttp.
- [x] **Manejar la caída del proceso renderer de WebView.**
  [649fc83ff](https://github.com/mihonapp/mihon/commit/649fc83ff).
  La WebView visible informa y cierra la página. La de Cloudflare libera la espera
  con error; ambas destruyen los recursos sin operar sobre el renderer muerto.
- [x] **Tolerar filtros inválidos de extensiones.**
  [c9e1b6e96](https://github.com/mihonapp/mihon/commit/c9e1b6e96).
  TriState desconocido se muestra desactivado; una selección fuera de rango se
  muestra vacía. Anime y manga conservan los ajustes previos de filtros y selección.
- [x] **Cancelar únicamente la instalación solicitada.**
  [ae749bb4f](https://github.com/mihonapp/mihon/commit/ae749bb4f),
  [07b9fb695](https://github.com/mihonapp/mihon/commit/07b9fb695),
  [2df48e19a](https://github.com/mihonapp/mihon/commit/2df48e19a).
  Se adaptaron los tres cambios a ambas pilas. También se ignoran resultados de
  sesiones anteriores y se conserva la instalación siguiente si la actual termina
  mientras se procesa su cancelación.
- [x] **Evitar desplazar la fecha inicial de los trackers por zona horaria.**
  [ee5939454](https://github.com/mihonapp/mihon/commit/ee5939454).
  `AddMangaTracks` y `AddAnimeTracks` conservan el instante del historial sin
  convertirlo por segunda vez. Cobertura cerca de medianoche en UTC y zonas ±UTC.
- [x] **Corregir posición y páginas divididas del lector paginado.**
  [51b61a36e](https://github.com/mihonapp/mihon/commit/51b61a36e),
  [3156b4a1c](https://github.com/mihonapp/mihon/commit/3156b4a1c).
  Implementado en `PagerViewerAdapter`: conserva el elemento actual y distingue
  páginas preprocesadas por capítulo e índice. Validado en emulador con RTL/LTR,
  capítulos vecinos de distinta longitud y páginas anchas divididas. Solo manga.

### Adaptación de la primera tanda — 2026-10-21

Se confirmó `main` como rama predeterminada de Mihon y se actualizó `mihon/main`
a `d001e6f210e6a077a30dd9a1392573a3225bba2f`. Se adaptaron los parches indicados,
sin un merge masivo ni cambios en la API pública de extensiones:

- **Cloudflare:** `CloudflareInterceptor`, `CloudflareChallengeCoordinator` y
  `WebViewInterceptor` comparten un desafío por host entre solicitudes en vuelo.
  La coordinación tiene vida limitada, admite cancelación y conserva las excepciones
  que usan los cooldowns propios. Para métodos distintos de GET, WebView visita la
  raíz y OkHttp reintenta la solicitud original con su método, cuerpo y cabeceras.
- **WebView:** `WebViewUtil.toWebViewHeaders` filtra cabeceras incompatibles;
  `WebViewActivity`, `WebViewScreenModel` y `WebViewScreenContent` restringen las
  cabeceras al host de la fuente. Se distingue explícitamente anime de manga,
  incluso si coinciden sus IDs. Ambas rutas manejan la caída del renderer y liberan
  WebView; la visible informa y cierra la página.
- **Filtros:** ambos `SourceFilter*Dialog` muestran un TriState desconocido como
  desactivado. `SettingsItems.SelectItem` tolera una selección fuera de rango.
  Se conservan la separación de filtros aplicados/borrador y los ajustes previos.
- **Instaladores:** ambas pilas comprueban el ID al cancelar, sincronizan las
  operaciones compuestas de cola y manejan la falta de actividad de confirmación.
  `07b9fb695` aplica porque Shizuku puede llamar `continueQueue` desde IO mientras
  la cancelación llega por main. El servicio permanece activo mientras instala,
  aunque no tenga más entradas en cola. Los resultados tardíos se filtran por sesión
  y la cancelación usa compare-and-set para no limpiar una instalación posterior.
- **Trackers:** `AddMangaTracks` y `AddAnimeTracks` conservan el instante del historial
  sin aplicar una segunda conversión de zona horaria. La consulta de historial
  admite inyección para probar ambas pilas cerca de medianoche.
- **Lector:** `PagerViewerAdapter` conserva el elemento actual al actualizar capítulos
  y distingue páginas divididas por ID de capítulo e índice. Conserva las dos páginas
  de precarga vecina propias del fork. La prueba de dispositivo detectó además que
  `ReadingModePage` enlazaba las opciones de dividir/invertir páginas a preferencias
  equivocadas; se corrigieron ambos enlaces y se contrastaron con `mihon/main`.
  Webtoon ya tenía los enlaces correctos. Solo manga; anime no comparte este adaptador.

Validación automatizada final:

- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: correcto;
  456 pruebas unitarias, sin fallos ni pruebas omitidas.
- `python3 -B -m unittest discover -s scripts/tests -p 'test_check_16kb_alignment.py' -v`:
  15 pruebas correctas. No se modificaron dependencias nativas.
- `git diff --check`: correcto. Cambios previos preparados preservados; los de esta
  tanda quedan sin stage ni commit.
- Cobertura nueva: coordinación y cancelación Cloudflare, raíz no GET, cabeceras
  inseguras, redirección HTTP real entre hosts, managers anime/manga con ID idéntico,
  cancelación por ID, finalización concurrente, resultados de sesión tardíos y fechas.

Pruebas de emulador (`emulator-5554`, Android con páginas de 16 KB):

- Durante el primer intento de instalación el emulador pasó a `offline`; el usuario
  lo reinició y las instalaciones posteriores con `adb install -r` fueron correctas.
  No se determinó la causa del bloqueo. Android SDK 36, `getconf PAGE_SIZE=16384`.
- APK final x86_64: SHA-256
  `511ee8c1c2fc6313f0a86ee7691558932b41f786188ae1d5844747d740e7503c`.
- Lector con fixture local de tres capítulos (4/7/3 páginas): reanudación en la
  página intermedia exacta con vecinos cargados; páginas anchas iniciales/finales,
  ambas mitades y cambio al capítulo siguiente y regreso en RTL y LTR, sin cierres
  ni mezcla de páginas. La reanudación inicial se probó antes de corregir la casilla;
  los recorridos con división real se repitieron en el APK final.
- Se comprobó que las casillas de división/inversión escriben sus preferencias
  correctas. Ajustes globales de escala, zoom, pan, división e inversión restaurados.
- Filtros de manga (incluida expansión de géneros/TriState) y anime: apertura sin cierre.
- WebViews de MangaFire y Jkanime: apertura y cierre correctos. En la visible de
  manga se forzó `chrome://crash` mediante DevTools debug: se cerró la página y se
  volvió al catálogo con el mismo PID de app, sin cierre de la app ni del emulador.
  Una nueva WebView abrió correctamente después de la caída.
- Fixture CBZ e historial de sus capítulos eliminados; títulos existentes conservados.
  Se retiró el puerto reenviado de DevTools. App y emulador quedaron operativos.

Límites: no se inició sesión real en Kitsu ni otros trackers, ni se forzó un desafío
Cloudflare de un sitio externo. La falta de pantalla de confirmación y el IPC del
instalador no se reprodujeron en un dispositivo modificado. La cobertura de eventos
abandonados usa el receptor real de la app con mocks del sistema Android. No se forzó
la caída de la WebView oculta de Cloudflare ni se instaló una extensión malformada
para inyectar filtros inválidos en dispositivo.

## 2. Rendimiento y funcionalidades — segunda tanda

- [x] **Cargar trackers de biblioteca solo cuando los filtros u orden los necesitan.**
  [940272f75](https://github.com/mihonapp/mihon/commit/940272f75).
  Suscripción condicionada por filtros/orden en anime y manga, con cancelación
  cuando dejan de necesitarse. Fuentes, nombres e idiomas reutilizados por
  actualización, siguiendo también
  [f027de8c2](https://github.com/mihonapp/mihon/commit/f027de8c2).
- [x] **Consultar estados de descarga por lote en listas de capítulos/episodios.**
  [fb8e640d6](https://github.com/mihonapp/mihon/commit/fb8e640d6).
  Detalles anime/manga consultan cola y archivos por lote; actualizaciones
  reutilizan el snapshot de cola. Se conservan las optimizaciones propias.
- [x] **Carga paralela y precarga del lector, con control por Wi-Fi.**
  [PR #2986](https://github.com/mihonapp/mihon/pull/2986).
  Planificador con cinco solicitudes por fuente, cancelación y precarga completa
  según Wi-Fi; fuera de esa política usa una ventana limitada. Las mediciones
  y límites de validación figuran abajo. Solo manga.
- [x] **Búsqueda avanzada de biblioteca.**
  [PR #3554](https://github.com/mihonapp/mihon/pull/3554).
  Operadores `&&`, `||`, negación, paréntesis, campos y comparaciones, por ejemplo
  `genre:action && unread:>0`, con vocabulario de episodios/vistos para anime.
  Conserva `id:`; es independiente de la búsqueda global de extensiones.
- [x] **Carga de extensiones en segundo plano y espera explícita de inicialización.**
  [PR #3788](https://github.com/mihonapp/mihon/pull/3788),
  [PR #3841](https://github.com/mihonapp/mihon/pull/3841).
  Carga en IO y espera antes de consumir fuentes, incluidos portadas, enlaces,
  bibliotecas, búsquedas, detalles, backups y tareas de actualización.
- [x] **Diagnóstico de extensiones que no cargan y cambios sin reiniciar.**
  [PR #3953](https://github.com/mihonapp/mihon/pull/3953),
  [PR #3954](https://github.com/mihonapp/mihon/pull/3954).
  Entradas gestionables/actualizables con error y diálogo de stack trace copiable.
  Cambiar confianza o NSFW provoca recarga sin reiniciar; se conservan las
  instancias válidas cuando no cambia el paquete.
- [x] **Vincular instalación y actualización con la firma del repositorio.**
  [093841105](https://github.com/mihonapp/mihon/commit/093841105).
  Ambas pilas eligen la versión más alta de repositorios con firma compatible
  y verifican firma, paquete y versión del APK descargado antes de instalarlo.
- [x] **Mejoras de los trackers existentes.**
  Búsqueda `id:` en AniList, Bangumi, Kitsu y Shikimori para anime y manga, y
  MangaUpdates para manga; MyAnimeList ya la incluía. Refresco de perfil y escalas
  de Kitsu adaptados; validación de esta continuación registrada abajo.
- [ ] **Integraciones opcionales de trackers nuevos — pospuestas.**
  Comick, MangaBaka y Hikka siguen ausentes. El usuario delimitó la continuación
  del 2026-10-22 a mejorar los servicios existentes. Su integración se evaluará
  aparte según necesidad y soporte real de anime; no bloquea ese alcance.

### Adaptación de la segunda tanda — 2026-10-21

Referencia: `mihon/main` confirmado y actualizado a
`d001e6f210e6a077a30dd9a1392573a3225bba2f`. Se conserva la identidad del fork,
DataSaver, ambas bases y la API pública de extensiones.
Los textos nuevos de precarga y diagnóstico tienen traducción española; también
se completó el aviso de caída de WebView de la primera tanda.

- **Bibliotecas:** `LibraryTracksFlow` mantiene la suscripción a tracks únicamente
  cuando existe un filtro de tracker activo o una categoría ordenada por puntuación.
  Al dejar de necesitarla, cancela y vacía el mapa. Ambos `LibraryScreenModel`
  reutilizan las fuentes, nombres e idiomas por actualización y evitan recalcular
  el mapa completo de trackers por fila. Referencias: `940272f75`, `f027de8c2`.
- **Descargas:** ambos caches resuelven IDs descargados de una lista usando el
  directorio del título una sola vez; los managers ofrecen un snapshot de la cola
  que conserva los objetos de descarga y su progreso. Detalles usan ambos lotes;
  actualizaciones reutilizan la cola, pero conservan la comprobación de descarga
  por título porque sus filas pueden pertenecer a títulos/fuentes distintos.
  Referencia: `fb8e640d6`. Se conserva el pacing y la recuperación propios.
- **Lector:** `ReaderPageLoadScheduler` limita a cinco solicitudes por fuente,
  compartidas entre capítulos. Prioriza la página solicitada y sus vecinas; cancela
  trabajo lejano y devuelve páginas canceladas a `QUEUE` antes de reemplazarlas.
  La precarga completa y del próximo capítulo respeta el ajuste nuevo de Wi-Fi,
  activado por defecto; fuera de esa política usa una ventana limitada de
  cuatro páginas siguientes y una anterior por solicitud de página. Referencia: `3e5d593e2` (PR #2986).
  Solo manga: el streaming de anime mantiene sus políticas propias.
- **Búsqueda de biblioteca:** parser compartido adaptado de `e7ec672c8` (PR #3554),
  con evaluadores distintos para `LibraryManga` y `LibraryAnime`. Admite `&&`, `||`,
  negación, paréntesis, comillas y campos; también operadores pegados al texto.
  Conserva `id:42` y añade comparaciones como `unread:>0`. Anime admite `seen`,
  `watched`, `unseen` y `episodes`; los conteos usan los datos reales aunque sus
  insignias estén desactivadas. `lang:es` funciona sin activar la insignia de idioma.
  Ejemplos: `genre:action && unread:>0`, `seen>=3 && episodes:12`,
  `-(author:Example || genre:romance)`, `added>=2026-10-01`.
  Campos de texto: `title`, `author`, `artist`, `description`/`desc`, `genre`/`tag`,
  `source`/`src`, `language`/`lang`, `source_id`. Comparaciones: `id`, `added`,
  `fetchinterval`/`fi`, `nextupdate`/`nu`, `read`/`seen`/`watched`, `unread`/`unseen`,
  `total`/`chapters`/`episodes`. No se añade `notes`: el fork no tiene el campo de
  notas de biblioteca de Mihon. Fechas en zona local; valores inválidos no coinciden.
- **Extensiones:** gestores inicializados en IO, con espera explícita antes de
  búsquedas, fuentes, detalles, bibliotecas, backups y trabajos de actualización.
  Los errores de carga conservan una entrada gestionable con stack trace copiable;
  cambiar confianza o NSFW provoca recarga sin reiniciar. Se conservan instancias
  válidas cuando no cambia el paquete. Adaptación de PR #3788/#3841/#3953/#3954.
- **Repositorios:** selección de la versión más alta entre repositorios cuya firma
  coincide con la extensión instalada. Antes de despachar el APK a cualquier
  instalador, se verifican firma del repositorio, paquete y versión solicitados.
  Los fallos de carga siguen siendo actualizables. Referencia: `093841105`.

**Evaluación de trackers (actualizada el 2026-10-22):** MyAnimeList ya incluye
`id:` en ambas pilas. Las [notas oficiales](https://mihon.app/changelogs/v0.21.2),
consultadas en esta continuación, también enumeran AniList, Bangumi, Kitsu,
MangaUpdates, Shikimori y Hikka; la revisión anterior no desglosaba esos servicios.
La adaptación de los existentes se registra después de la validación original de
esta tanda. Se conserva la representación local 0–10 de Kitsu y las conversiones
`ratingTwenty`; no se migran bases ni formato de backup. Comick, MangaBaka y Hikka
quedan pospuestos por decisión del usuario.

Validación automatizada final:

- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: correcto;
  492 pruebas unitarias, sin fallos ni pruebas omitidas.
- `python3 -B -m unittest discover -s scripts/tests -p 'test_check_16kb_alignment.py' -v`:
  15 pruebas correctas. No se modificaron dependencias nativas.
- `git diff --check`: correcto. El índice preparado previo se conserva sin cambios;
  las modificaciones de esta tanda permanecen sin stage ni commit.
- Las primeras compilaciones detectaron imports y mocks incompletos de la adaptación;
  corregidos antes de la validación final. La cobertura del lector detectó además
  un reintento automático de errores y una carrera entre reintentos durante la
  cancelación: corregidos y cubiertos antes del APK final. La prueba de saltos
  rápidos en emulador detectó además una página conservada por el paginador cuya
  descarga se cancelaba sin volver a solicitarse. `loadPage` vuelve a mantener
  demanda hasta que se libera cada holder; dos vistas de una página dividida
  comparten un contador. Corregido y cubierto con pruebas de retención/liberación.
- Cobertura: ciclo de suscripción a tracks, parser y evaluadores anime/manga,
  idioma independiente de insignias, lotes de descarga con nombres alternativos,
  límites por fuente, Wi-Fi, cancelación y reintentos del lector, selección de
  repositorio por firma, validación de paquete/versión/hash, confianza y espera
  de inicialización en búsqueda global.

Pruebas de emulador (`emulator-5554`, Android SDK 36, páginas de 16 KB):

- APK final x86_64, SHA-256:
  `cdaf64ca4b7c7288e99cd5364b27cf33db9d8990b5d67246d11be438cd516398`.
- 15 consultas UI correctas: ocho de manga y siete de anime; idioma con insignia
  desactivada, ID, comparaciones, negación, AND/OR, paréntesis y entradas incompletas.
  Smoke adicional sin espacios: `genre:action&&unread:>0`, dos títulos esperados.
  Esta matriz se ejecutó antes de la corrección final del lector; la búsqueda no
  cambió y las bibliotecas volvieron a comprobarse en el arranque del APK final.
- Filtro no leído en manga: IS → NOT → desactivado, con 4 → 0 → 4 resultados.
  Orden por cantidad correcto: capítulos 457/435/194/15 y episodios 16/8.
  Detalles de manga con 194 capítulos y anime con ocho episodios; listas y botones
  de descarga visibles. Actualizaciones de manga con entrada y botón de descarga;
  las de anime están vacías en los datos de prueba.
- Desactivar NSFW produjo diagnóstico de carga en cuatro extensiones existentes;
  una fila abrió el stack trace real y su botón de copia. Al reactivarlo se
  recuperaron nombres, idiomas y fuentes con el mismo proceso, sin reiniciar.
  Ajuste restaurado. No se pulsó Copiar para preservar el portapapeles existente.
- Lector con extensión HTTP temporal autorizada: 20 páginas del capítulo actual y
  20 del siguiente precargadas sobre Wi-Fi, pico de cinco solicitudes simultáneas,
  72.243.684 bytes servidos. Primera respuesta en 0,350 s y 40 respuestas completas
  en 9,260 s, con demora artificial de 350 ms por imagen. Imagen correcta en pantalla.
  PSS de la app: 296.740 → 297.635 KB (+895 KB) en dos muestras puntuales; no es una
  comparación con la implementación anterior ni una medición de pico de memoria.
- Fuera de Wi-Fi, con el ajuste predeterminado activado, el lector paginado cargó
  seis imágenes cercanas (índices 0–5), 10.838.598 bytes, pico de tres solicitudes,
  sin precargar otro capítulo. La ventana −1/+4 se aplica por solicitud de página;
  el holder vecino del paginador explica la sexta imagen. Sin spinner permanente.
- Firma en Android real: 14 casos correctos invocando los loaders de ambas pilas
  mediante instrumentación temporal. La firma válida acepta; clave, paquete o
  versión incorrectos, clave vacía, archivo inválido y tipo de extensión opuesto
  rechazan. Los APKs usados como archivos de firma no se instalaron ni confiaron.
  Esta prueba no recorre DownloadManager ni todos los instaladores del sistema.
- Saltos rápidos sobre caché vacía: el primer APK dejó la página 10 en carga al
  hacer 10 → 20 → 10 con 80 ms entre saltos. Tras corregir la demanda por holder,
  la misma secuencia en el APK final muestra la página 10 cargada tras cinco
  segundos, sin spinner ni solicitudes activas. La ventana sin Wi-Fi se conserva.
  Durante cancelación el servidor llegó a contar siete handlers, incluidos los
  de conexiones abandonadas que seguían en su demora artificial; ese contador
  no equivale a solicitudes activas del cliente. El límite lógico compartido de
  cinco también se comprueba con dos capítulos en pruebas unitarias.
- Desactivar desde la UI la restricción de Wi-Fi permitió completar 20/20 imágenes
  del capítulo sin Wi-Fi, incluidas las lejanas a la ventana. El ajuste quedó
  restaurado a activado y Wi-Fi volvió a su estado inicial.
- Lectura continua (Long strip): tres desplazamientos y páginas 3/4 visibles, sin
  spinner. El cambio de modo se limitó al título de prueba.
- Limpieza completada: extensión y confianza temporales, filas, historial, imágenes,
  listas de páginas, instrumentación, archivos del dispositivo, túnel ADB y servidor
  retirados. Los conteos de todas las tablas y los IDs favoritos coinciden con el
  estado inicial: cuatro mangas y dos animes. Preferencias y flags de categorías
  restaurados. Se eliminaron las copias privadas de bases/preferencias usadas para
  comparar; solo se conservaron informes de agregados y evidencia de prueba.
- La confianza en la extensión local de QA requirió autorización explícita del
  usuario tras un rechazo de la revisión automática. Se retiró al terminar.
- Estado final: emulador online, Wi-Fi activo y app operativa, sin crashes del
  proceso final. No se bloqueó ni se detuvo el emulador; no necesitó reinicio.

Las mediciones pertenecen al servidor local controlado y no predicen tiempos
ni consumo de una extensión externa. El orden por puntuación de tracker no se
ofrece sin sesión iniciada; su demanda/cancelación se cubre con pruebas unitarias.

### Continuación de trackers existentes — 2026-10-22

Se confirmó la rama predeterminada de Mihon y se ejecutó `git fetch mihon`;
`mihon/main` permanece en `d001e6f210e6a077a30dd9a1392573a3225bba2f`.
El alcance autorizado excluye integraciones nuevas.

- **Búsqueda `id:`:** AniList, Bangumi, Kitsu y Shikimori cubren anime y manga;
  MangaUpdates cubre manga y acepta identificadores decimales o base36. Kitsu
  acepta ID o slug. Se conservan las búsquedas por título, el soporte existente de
  MyAnimeList y la separación de tipos. Se adaptó el código del snapshot upstream
  a las APIs locales, sin introducir Apollo. El commit original de `id:` no pudo
  recuperarse en el historial superficial disponible.
- **Perfil:** `BaseTracker` coordina refrescos, descarta duplicados y cancela al
  salir de la pantalla, cerrar sesión o guardar credenciales de otra sesión. Los
  resultados se aplican bajo un bloqueo compartido con logout. El nombre visible
  usa una nueva preferencia privada String, separada del ID/correo y token usados
  para autenticarse. Ajustes muestra botón, progreso y resultado; los trackers
  enhanced de ambas pilas no ofrecen refresco. Adaptado de `01e3f8c60` (PR #3828).
  AniList, MAL, Bangumi, Shikimori, MangaUpdates, Kitsu y Simkl actualizan perfil;
  AniList y Kitsu también recuperan la escala elegida en la cuenta.
- **Kitsu:** escalas simple, regular y avanzada, con fallback ante valores nuevos,
  ausentes o con mayúsculas. Perfil mediante `currentAccount` GraphQL, siguiendo
  `ec614bb4c` y `a82baac3e`; las búsquedas por ID conservan REST. Los modelos de
  anime/manga y backups mantienen puntuaciones 0–10, y las conversiones de envío y
  lectura `ratingTwenty` permanecen intactas. Cambiar de escala solo modifica la
  presentación hasta que el usuario confirma una puntuación nueva.

Validación automatizada de esta continuación:

- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: correcto.
  Tras corregir firmas de tests para que JUnit descubriera todos los casos nuevos,
  se repitió `./gradlew spotlessCheck testReleaseUnitTest`: 531 pruebas correctas,
  sin fallos ni omitidas, incluidas 39 nuevas de trackers.
- Cobertura nueva: nueve combinaciones de búsqueda por ID, búsquedas por título,
  IDs inválidos/ausentes, tipo anime/manga, perfiles, estados de refresco,
  duplicados, cancelación, respuestas tardías, credenciales privadas y las tres
  escalas de Kitsu con round-trip de todas las puntuaciones antiguas.
- Regresión del checker de alineación: 15 pruebas correctas. Sin cambios nativos.
- Las primeras comprobaciones detectaron firma incorrecta de `logcat`, constantes
  de fixtures sin formato y un retorno interno expuesto por un test; corregidos.
  También se corrigió el retorno de los tests `runBlocking` para que JUnit
  ejecutara todos los casos, comprobando sus conteos en los informes XML.
- `git diff --check`, XML de recursos y referencias locales del TODO: correctos.
  El índice preparado previo se conserva intacto; cambios nuevos sin stage/commit.

Pruebas de emulador de esta continuación:

- APK debug x86_64 SHA-256:
  `d4aebafa5f7d26bc5ad77c96c7b9c349728baac622a988467549e629586f29bd`.
  Instalación con `adb install -r` correcta.
- Un subagente especializado completó 80 comprobaciones Android con respuestas
  HTTP interceptadas: nueve variantes `id:`, endpoints/tipos, identificadores
  inválidos, base36 de MangaUpdates y las tres escalas Kitsu. Los siete servicios
  existentes actualizaron el perfil con éxito y conservaron el nombre anterior
  ante error; ID, correo/usuario y token de autenticación se mantuvieron intactos.
  No se enviaron peticiones ni escrituras a servicios reales.
- `PreferenceItem` y `TrackingPreferenceWidget` reales: click mediante accesibilidad,
  petición, indicador de carga y fila deshabilitada automáticamente, nombre nuevo
  y botón restaurado al completar. Flujos y avisos Android de éxito/error correctos.
  Komga (manga), Jellyfin (anime) y un servicio sin sesión no mostraron refresco.
  Logout con petición en vuelo canceló la operación; la respuesta tardía no restauró
  sesión ni nombre y la UI permaneció desconectada.
- Smoke de la app real: Más → Ajustes → Tracking, con siete servicios visibles y
  sin acciones de refresco al no haber cuentas iniciadas. Revisión visual correcta.
  La comparación de preferencias reales de tracking antes/después pasó.
- En el primer intento el emulador desapareció de ADB:
  `adb: device 'emulator-5554' not found`; `adb devices -l` quedó vacío.
  El agente no lo cerró ni reinició. El usuario lo reinició y se completó la matriz;
  no se determinó la causa de la desaparición ni se atribuye a la app.
- El fixture inicial de AniList capturó una preferencia antes de inyectar el almacén
  aislado y cambió la escala real solo en memoria. El XML persistente y el nuevo
  proceso real confirmaron la ausencia de `anilist_score_type`; no se escribió ni
  borró una clave real para asumir un valor anterior. Se corrigió el aislamiento
  antes de repetir la matriz completa. No fue un defecto de producción.
- Limpieza completada: paquete `xyz.b1ack0u7.aniyomi.trackqa` desinstalado, lista
  de instrumentaciones vacía, preferencias aisladas ausentes, capturas/dump
  temporales del dispositivo y XML privados locales retirados. Una comprobación
  independiente en un proceso nuevo confirmó clave real de AniList ausente y
  escala predeterminada POINT_10. App abierta y emulador operativo, boot completo,
  sin ANR desde el reinicio ni errores AndroidRuntime en la comprobación final.

Límites: las APIs, perfiles y escalas se probaron en Android con respuestas
controladas, sin login real ni sincronización contra cuentas externas. Las
puntuaciones almacenadas y su conversión se cubren también en las pruebas unitarias;
no se realizó una restauración completa de un backup real en este recorrido.

## 3. Proyectos independientes — evaluar después

- [ ] **Base de datos y consultas de bibliotecas grandes:**
  [PR #3805](https://github.com/mihonapp/mihon/pull/3805) y
  [PR #4022](https://github.com/mihonapp/mihon/pull/4022).
  Mihon reestructuró esquema y fusionó duplicados. Adaptar dos bases y dos
  secuencias de migraciones; incluir las correcciones posteriores a esa migración.
  No importar reparaciones de problemas introducidos por ese esquema si el esquema
  nuevo no se adopta.
- [x] **Backups por streaming:**
  [771ba33a3](https://github.com/mihonapp/mihon/commit/771ba33a3).
  Creación por lotes, validación progresiva y restauración mediante caché temporal
  para anime y manga. Formato y detección legacy conservados, con lectura de los
  archivos nuevos por el serializer anterior. Se mantienen las reglas propias de
  progreso/detalles; validación y límites de memoria registrados abajo.
- [x] **Corregir el webtoon actual para páginas muy altas:**
  [b5856135c](https://github.com/mihonapp/mihon/commit/b5856135c).
  Dibujo por franjas con el decoder actual, recarga fuera de pantalla y protección
  frente a resultados tardíos. Validado con pruebas unitarias y Canvas hardware en
  Android; alcance y límites registrados abajo.
- [ ] **Lector de alta calidad y migración de decoder — pospuestos:**
  [PR #3388](https://github.com/mihonapp/mihon/pull/3388),
  [65d7110e1](https://github.com/mihonapp/mihon/commit/65d7110e1).
  Renderer experimental y decoder diferente: evaluar en otro proyecto. No forman
  parte de la corrección del webtoon actual ni requieren cambiar sus dependencias.
  Probar GPU, memoria, imágenes animadas, orientación, color y Android antiguos.

### Adaptación de backups por streaming — 2026-10-22

Referencia: `771ba33a33b6b07675625ece8211d1584f247ac8`; rama predeterminada
`main` confirmada mediante `git ls-remote --symref mihon HEAD` y `git fetch mihon`,
con `mihon/main` en `d001e6f210e6a077a30dd9a1392573a3225bba2f`.
No se adopta el nuevo esquema de base de datos ni se cambia la API de extensiones.

- **Creación:** `BackupCreator`, `AnimeBackupCreator` y `MangaBackupCreator` escriben
  títulos progresivamente mediante `BackupWriter`. Consultas por lotes de hasta
  100 títulos recuperan episodios/capítulos, categorías, tracks e historial; este
  último obtiene la URL directamente, sin consultas individuales. La paginación
  usa IDs y fija el máximo al inicio para no incluir títulos insertados después.
  Se conservan las opciones de incluir títulos vistos/leídos fuera de biblioteca,
  fuentes, repositorios, preferencias privadas, APKs y botones personalizados.
- **Formato y validación:** `BackupStream`, `BackupDecoder` y `BackupFileValidator`
  leen entradas individuales, admiten protobuf sin comprimir y gzip, y mantienen
  los campos y ProtoNumbers existentes. La detección legacy conserva los campos
  antiguos de anime y su significado, incluidos los números que se solapan con
  metadatos modernos. El serializer monolítico anterior puede leer los archivos
  nuevos. Las pasadas de títulos descartan metadatos que ya se leyeron.
- **Restauración:** `BackupRestoreStore` guarda payloads individuales en caché
  privada y conserva en RAM solo su índice y los metadatos del backup. Mantiene
  nuevos antes que existentes y fecha de modificación descendente, sujeto a que
  los padres de anime deben restaurarse antes que sus temporadas. Se remapean IDs
  antiguos a IDs reales, se toleran padres ausentes sin crear vínculos inválidos
  y se registran ciclos sin bloquear entradas independientes. Categorías se
  restauran antes que los títulos; la caché se elimina al terminar o cancelar.
  Se conservan las reglas propias de mezcla de progreso, marcadores, historial,
  detalles y trackers, sin importar otra política de restauración de Mihon.
- **Campos anime:** se conservan IDs y ajustes de temporadas y se incluye
  `backgroundUrl`, ya definido en el formato pero omitido anteriormente al crear.

Validación automatizada:

- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: correcto;
  570 pruebas unitarias, sin fallos ni omitidas. Las 39 nuevas cubren lectura
  antigua/nueva, ambos formatos de anime, los seis tipos de preferencias, metadatos,
  opciones, paginación, payloads corruptos/truncados, cierre, concurrencia del
  archivo temporal y relaciones de temporadas, incluida una cadena de 10.000.
- El primer recorrido detectó fixtures con `encodeDefaults=true` incompatible con
  opcionales nulos, constantes/tags protobuf incorrectos y formato; corregidos.
  Se hicieron explícitas las firmas Unit de las pruebas de coroutines y se
  verificaron sus conteos XML. Un recorrido posterior encontró dos fallos de
  sincronización en `MigratorTest`, sin cambios en esa área; sus seis casos
  aislados y la suite completa final pasaron, sin modificar esas pruebas.
- Regresión del checker de alineación: 15 pruebas correctas; sin cambios nativos.
- `git diff --check`: correcto; índice preparado previo preservado.

Pruebas de emulador (`emulator-5554`, Android con páginas de 16 KB):

- APK debug x86_64 SHA-256:
  `2de4f7a18c8529563339027898a9fe0ea438203cb6621296159f898a51b4a54d`.
  Instalación con `adb install -r` correcta, conservando datos de la app.
- Subagente dedicado: 79 comprobaciones correctas con SQLDelight y SQLite reales,
  bases y preferencias aisladas. Restauración de 205 anime y 205 manga desde el
  formato monolítico moderno sin comprimir, el antiguo de Aniyomi en gzip y el
  nuevo streaming en gzip. El creador real recorre tres lotes por pila; el parser
  monolítico anterior lee el resultado nuevo. El validador también acepta las
  variantes streaming y legacy sin comprimir.
- Verificados progreso, marcadores, filler, duración, resumen, historial, trackers,
  categorías, scanlators excluidos y vínculo de temporada con el ID real del padre.
  Restaurar progreso anterior mantiene lo ya leído/visto y sus marcadores. El
  ajuste de títulos fuera de biblioteca incluye los dos con progreso, excluye uno
  sin progreso y permite respaldar solo los 203 favoritos de cada pila.
- Creación/restauración de solo metadatos y restauración sin categorías correctas.
  APKs opcionales, botones y preferencias comprobados por serialización, sin
  instalar extensiones ni cambiar preferencias reales. Archivos vacíos, inválidos
  y truncados rechazados. La caché se elimina después de éxito, error de payload
  durante preparación y cancelación real del trabajo.
- Los intentos iniciales del harness corrigieron una carrera antes de inicializar
  Injekt, fuentes ausentes en un fixture legacy, categorías de sistema esperadas
  y un puente de coroutines que comprobaba la cancelación antes de terminar el
  `finally` del método probado. Se repitió la matriz final; no fueron defectos de
  producción. Evidencia externa: `/tmp/aniyomi-backup-qa/result-final.txt`.
- Las consultas de identidad de las bibliotecas y el mapa completo de preferencias
  reales se compararon antes/después, sin diferencias. No se usaron cuentas ni
  peticiones a servicios externos; los restauradores recibieron dependencias
  explícitas de las bases aisladas.

- Limpieza final: harness desinstalado, notificación de QA cancelada y bases,
  preferencias y caché aisladas eliminadas. El emulador nunca se detuvo y quedó
  conectado; la instrumentación cerró su proceso de app al terminar. El APK debug
  final permanece instalado. Reporte: `/tmp/aniyomi-backup-qa/QA-REPORT.md`.

Límites de dispositivo: el harness ejecuta el método real de preparación y
restauración con dependencias aisladas; no automatiza el selector de archivos ni
la planificación de WorkManager. No instala los APKs opcionales del backup.

Validación final con la biblioteca existente del emulador — 2026-10-22:

- 61 comprobaciones correctas sobre el APK final de la corrección de webtoon,
  SHA-256 `cb263872f4ec8a9de41ba261df6f31b68aa03726177c991f1a2b3c9e0a2e012b`.
  Se usaron `BackupCreator.backup` y `BackupRestorer.restore` públicos, con
  dependencias predeterminadas y las bases reales de la app, sin fixtures.
- Las bases contenían 103 anime y 813 manga, incluidos registros de navegación.
  El backup seleccionó 3 anime y 7 manga: los 2/4 favoritos y entradas externas
  con progreso. Incluye 25 episodios, 1750 capítulos, 561 capítulos leídos,
  2 historiales anime, 9 manga, 43 preferencias, 2 repositorios y un botón.
- Crear → restaurar sobre los datos existentes → crear otra vez conservó las
  12 secciones semánticas. Hubo dos mensajes por repositorios idénticos ya
  registrados: comportamiento previo de sus restauradores, sin diferencias de
  contenido ni errores inesperados. Este aviso permanece como limitación previa.
- Con la app detenida, se apartaron privadamente sus bases y se crearon bases
  vacías. Restaurar el mismo archivo terminó con cero errores/avisos; otro
  backup coincidió en todas las secciones. La comparación conserva estrictos
  contenido, progreso e historial y normaliza solo IDs internos, huecos de orden,
  versiones y tiempos de modificación regenerados por la restauración.
- Después se recuperaron las bases y preferencias originales para mantener
  también los registros de caché que el backup excluye. Se verificaron los
  103/813 registros y se generó un backup de 46999 bytes idéntico, byte por byte,
  al original, SHA-256:
  `e89a91455277c0887e66f2490eb6a29032e2290e32734facbd1f2a78261fd1a6`.
- Ambas bases pasaron `PRAGMA integrity_check` y `foreign_key_check` antes y
  después de cada recorrido, sin problemas. Las credenciales marcadas privadas
  no cambiaron; esta instalación no tenía claves privadas, trackers, marcadores,
  filler, temporadas, categorías propias ni preferencias de fuente con datos.
  Esta matriz no amplía la cobertura de esas funciones; sus pruebas unitarias y
  aisladas anteriores se registran arriba.
- Recuperación y backups permanecieron dentro del emulador; no se exportaron
  bases, preferencias ni archivos de backup al equipo. Se conservaron únicamente
  el backup original y el archivo privado de recuperación, retirando duplicados
  de prueba. Harness desinstalado, notificaciones retiradas y app abierta
  correctamente al terminar, con el emulador conectado sin reinicios.
- Reporte seguro, con conteos y hashes: `/tmp/aniyomi-library-roundtrip/QA-REPORT.md`.
  No se modificó código de producción ni se repitió Gradle; solo validación Android
  y actualización de documentación. No se automatizó el selector de archivos SAF.

Límites de memoria: creación retiene un lote y sus datos relacionados;
restauración retiene un título decodificado por pila y un índice proporcional al número de
entradas, además de los metadatos. Los APKs opcionales de extensiones siguen
formando parte de los metadatos cargados en memoria. La caché de restauración
requiere espacio en disco; no se promete una reducción del tamaño del backup ni
un porcentaje de ahorro de RAM sin una medición comparativa.

### Corrección del webtoon actual — 2026-10-22

Referencia: `b5856135c59c573f3708e9b900a2aebf132a5e83` de Mihon. Rama
predeterminada `main` confirmada y `mihon/main` actualizado/verificado en
`d001e6f210e6a077a30dd9a1392573a3225bba2f`. Solo manga: anime usa mpv y no
comparte estas vistas. Se mantiene el lector actual; no se añade WebGPU ni se
cambian el decoder nativo, SSIV, Coil o la API de extensiones.

- **Decoder:** `TachiyomiImageDecoder` usa la API de regiones existente para
  producir franjas inmutables, sin crear un bitmap Java de toda la página.
  `WebtoonStripGeometry` limita cada franja a 4096 píxeles por dimensión y 4 MiB,
  con muestreo potencia de dos, cobertura continua y ajuste de la última región
  cuando su altura es menor que el muestreo. Conserva recorte e ICC del decoder
  actual. Un semáforo serializa estas decodificaciones; intermedios y decoder se
  liberan ante éxito, error o cancelación.
- **Dibujo:** `WebtoonStripImageView` respeta el área de recorte del Canvas y dibuja
  las franjas con límites acumulados para evitar huecos por redondeo. Mantiene al
  menos un píxel de altura para panoramas extremos. Los gestos siguen en
  `WebtoonFrame`/`WebtoonRecyclerView`; la nueva vista no los consume.
- **Ciclo de vida:** páginas de más de 64 MiB liberan franjas al salir de pantalla
  sin perder sus dimensiones y recargan al volver desde datos comprimidos
  reutilizables. `ReaderPageImageView` cancela peticiones al reciclar, reemplazar
  o separar la vista. Una transferencia de propiedad sincronizada libera también
  resultados que Coil descarta antes de entregarlos; las generaciones impiden que
  una petición antigua sustituya la página actual. No reinicia una carga activa
  únicamente porque la vista se adjunte por primera vez.
- **Rutas conservadas:** el ajuste existente de forzar SSIV mantiene su ruta;
  lector paginado y GIF/otras imágenes animadas conservan sus vistas. Se detienen
  animaciones y se liberan recursos al reemplazarlas, con la misma protección
  contra respuestas tardías.

Validación automatizada:

- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: correcto;
  586 pruebas, sin fallos ni omitidas. Las 16 nuevas cubren geometría, dimensiones
  impares, límite de textura/bytes, última región parcial, panoramas, presupuesto
  de memoria y transferencia/cancelación de resultados. Las pruebas de geometría
  incluyen 500 combinaciones deterministas de dimensiones y tamaños de muestreo.
- Checker de alineación: 15 regresiones correctas, sin cambios nativos.
- Revisión independiente de decoder y UI, `git diff --check` y conservación del
  índice preparado previo. Los cambios de backups y tandas anteriores permanecen.

Pruebas de emulador (`emulator-5554`, Android SDK 36, páginas de 16 KB):

- APK debug x86_64 SHA-256:
  `cb263872f4ec8a9de41ba261df6f31b68aa03726177c991f1a2b3c9e0a2e012b`.
  Subagente dedicado: 446 comprobaciones correctas, con decoder JNI, Coil,
  `ReaderPageImageView`, `WebtoonFrame` y `WebtoonRecyclerView` reales. Instalado
  conservando datos de la app; el harness usa contenido y vistas aislados.
- PNG 1501×30001, JPEG y WebP de dimensiones impares, páginas de un píxel y
  panoramas muy anchos. Comparación de todas las filas de regiones con muestreo 2
  contra la decodificación monolítica nativa: sin desplazamiento de filas.
  Verificados límites de dimensión, bytes e inmutabilidad de cada franja.
- Capturas PixelCopy de una ventana con aceleración hardware comprueban bandas
  coloreadas al principio, entre franjas y al final, sin áreas vacías ni huecos.
  Recorte de bordes conservado, incluida una franja final de un píxel. Cambios de
  ancho del viewport recalculan correctamente la altura y el contenido.
- Página con unos 132 MB sin comprimir: liberar al separar la vista y regresar
  conserva dimensiones y vuelve a mostrar píxeles. Cancelación de carga inicial,
  reemplazo y reciclaje repetidos seis veces sin sustituir la página actual con
  resultados antiguos. Tap y doble tap mantienen los gestos externos de zoom.
- GIF usa `AppCompatImageView`, permanece en reproducción y cambia de fotogramas;
  SSIV forzado y lector paginado conservan sus rutas. Sin errores de carga en la
  matriz final. Preferencias del lector comparadas antes/después, sin cambios.
- Los primeros intentos del harness usaban una espera fija demasiado corta para
  recargar un PNG grande y comparaban dos capturas de GIF que coincidían en fase.
  Se esperó la imagen real y se muestrearon varios fotogramas; la matriz final
  pasó sin cambios adicionales de producción. Resultados literales externos:
  `/tmp/aniyomi-webtoon-qa/result-3.txt`.

- Limpieza: harness desinstalado y caché temporal retirada, sin cambios en
  bibliotecas ni preferencias de lector. Emulador conectado durante todo el
  recorrido; app cerrada tras instrumentación y APK final instalado. Reporte:
  `/tmp/aniyomi-webtoon-qa/QA-REPORT.md`. Capturas hardware revisadas visualmente
  en `tall-png.png` y `threshold-crop.png`, dentro del mismo directorio.

Límites de dispositivo: se probaron componentes reales del lector con contenido
local, sin modificar bibliotecas ni usar extensiones externas. No se automatizó
la navegación completa de `ReaderActivity`, una rotación física del dispositivo,
perfiles ICC/HDR ni dispositivos con GPU o versiones de Android diferentes. El
cambio de orientación se cubre parcialmente mediante tamaños de viewport. No se
midió FPS ni consumo máximo de RAM.

Límites de memoria: 128 MiB es un presupuesto suave para las franjas, que puede
superarse para conservar la resolución del viewport. Los datos comprimidos de
la página se retienen hasta reciclarla y el decoder nativo puede conservar el
fotograma completo internamente. Esta corrección evita el bitmap Java completo
adicional y la textura gigante; no elimina toda asignación proporcional a la
página ni garantiza un porcentaje de ahorro o FPS sin medición comparativa.

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
3. Continuar desde la primera casilla pendiente; las tandas 1 y 2 registran sus
   implementaciones y límites abajo. Revisar dependencias mínimas de cada parche y subir
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
