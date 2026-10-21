# Tadami — investigación del reproductor y posibles adaptaciones

Revisión e implementación de las dos primeras tandas: **2026-10-19**. La sección 1
está adaptada; se añadieron diagnóstico seguro de resolución y estado de sliders.
Las comprobaciones y límites de validación se detallan al final.

## Fuente y límites

Proyecto analizado: [andarcanum/Tadami-Aniyomi-fork](https://github.com/andarcanum/Tadami-Aniyomi-fork),
fork de Aniyomi. Se eligió este repositorio de origen frente a otros forks llamados
Tadami. Rama predeterminada comprobada en el clon: `main`.
Snapshot: `d8442c8129e66bcee0b4d88050b13799c9728cdf`, 6 de octubre de 2026.
Se descargó una copia temporal con `git clone --depth=250 --no-checkout` y se
inspeccionaron fuentes de HEAD y parches del historial disponible. El historial es
superficial: no permite afirmar que se hayan inventariado todos los cambios desde
la bifurcación. Los hallazgos describen código de `main`, no garantizan que cada
función esté presente en una APK estable concreta.

Fork local: `dev`, HEAD `ba6adedacd8bdd047bccccd369e650f1f9eecb40`, con cambios
previos preparados en otras áreas. Se conservan sus contenidos y su estado de stage.
La investigación inicial fue estática; las comprobaciones de esta implementación se
registran al final, incluida reproducción funcional en emulador. No hay nuevas
mediciones comparativas de rendimiento, consumo, GPU o red.

El plan de buffering, salud de reproducción y recuperación queda en
[Video player](video-player-todo.md). Este documento conserva evidencia específica
de Tadami, beneficios y límites para no mezclar funciones opcionales con ese plan.
Todas las tareas son anime salvo la infraestructura compartida que se indique.
El lector de manga usa otra Activity y no recibe estas políticas automáticamente.

## Resultado principal

Hay parches pequeños de corrección con valor inmediato y funciones opcionales de
usabilidad/calidad visual. No hay evidencia aquí de una red más rápida o un nuevo
motor nativo: ambos catálogos declaran `aniyomi-mpv-lib:1.18.n`,
`ffmpeg-kit:1.18` y `torrserver:0.1.0`. La igualdad de versiones declaradas no
constituye una comparación binaria de los APKs.

Tadami no sustituye el watchdog y la coordinación de fuentes propios del fork.
Su selección de calidad/servidor por preferencias no demuestra adaptación dinámica
por ancho de banda ni implementación de los pendientes de buffering locales.

## 1. Correcciones prioritarias — implementadas, validación nativa pendiente

- [x] **Construir una sola cadena de filtros `vf`.** Adaptado de
  [7e569b3d](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/7e569b3d18503d319f33732fcd8f3746b60966cc).
  `AniyomiMPVView.initOptions` usa `VideoFilterChain.kt`: en software compone
  `gradfun=radius=12,format=yuv420p`; con hardware nunca añade yuv420p.
  Solamente CPU debanding + hardware solicita `auto-copy`, porque gradfun necesita
  frames accesibles desde CPU. Las demás combinaciones conservan `auto`/`no`, sin
  cambiar preferencias, defaults ni `gpu-next`. `VideoFilterChainTest` cubre la
  matriz debanding/YUV/hardware. El [manual de mpv](https://mpv.io/manual/stable/#options-hwdec)
  documenta que auto-copy permite filtros CPU y puede recurrir a software; las
  copias pueden incrementar consumo. La validación conjunta de abajo confirma
  `hwdec-current=mediacodec-copy` en emulador; falta dispositivo físico. La configuración avanzada del
  usuario mantiene su precedencia sobre estas opciones iniciales.
- [x] **Cerrar streams al copiar scripts, opciones, shaders y fuentes.** Adaptado de
  [dbaf5c02](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/dbaf5c0251858194a711de0120ee9e91f808e6f1).
  `PlayerActivity.copyUserFiles` y `copyFontsDirectory` anidan `use` para cerrar
  ambos streams incluso ante excepciones. El bridge Lua abre la entrada dentro del
  bloque de salida, evitando dejarla abierta si no hay destino. `copyAssets` ya
  dispone de cierre explícito y no se modificó. Revisión estática y compilación;
  no se afirma una mejora del throughput de vídeo ni un ensayo de descriptores.
- [x] **No lanzar excepción con un decoder desconocido.** Adaptado de
  [91a8a12b](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/91a8a12b8fe38ed3d058d48ec25fa6a2801cbbf1).
  `getDecoderFromValue` usa `firstOrNull` y devuelve `Decoder.Auto` ante valores
  desconocidos, vacíos o nulos, sin escribir la selección en mpv. `DecoderTest`
  cubre los valores configurados/activos conocidos y el fallback. No se fuerza
  `mediacodec-copy` ni se cambia la selección de `gpu-next`.
- [x] **Corregir el resultado externo con duración cero.** Adaptado de
  [3f7c018f](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/3f7c018f491e757f6fa0fae160425fb0ecbeee71).
  `ExternalIntents` usa `ExternalPlaybackProgress.kt`: retorno vacío o 0/0 conserva
  progreso; ambas familias de extras aceptan Int/Long; finalización explícita usa
  duración positiva reportada o previa. Sin duración conocida no inventa progreso.
  Conserva historial en retornos OK y respeta el modo incognito. `ExternalPlaybackProgressTest`
  prueba ausencia, tipos, valores inválidos, salida del usuario y finalización.
  `MainActivity` sigue descartando resultados distintos de RESULT_OK, incluidos
  RESULT_CANCELED (comprobado por inspección; sin prueba de Activity/dispositivo).
- [x] **Aplicar cutout, consultar barra de estado y evitar guardar aspecto repetido.**
  Adaptado de [97decc74](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/97decc745377c6cd2d9b5b6c89154571c933c75c).
  `PlayerActivity.onStart` reasigna `window.attributes`. `PlayerViewModel.showControls`
  lee la preferencia vigente; `changeVideoAspect` solo guarda y muestra aviso cuando
  cambia, pero siempre aplica panscan/ratio para rotación y nuevos archivos.
  El fix local EdgeToEdge `29b652808` afecta diálogos/sheets; no se sustituyó ni se
  alteraron sus insets. El seguimiento final valida PiP, rotación y cutout del perfil
  Pixel8; siguen pendientes pantalla dividida y dispositivos/versiones adicionales.
- [x] **Pausar al EOF normal sin autoplay.** Adaptado de `7e569b3d`.
  `PlayerActivity.endFile` llama `viewModel.pause()` después de comprobar contenido
  reproducido. El EOF sin contenido conserva failover y deduplicación de fallos;
  autoplay conserva cambio de episodio. `PlaybackHealthTest` cubre saltos al final,
  progreso real, posiciones estacionarias y reset entre servidores. La pausa usa
  la misma ruta que controles/media session; no añade un estado de media session
  nuevo. La validación conjunta confirma reproducción y EOF nativo con fixture;
  faltan seek/reanudación al EOF y estado de PiP/media session. Los tests unitarios
  no ejecutan Activity ni mpv.

Archivos de producción: `app/src/main/java/eu/kanade/tachiyomi/ui/player/`.
Pruebas: `app/src/test/java/eu/kanade/tachiyomi/ui/player/` (salud en `utils/`).
Todos estos cambios son de anime: `ReaderActivity` no usa mpv ni consume resultados
de reproductores externos. No se cambió infraestructura compartida con manga.

## 2. Decoder y consumo — adaptar tras medir

- [ ] **Evaluar desactivar yuv420p por defecto.** Tadami lo desactiva y cambia sus
  presets en `7e569b3d`; localmente la preferencia sigue siendo true y se conserva
  la protección para no usarla con hardware decoding. El beneficio adicional aquí
  no es el mismo que en el Tadami anterior al parche. Probar color, formatos de
  salida, CPU y consumo; no copiar su migración que resetea ajustes de usuarios.
- [ ] **Evaluar `mediacodec-copy` frente a `auto` y fallback para `gpu-next`.**
  Referencia: `91a8a12b`. Tadami elige HW copy explícito y restringe `gpu-next` a
  Android 10+. El fork conserva `auto` salvo CPU debanding explícito (`auto-copy`) y no aplica
  esa restricción de versión en `initOptions`.
  El wrapper nativo puede influir en la selección efectiva: registrar
  `hwdec-current`, dropped frames, consumo y temperatura en el binario usado.
  Una ruta de copia puede aumentar tráfico de memoria. No presentar HW copy como
  más rápido o de menor consumo para todos los dispositivos.
- [ ] **Presets Device/Low/Mid/High, Anime4K e interpolación.**
  [fc465bea](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/fc465bea),
  [3087c249](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/3087c249),
  más correcciones `7e569b3d` y `91a8a12b`.
  HEAD contiene `DecoderPreset`, `Anime4KShaderPreset` y `MotionInterpolationMode`,
  con cadenas de shaders y opciones mpv de interpolación. Son funciones visuales
  opcionales; pueden incrementar consumo/GPU y cambiar la apariencia del anime.
  Portar assets/licencias, rutas, UI y fallbacks, no solo preferencias.
  Los modos Auto/Always de interpolación devuelven las mismas opciones en la clase
  inspeccionada: no asumir ajuste inteligente por carga de GPU.

## 3. Usabilidad — funciones opcionales

- [ ] **Recordar selección de calidad/doblaje por anime y preguntar al abrir episodio.**
  [ae8e0d10](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/ae8e0d1070bb72918e933cb140d8f4e0bec570cc),
  [d8850634](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/d8850634a6fb930fb611aa8aec012a137fd80cf1).
  La implementación inicial recuerda el título exacto del vídeo; HEAD incorpora
  también `PlaybackSelectionResolver` con metadata `playerId`/doblaje y reglas
  específicas de CDN/Kodik/Parlorate. El `Hoster` local no expone esa metadata.
  Diseñar una preferencia genérica, tolerante a títulos cambiantes, ausencia del
  servidor elegido y cambios de fuente. No importar reglas de proveedores como
  estándar global ni cambiar `source-api` sin revisar ABI/serialización.
  Una clave por ID local de anime requiere decidir cómo se remapea tras restaurar
  backups; no basta con incluirla en el backup de preferencias.
- [ ] **Mantener pulsado y deslizar para acelerar, con restauración al soltar.**
  [0f35b00f](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/0f35b00f),
  [16efec6e](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/16efec6e),
  [077fc8c3](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/077fc8c3b37f81f29e4a4e4cfdfdf7b770a9dd3f).
  Añade selección dinámica, histéresis de pasos y modo temporal por defecto.
  Localmente la pulsación larga abre screenshots. Hacer configurable la acción,
  mantener accesible screenshots y probar cancelación, bloqueo, paneles y multitouch.
- [ ] **Editor de controles por orientación.**
  [ef85c9a9](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/ef85c9a9).
  HEAD tiene pantallas layout Portrait/Landscape y configuración propia.
  Es una migración de UI, no una mejora de buffering. Probar accesibilidad,
  controles bloqueados, gestos, insets y PiP. Comparación preliminar.
- [ ] **Traducción de subtítulos.**
  [d73af0c0](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/d73af0c0),
  [a446bec8](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/a446bec8),
  [1afc05b6](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/1afc05b6).
  HEAD incluye selección de proveedor/idioma, coordinación, caché y extracción
  de pistas embebidas locales. Requiere un proyecto propio: tiempos SRT/ASS,
  preservación de estilos, red, cancelación, caché, credenciales y decisión explícita
  de enviar texto a proveedores externos. No se ejercitó ni se validó la calidad.

## 4. Integración con extensiones — estudio adicional

- [ ] **Combinar cabeceras de fuente y vídeo sin distinguir mayúsculas/minúsculas.**
  [PlayerHttpHeaders en el snapshot](https://github.com/andarcanum/Tadami-Aniyomi-fork/blob/d8442c8129e66bcee0b4d88050b13799c9728cdf/app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerHttpHeaders.kt).
  Localmente `setHttpOptions` escoge `video.headers ?: source.headers`; si el vídeo
  trae solo cabeceras parciales, descarta las de fuente. Tadami combina ambas,
  permite que vídeo sobrescriba fuente y separa User-Agent/Referer para mpv.
  Revisar si alguna extensión depende de reemplazar las cabeceras enteras, limpiar
  estado entre vídeos y probar cookies/redirecciones. El filtro `X-AnimeGO-*` de
  Tadami es específico de su ecosistema y no justifica copiar toda su política.
- [ ] **Revisar ABI Hoster y errores en el límite de extensiones.**
  [91a79bd0](https://github.com/andarcanum/Tadami-Aniyomi-fork/commit/91a79bd0a4bcd07b5afdf5ea1c843d4e792bddfd).
  Tadami restaura un constructor de cuatro argumentos y maneja linkage errors en
  carga de episodios. El constructor principal local contiene cinco parámetros
  incluido `lazy`. Hacer una auditoría binaria con extensiones reales y firmas JVM;
  no concluir que todas estén rotas ni reemplazar `Hoster` con el de Tadami.
  Mantener cancelación y no absorber errores fatales de forma indiscriminada.

## Orden y validación

1. Completar validación nativa/visual de la primera tanda implementada.
2. Diseñar el plan de buffering del TODO del reproductor sin alterar su watchdog.
3. Cabeceras y selección recordada de calidad; auditar ABI si se detecta un caso real.
4. Medir hardware decoding y recién entonces elegir defaults/presets.
5. Gestos/editor y, como proyecto separado, traducción/Anime4K/interpolación.

Adaptar cada parche a los recursos AYMR y al DI del fork, preservar identidad y
cambios propios. Mantener tests significativos de filtros, resultados externos,
selección y cabeceras. Ejecutar checks de módulo y, en cambios transversales,
`spotlessCheck`, `testReleaseUnitTest` y `:app:assembleDebug`; añadir release/R8 y
comprobación 16 KB si se cambian binarios nativos. Validar en dispositivo con MP4,
HLS, subtítulos, cambio de episodio/servidor, recuperación, PiP y Android antiguos.
No reutilizar como mediciones nuevas las cifras históricas del otro TODO.


## Registro inicial de validación de la primera tanda — 2026-10-19

Este registro precede al ensayo conjunto en emulador descrito al final.

- `./gradlew :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.player.*'`:
  **25 tests pasaron** (16 nuevos de filtros/decoder/resultados/salud y 9 existentes
  de errores de HosterLoader). No son ensayos de mpv nativo ni Activity.
- `./gradlew spotlessCheck testReleaseUnitTest :app:assembleDebug`: **correcto**.
  Suite release completa y APKs debug generadas; sin cambios en dependencias,
  binarios nativos ni `source-api`, por lo que no se repitió el análisis 16 KB.
- `git diff --check` y revisión de referencias locales: correctos. Índice comparado
  byte a byte con el inicial; las entradas preparadas del usuario permanecen iguales.
- El primer intento de Gradle en sandbox falló por la caché de solo lectura; se
  ejecutaron los checks con acceso autorizado a la caché/SDK locales. No hay un
  bloqueo de compilación pendiente. Persisten avisos de APIs deprecadas y Gradle 9.
- ADB confirmó un emulador `sdk_gphone16k_x86_64` conectado. No se instaló ni se
  ensayó la APK en él: falta preparar un escenario reproducible con episodio,
  MP4/HLS, scripts/fuentes y reproductores externos. Un emulador tampoco valida
  el consumo ni MediaCodec del dispositivo físico del usuario.
- **Pendiente de dispositivo/emulador:** ambas opciones de autoplay al EOF normal,
  EOF sin contenido y failover con posición conservada; pausa/seek/reanudación;
  mpv con CPU/GPU debanding, YUV y `hwdec-current`; scripts/shaders/fuentes;
  retorno vacío/cancelación/finalización de reproductores externos con historial;
  notch, controles, rotación, pantalla dividida y PiP/media session. No hay
  verificación visual, medición nueva ni afirmación de mejora de buffering.

PlaybackHealth, watchdog continuo/acumulado, overlay/cuenta regresiva, failover,
cancelación temporal de descarga y coordinación/pacing no cambian. Las secciones
2–4 y el plan de buffering siguen pendientes; esta tanda no los implementa.


## Segunda tanda — diagnóstico y estado de sliders, 2026-10-19

Alcance acordado: los dos pendientes pequeños del TODO local, sin cambios de
buffering, selección de servidor/calidad, defaults de decoder ni funciones visuales
nuevas de Tadami.

- `HosterLoader.getResolvedVideo` registra a nivel WARN el ID numérico de fuente y
  los tipos de error/causas cuando absorbe una excepción y devuelve null. Omite el
  mensaje de excepción y stacktrace para evitar URLs firmadas, cookies o secretos.
  La cancelación se relanza antes del registro; se conserva la propagación optativa
  de challenges/errores transitorios y el comportamiento de descargas. Alcance anime.
- `PreferenceItem` pasa `enabled` a `BaseSliderItem`, que también lo pasa al slider
  Material. El parámetro nuevo tiene default true; no cambia otros consumidores.
  `StatusWrapper` conserva el ocultamiento de ajustes deshabilitados y ahora el
  slider tampoco admite interacción durante su animación de salida. La corrección
  compartida alcanza el timeout de servidores del reproductor y duración/intervalo
  E-Ink del Reader de manga; no altera sus valores ni comportamiento de lectura.
- `HosterLoaderDiagnosticsTest`: cuatro pruebas de diagnóstico sin secretos, causas
  cíclicas acotadas, cancelación en ambos modos y resolución/fallback. Se conservan
  los nueve tests existentes de errores/propagación. La UI del slider se valida en
  emulador, sin añadir un test unitario que solo replique el paso de un parámetro.
- `spotlessCheck`, `:app:assembleDebug` y `:app:testDebugUnitTest` filtrado a
  `eu.kanade.tachiyomi.ui.player.*`: correctos, **29 tests** del reproductor.
- `./gradlew testReleaseUnitTest`: correcto, **372 tests**, sin fallos ni omitidos.
  `git diff --check` correcto e índice idéntico al inicial.
- El ensamblado emitió avisos D8/R8 8.9.32 al reescribir metadata Kotlin
  (`Should never be called`), además de deprecaciones, pero terminó correctamente.
  No se actualizaron dependencias/build tools en esta tanda ni se validó release/R8.

## Validación conjunta en emulador — 2026-10-19

Subagente dedicado, APK debug x86_64 actualizada sin borrar datos, paquete
`xyz.b1ack0u7.aniyomi.mi.dev`, emulador `sdk_gphone16k_x86_64`, Android API 36,
1080×2400 y páginas de 16 KB. No es una validación de alineación de todas las ABI.

| Escenario | Resultado observado | Evidencia local |
| --- | --- | --- |
| Tensei shitara Slime Datta Ken 4th Season, Jkanime, episodio 16 | Imagen y subtítulos visibles; pausa a 04:57, reanudación y seek hacia créditos | `player-now.png`, `paused.png`, XML de controles |
| CPU debanding + YUV en software | Durante el vídeo real, `vf` conserva gradfun(radius=12) y format(yuv420p), `hwdec-current=no`, posición avanza | `native-observed.jsonl` |
| Hardware solicitado + CPU debanding | MP4 sintético local de 14 s: `hwdec=auto-copy`, `hwdec-current=mediacodec-copy`, `vf` solo gradfun; no filtro format | `native-hwcopy.jsonl` |
| EOF natural con autoplay desactivado | Tras file-loaded real se sustituyó por clip local de 14 s: avance 7.916 s con pausa false, EOF a 13.958 s con pausa true, sin seek ni overlay de carga | `natural-eof.jsonl`, `natural-eof-ui.png` |
| Timeout de cambio automático | OFF oculta fila; ON conserva 20 s; gesto habilitado cambia a 53 s. No hay nodo disabled estable por el wrapper de visibilidad | XML `timeout-*` |
| Sliders E-Ink de manga | OFF oculta ambos; ON permite cambiar duración 100→1200 ms e intervalo 1→8 páginas | XML `eink-*` |

La carga inicial real fue variable (aproximadamente un minuto); otro arranque no
llegó pronto a file-loaded y motivó el fixture local. No se atribuye a un cuello
de botella concreto ni se extraen métricas de buffering. Un primer intento de EOF
sintético compitió con la carga de fuente y fue descartado como evidencia limpia;
el último esperó a file-loaded real antes de cargar el clip. El EOF acreditado es
funcional/nativo con fixture: no prueba progreso correcto del anime de 24 minutos,
cobertura aislada del método privado ni media session.

**Pendiente al finalizar ese ensayo** (PiP/cutout/rotación se actualizan en el
seguimiento posterior): GPU debanding y matriz visual completa; consumo/temperatura en
hardware físico; HLS, EOF sin contenido y failover con posición preservada; autoplay
con cambio de episodio; PiP, cutout/notch, barra de estado, aspecto, rotación y
pantalla dividida; retornos de reproductores externos y errores reales de extensión.
Los intentos de PiP no demostraron modo pinned (`PictureInPictureMode=false`), de
modo que no se dan por correctos. Decoder desconocido, resultados externos y
cancelación/diagnóstico conservan cobertura unitaria, sin ensayo end-to-end aquí.
Las copias de scripts/opciones/shaders/fuentes tienen revisión de `use` y compilación,
pero no una prueba específica de descriptores. El observador Lua se cargó desde un
archivo temporal externo a la carpeta recreada de scripts.

Respaldo de databases/shared_prefs/files previo a la instalación; sin asociaciones
de trackers de anime en ese estado. Tras las pruebas se restauraron **21/21 archivos
con SHA256 idéntico**, sin faltantes, diferencias ni añadidos en esas carpetas.
Se retiraron Lua/MP4 temporales y transferencias; la app quedó detenida, con la APK
debug nueva instalada. Preferencias, historial y progreso de prueba quedaron restaurados.

Informe y evidencias sanitizadas de esta sesión:
`/tmp/aniyomi-player-emulator-batch2/report.md`, JSON y capturas de ese directorio.
Es una ruta temporal, no un artefacto versionado. El respaldo privado y logcat bruto
no forman parte del informe porque pueden contener datos de sesión. No se modificó
producción ni Git durante el ensayo del subagente.


## Seguimiento de PiP, cutout y rotación — 2026-10-19

Correcciones posteriores a la validación conjunta anterior:

- `PlayerOrientation.toActivityOrientation` corrige ReverseLandscape, que solicitaba
  REVERSE_PORTRAIT. El emulador confirmó antes del cambio la orientación vertical
  inversa al seleccionar horizontal inverso. `PlayerOrientationTest` cubre las
  familias horizontal/vertical, orientación libre y selección según el vídeo.
- `PlayerActivity.updatePipParams` aplica los parámetros también cuando cambia el
  aspecto del vídeo (antes se construían y descartaban). La entrada automática
  ahora exige soporte, PiP habilitado, opción al salir y reproducción activa;
  desactivar PiP prevalece sobre la preferencia de entrada automática. Se pasan
  parámetros actuales también en Home/Back. El cálculo de tamaño PiP omite valores
  ausentes, no finitos o inválidos sin usar `!!`. `PlayerDisplayTest` comprueba
  condiciones de entrada y proporciones válidas/ausentes/fuera de rango.
- `PlayerViewModel.changeVideoAspect` calcula Stretch con la superficie efectiva
  del reproductor, en lugar de las dimensiones físicas del display. Se reaplica
  el aspecto guardado al cargar un archivo y cambiar tamaño fuera de PiP; al volver
  de PiP se solicita reaplicación. El callback de configuración llama primero a
  super; el callback de layout corrige el tamaño definitivo. No vuelve a guardar
  la preferencia ni mostrar aviso cuando el valor no cambia.
- `PlayerActivity.setupPlayerLayout` reserva el cutout en el contenido cuando la
  opción de mostrar vídeo en el recorte está desactivada en Android 15+; consume
  solo ese inset antes de entregarlo a Compose para evitar doble padding. Con la
  opción activa permite vídeo hasta el recorte, pero `PlayerControls` protege los
  controles con `displayCutoutPadding`. El fondo del reproductor es negro.
  [Android documenta que NEVER y SHORT_EDGES se interpretan como ALWAYS](https://developer.android.com/develop/ui/views/layout/display-cutout)
  en ventanas no flotantes con target 35+ desde Android 15; reasignar atributos por
  sí solo no permite mantener la opción de exclusión del recorte. Se conserva el
  manejo independiente de diálogos/sheets EdgeToEdge del fork.

Estas rutas son específicas de anime/mpv. Reader tiene orientación y setter de
cutout propios (`ReaderConfig.setCutoutShort`), sin PiP ni Stretch de vídeo. La
misma imposición de plataforma puede afectar su opción cutout; no se validó ni se
modificó aquí y requiere revisión independiente, con referencia a Mihon si se corrige.

Checks finales: `spotlessCheck`, `:app:assembleDebug` y **43 tests** de reproductor
con `:app:testDebugUnitTest`, correctos; `testReleaseUnitTest`, **386 tests**, sin
fallos/errores/omitidos. Catorce tests nuevos en `PlayerOrientationTest`,
`PlayerDisplayTest` y `SurfacePlaybackQueueTest`. Los avisos D8 de metadata Kotlin ya registrados persisten,
sin impedir el ensamblado. No se cambiaron dependencias ni se validó release/R8.

Durante los ensayos apareció un cierre nativo antes del primer file-loaded con
el MP4 local de resolución inmediata: `android_common.c:47`, aserción de WinID
inválido en `vo_android_init`. Se reprodujo en **2/4 arranques** sin el gate; hubo
fallos tanto en frío como después de abrir MainActivity. El intento caliente no
constituye un arreglo.

`BaseMPVView` de la biblioteca 1.18.n ya pospone `playFile` hasta surfaceCreated y
advierte sobre inicializar salida antes de adjuntar la superficie; Activity usa
comandos loadfile propios para conservar opciones. `SurfacePlaybackQueue` y los
callbacks de `AniyomiMPVView` ahora posponen tanto cargas como reaplicación de aspecto
hasta después de adjuntar la superficie. Las tres rutas loadfile (normal y dos de
torrent) pasan por el gate en el hilo principal. Solo se conserva la selección
vigente; generaciones y comprobación de episodio/vídeo descartan respuestas tardías,
y la cola se invalida al cambiar episodio/Intent o destruir. Se conserva el decoder
por defecto y la biblioteca nativa; la protección no usa esperas fijas.

El watchdog se arma cuando existe superficie y empieza la resolución/apertura;
sigue cubriendo resolución torrent y apertura del stream. Esperar a una superficie
no se carga como falta de datos del servidor. `PlaybackHealth` se reinicia para la
carga vigente; se conservan failover, pausa, progreso y argumentos loadfile.
`SurfacePlaybackQueueTest` cubre selección más reciente, respuesta antigua,
reconexión, inactividad/reanudación, limpieza, argumentos y reentrada.

### Resultado observado en emulador

La fuente volvió a atascarse antes de file-loaded. Se usó un MP4 sintético de 14 s
con loop, cargado mediante un APK de instrumentación temporal firmado debug y el
contrato existente de `PlayerActivity.newIntent`/hostList inicializado. No se
exportó la Activity ni se añadió una API productiva. Lua solo observaba propiedades
en las repeticiones con gate: no inyectaba loadfile ni modificaba pause.

| Caso | Resultado |
| --- | --- |
| PiP manual previo | Funciona con entrada automática desactivada; dumpsys pinned y ventana pequeña. La falta de evidencia anterior era una limitación del ensayo |
| ReverseLandscape | Antes REVERSE_PORTRAIT (9), después REVERSE_LANDSCAPE (8) y pantalla 2400×1080 |
| Cutout desactivado | Antes patrón hasta y=0 pese safeInsetTop=132; después margen negro de 132 px, superficie útil 2268×1080 y Stretch=2.1 en horizontal inverso |
| Cutout activado y controles | Vídeo 1080×2400 en vertical; título comienza y=185, fuera del inset de 132, frente a y=53 anterior |
| Giro en vivo | Con Free y clip continuo, 2400×1080/Stretch=2.222 → 1080×2400/0.45 → horizontal/2.222 |
| Fit/Crop/Stretch | Fit conserva barras; Crop usa panscan=1; Stretch usa dimensiones útiles sin barras internas |
| PiP automático y controles | Home con PiP y opción al salir activados produce pinned; pausa detiene tiempo en 4.833 s y play lo lleva a 7.791 s |
| Retorno desde PiP | Se recuperan 2268×1080 y Stretch=2.1, sin quedarse con el tamaño de la ventana pequeña |
| PiP desactivado | Home no entra en pinned, tanto antes como después. La corrección del gate de parámetros se fundamenta en inspección y tests; no se atribuye a un fallo funcional reproducido de esa combinación |
| APK definitiva con gate | Tres arranques fríos completados con avance real y proceso vivo; dos intentos bloqueados por cierre completo del emulador host. No se afirma 5/5 |
| Prueba breve adicional de APK definitiva | Vídeo activo, giro 2400×1080/2.222 → 1080×2400/0.45 y Home → pinned |

La matriz de pantalla se ensayó primero con las correcciones de display, antes
de añadir el gate. Después se repitieron arranques y la prueba breve con la APK
definitiva; su SHA256 coincide con la instalada. Un observador Lua perdió el
file-loaded inicial de uno de los tres arranques finales, pero había duración de
14 s, frames y time-pos avanzando. No se confunde esa ausencia de log con fallo.

### Bloqueos del entorno y cierre

Se cerró varias veces el proceso HOST del emulador/ADB, no solo el proceso app.
Hubo errores gfxstream/EGL; SwiftShader también falló al arrancar, sin app abierta,
con SIGSEGV en su libGLESv2. No se registró OOM. Se recuperó el mismo AVD sin wipe,
sin cargar/guardar snapshots ni modificar drivers Fedora, mediante flags temporales:
GLES host, Vulkan desactivado, sin ventana y cámaras virtuales desactivadas. Los
arranques finales no son una comparación estadística ni de consumo entre renderers.

Un intento de activar tall cutout coincidió con el primer cierre host; el overlay
quedó desactivado y se usó el recorte original del perfil Pixel8 (132 px). Se
comprobaron preferencias reales después del reinicio y se descartaron capturas
cuyas preferencias copiadas antes del cierre no habían persistido. Los cierres
posteriores también afectaron al registro del paquete debug: se reinstaló la APK
definitiva y se restauró el respaldo en su UID actual.

Restauración comprobada: **21/21 archivos originales con SHA256 idéntico**, sin
faltantes, diferencias ni añadidos en databases/shared_prefs/files; overlays,
accelerometer_rotation=1, user_rotation=0 y sensor originales restaurados. Harness
desinstalado, fixtures/Lua/transferencias retirados, app detenida. ADB quedó online;
el emulador continúa **sin ventana** por la recuperación, con la APK definitiva.
UID original y actual coinciden (10228), y el appop PiP conserva Default allow.
No se capturó baseline de permisos runtime/grants URI ni appops completos; la
verificación 21/21 acredita el contenido del respaldo, no todas las concesiones
Android externas a él. El informe explicita esta limitación sin publicar URIs.

**Pendientes reales:** publicación de estado/progreso de MediaSession (dumpsys
mostró NONE/posición 0 durante reproducción, aunque los controles personalizados
PiP sí funcionaban); pantalla dividida, Android anteriores/tablets y hardware
físico; HLS, failover/EOF sin contenido, autoplay, retornos externos, copias de
streams y matriz GPU. Las rutas torrent del gate se revisaron en código y comparten
la cola cubierta por tests; no se realizó reproducción torrent en el emulador.
Reader conserva pendiente su auditoría de cutout independiente. No se afirma una
medición nueva de rendimiento ni se cambian los planes de buffering.

Informe y evidencias sanitizadas:
`/tmp/aniyomi-player-emulator-display3/report.md`, JSON y capturas de ese directorio.
El respaldo y logs privados completos no se incluyen como evidencia pública.


## MediaSession y validación pendiente — 2026-10-20

Se reprodujo el fallo con la APK anterior y un MP4 sintético de 40 s: mpv avanzaba
mientras MediaSession publicaba NONE, posición/velocidad/timestamp cero y metadata
null. `setupMediaSession` solo anunciaba acciones; nunca actualizaba su estado.

`PlayerSessionState` separa carga, pausa, buffering, seek y EOF de los indicadores
visuales compartidos. `PlayerActivity` publica snapshots desde los observers mpv,
convierte segundos a milisegundos y utiliza `SystemClock.elapsedRealtime()`;
velocidad cero evita extrapolar progreso en pausa/espera. Se actualizan título de
episodio, álbum/anime y duración. La carga limpia metadata y tiempos anteriores;
un cambio de servidor conserva posición, y file-loaded lee tiempo/duración activos
incluso si dos archivos tienen la misma duración. Un cambio de episodio inválido
recupera el snapshot actual en vez de dejar BUFFERING indefinido.

La pausa de Activity publica el estado antes de activar `isExiting`, pues después
los observers se ignoran. Stop multimedia pausa y cierra la Activity; ya no llama
manualmente a `Activity.onStop`. Se conservan las preferencias de acciones multimedia.
No se anuncia seek del sistema: la sesión refleja los seeks del reproductor, pero
no se añade un callback `onSeekTo` ni esa función nueva. Alcance anime/mpv; Reader
no usa esta sesión y no se modificó.

Checks de implementación:

- `:app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.player.*'`: 48 tests,
  sin fallos, en el primer build. Cinco nuevos tests de `PlayerSessionStateTest`
  cubren carga, pausa durante cache/seek, velocidad, EOF, reset y tiempos inválidos.
- APK definitiva: `testReleaseUnitTest` y `:app:assembleDebug` correctos; 391 tests
  release sin fallos, errores ni omitidos. Persisten avisos de APIs deprecadas.
- `spotlessCheck` global bloqueado por formato preexistente de
  `eu/kanade/presentation/util/ExceptionFormatter.kt`, sin cambios en esta tarea.
  `:app:spotlessKotlinCheck` con target temporal restringido a los tres archivos
  Kotlin de MediaSession: correcto. `git diff --check`: correcto.
- No se cambian dependencias, binarios nativos, `source-api`, defaults de decoder
  ni políticas de buffering/failover; no se ejecutó release/R8 ni análisis 16 KB.

Resultados funcionales observados en emulador API 36 x86_64 con fixtures locales:

| Escenario | Resultado |
| --- | --- |
| MediaSession MP4 | PLAYING con posición/timestamp actualizados; PAUSED con velocidad 0; reanudación y velocidad 1.5× correctas |
| Segundo plano sin PiP y Stop | Home publica PAUSED; Stop cierra el reproductor y elimina su sesión del stack del sistema |
| HLS local | Reproducción, pausa, seek a 20 s mientras pausado, reanudación y EOF natural a 39.98 s con autoplay OFF |
| Autoplay | MP4 de 5 s termina naturalmente y abre episodio 2 de LocalAnimeSource por SAF; segundo vídeo avanza y cambia metadata de sesión |
| Buffering y failover | Segmento HLS bloqueado tras avanzar a 3.69 s: BUFFERING, posición 3000 ms y velocidad 0; watchdog cambia a MP4 cerca de 3.50 s, avanza y vuelve a PLAYING |

Los fixtures verifican comportamiento funcional, no rendimiento de proveedores
reales, adaptación ABR ni consumo físico. El pequeño ajuste de posición durante el
failover corresponde al seek nativo observado; no se afirma precisión de frame.
Resultados externos mediante Activity temporal que devuelve resultados al launcher
real de MainActivity (sin reproductores de terceros instalados):

- RESULT_OK con Intent null conserva 20/100 s, sin crear historial.
- RESULT_OK con Intent vacío conserva 20/100 s y crea historial.
- RESULT_OK con posición/duración 0/0 conserva 20/100 s y crea historial.

Tres cierres del proceso host del emulador/gfxstream interrumpieron la matriz;
uno ocurrió sin vídeo abierto. Se recuperó el mismo AVD con flags temporales, sin
wipe ni snapshots; no se atribuyen esos cierres a la app. Un MP4 HTTP de prueba
sin Range/faststart no fue un ensayo válido de autoplay y se reemplazó por archivo
seekable. Tampoco acredita EOF sin contenido: apareció eof en un sondeo, pero no
se observaron el evento `eof-reached=true` y la recuperación requeridos.

**Pendientes al cerrar este primer ensayo (continuación debajo):** EOF sin
contenido/failover por esa ruta; cancelación y finalización o progreso positivo
externos; MediaSession dentro de PiP. Tampoco se validan hardware
físico, reproductores de terceros reales, Android anteriores ni la matriz GPU,
copias de streams o pantalla dividida de las tandas previas. Las pruebas anteriores
de PiP no sustituían la publicación de estado/progreso de sesión ahora corregida.

Restauración final: **21/21 archivos originales con SHA256 idéntico**, sin
faltantes, añadidos ni diferencias en databases/shared_prefs/files. Las líneas de
permisos/concesiones del package dump y el listado de overlays coinciden con el
baseline; globals accelerometer_rotation=1/user_rotation=0 originales. No se capturó
baseline completo de appops/grants URI persistidos, así que no se afirma igualdad
completa fuera de esos ámbitos. PiP quedó en Default allow.

Se retiró la carpeta SAF de fixtures mediante DocumentsContract y se repitió la
restauración/verificación de los 21 archivos. Harness desinstalado, transferencias
y fixtures retirados, reverse ausente y servidor local detenido; app detenida,
APK definitiva instalada. ADB online; AVD permanece **sin ventana** tras recuperar
el entorno, sin cambios persistentes de configuración/driver. La APK instalada y
el ensamblado final comparten SHA256
`d17d441867e1264e740917820df7131471b2f4e6db6d366fba95fc23d72086f9`.

Informe y evidencias sanitizadas:
`/tmp/aniyomi-player-media-session/report.md`, `restoration.json`, observaciones
nativas y dumps de sesión de ese directorio. Es una ruta temporal no versionada;
backups, package dumps completos y logcat bruto quedan privados. El índice Git
permanece idéntico al inicial; los cambios de esta tarea quedan sin stage/commit.


## Continuación de validación y corrección de pausa al EOF — 2026-10-20

Se retomó el mismo AVD y la APK anterior, con un nuevo respaldo independiente.
El usuario se encarga de reiniciar el emulador si se detiene; esta continuación
no autoriza tratar un cierre host como fallo de la app.

Los tres retornos externos pendientes pasaron mediante Activity temporal y launcher
real de MainActivity, comprobando SQLite: RESULT_CANCELED con 80/100 ignora extras
y conserva 20/100 sin visto/historial; RESULT_OK positivo actualiza a 80/100 sin
marcar visto y crea historial; `playback_completion` llega a 100/100, marca visto
y crea historial. Se usaron extras Int; Long conserva cobertura unitaria, sin
atribuirle un ensayo end-to-end aquí. Son resultados controlados, no reproductores
de terceros reales.

MediaSession dentro de PiP también pasó con la APK anterior: pinned y
PictureInPictureMode=true, vídeo avanzando y PLAYING con posición actualizada;
pausa vía MediaSession publica PAUSED/velocidad 0 y play reanuda. El botón real de
pantalla completa devuelve fullscreen y conserva avance/estado coherentes.

El ensayo EOF descubrió un fallo reproducible: MP4 seekable de 0.75 s, con
menos del segundo de progreso requerido por PlaybackHealth, emitió un verdadero
`eof-reached=true`. Autoplay estaba ON y se conservó correctamente el episodio al
seleccionar el fallback, pero este permaneció pausado a 0 durante más de 20 s.
No se inyectó un callback de fallo/EOF; el observer pasivo se instaló antes de abrir
la Activity. El fixture ejercita la clasificación de progreso insuficiente; no
representa por sí solo un error real de decoder ni un archivo de cero frames.

Causa: keep-open pausa mpv al EOF; el observer llamaba a `viewModel.pause()` y
`loadVideo` guardaba esa pausa automática como estado que debía restaurarse.
`PlaybackPauseState` separa intención, pausa temporal de carga y estado observado.
Las órdenes explícitas pause/play actualizan intención incluso durante carga;
la carga pausa sin modificarla y la restaura tras preparar tracks. El observer
sincroniza estado sin reescribir mpv y no recuerda la pausa automática si la
propiedad EOF vigente está activa, aunque su callback todavía no se haya procesado.
Una orden directa de play fuera de carga sí se recuerda incluso con EOF activo.
Los cambios directos mpv de Lua/teclas se reconocen fuera de carga; no se afirma
soporte completo de esas órdenes durante la pausa temporal de resolución/carga.
La selección de un episodio nuevo conserva su intención inicial de reproducir.
Alcance anime/mpv: no modifica Reader, watchdog, selección de candidatos ni source-api.

Ocho tests nuevos de PlaybackPauseStateTest cubren pausa automática al EOF frente
a pausa manual, restauración única, pausas temporales/fallbacks encadenados, órdenes
explícitas durante carga, órdenes directas fuera de carga, play al EOF y nuevo
episodio. `testReleaseUnitTest`: **399 tests sin fallos, errores ni omitidos**;
`:app:assembleDebug` y `:app:spotlessKotlinCheck` con target temporal restringido a
los cuatro archivos Kotlin de esta corrección: correctos. El fallo global previo
de formato de ExceptionFormatter queda fuera de esta tarea. No se cambiaron
binarios/dependencias ni se ejecutó release/R8 o alineación 16 KB.

### Validación de la APK corregida

APK x86_64 SHA256
`26062c38c613653e30ad2f1248cd3daa7c4e1f9348642c4a186a3fc7565ed2d7`.
Los resultados externos y el primer PiP anteriores corresponden a la APK previa;
la tabla siguiente repite las rutas de reproducción afectadas con la APK corregida.

| Escenario | Resultado observado |
| --- | --- |
| EOF con menos de 1 s de progreso, autoplay ON | Mismo MP4 de 0.75 s y evento EOF real; fallback largo avanza aproximadamente 0.5→9.54 s, pause=no, episodio 1 y MediaSession PLAYING |
| Pausa manual y selección de otro candidato | Pausa a 15 s y acción real onVideoClicked; nuevo MP4 conserva pausa/posición 15 s y sesión PAUSED/15000 ms/velocidad 0; Play reanuda |
| EOF normal, autoplay OFF | Seek a 37 s y EOF natural a 39.958 s; mantiene episodio 1, pausa y sesión PAUSED/39000 ms/velocidad 0 |
| Autoplay y siguiente episodio | Episodio de 5 s termina naturalmente; episodio 2 resuelto por SAF reproduce y se actualiza la metadata |
| PiP y regreso a pantalla completa | Pinned/pip=true con sesión PLAYING a 24 s; pausa PAUSED/24 s/0, play PLAYING/26 s/1; botón real fullscreen devuelve pip=false y avance coherente cerca de 29 s |
| HLS sano: pausa, seek y reanudación | Seek a 10 s durante pausa publica PAUSED/10000 ms/0; play vuelve a PLAYING/11000 ms/1 |
| HLS bloqueado: stall y fallback | Avance 2.86→3.69 s, cache stall y BUFFERING; watchdog recupera al HLS sano desde aproximadamente 3.90 s, avanza a 23.90 s con pause=no y PLAYING |

Un intento combinó seek con un segmento bloqueado y no se utilizó como prueba de
seek normal: la operación dependía de que respondiera la red. Se separaron ambos
escenarios para acreditar seek sano y recuperación del stall. El smoke de autoplay
solo preparó dos episodios: el intento posterior de cargar el tercero queda fuera
de la evidencia de transición y PiP. No hubo cierres del host durante este
seguimiento ni se necesitó reiniciar el emulador.

Con esto quedan cubiertos en emulador los casos concretos que estaban pendientes
al cerrar el primer ensayo. Permanecen las limitaciones generales: reproductores
de terceros reales y sus extras particulares, fallos de decoder reales, hardware
físico/consumo, Android anteriores/tablets, pantalla dividida, GPU/copias de streams
y torrents. No hay nuevas mediciones de rendimiento ni cambios del plan de buffering.

Restauración final de esta continuación: **21/21 archivos SHA256 idénticos**, sin
faltantes, añadidos ni diferencias. Coinciden los permisos del package dump,
overlays, dump completo de URI grants y modos appops con el nuevo baseline;
los tiempos de uso de appops cambian por ejecutar las pruebas y no forman parte
de esa igualdad. El baseline ampliado pertenece a esta continuación y no acredita
retroactivamente el estado anterior al primer ensayo.

Carpeta SAF de fixtures eliminada, harness desinstalado, transferencias/reverse
retirados, servidor detenido y app detenida. ADB online; la APK corregida instalada
coincide con el SHA256 anterior. No se reinició el AVD durante este seguimiento.
El índice Git sigue idéntico al inicial de la continuación; cambios propios sin
stage/commit. `git diff --check` y referencias locales de ambos TODO: correctos.

Informe sanitizado y evidencias de esta continuación:
`/tmp/aniyomi-player-media-session-followup/report-followup.md` y
`restoration-followup.json`. Respaldos y logs brutos quedan privados; estas rutas
son temporales, no artefactos versionados.
