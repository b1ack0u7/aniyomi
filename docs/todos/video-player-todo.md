# Reproductor de vídeo — trabajo pendiente

Revisado el **2026-10-19** contra el código actual de `dev` (HEAD `ba6adedac`, con
cambios preparados en otras áreas). El seguimiento del **2026-10-20** implementa buffering y límites sobre `dev`
(HEAD `a2f17768c`); diseño, checks y matriz se registran al final. Sigue vigente
como plan de salud de reproducción y recuperación. La comparación de correcciones y funciones con Tadami se
conserva en [Tadami player improvements](tadami-player-improvements-todo.md).

Las dos primeras tandas de correcciones pequeñas se implementaron el mismo día;
su alcance, pruebas y validación conjunta en emulador están en la sección 7 y en el
TODO de Tadami. Las cifras de rendimiento de la sección siguiente proceden de la
investigación histórica descrita en la versión anterior del documento; no se
conservaron aquí logs que permitan reproducir todas sus conclusiones. La validación
funcional nueva no confirma esas cifras ni justifica nuevos defaults.

## Contexto histórico y correcciones ya presentes

Investigación original: fuente Jkanime, episodio 16 de *Tensei shitara Slime Datta
Ken 4th Season*, dispositivo físico y emulador.

| Dato histórico | Resultado registrado |
| --- | --- |
| Servidor sano, ventana de tres minutos | 24 fps, cero frames perdidos, avsync 0, cero stalls |
| Caché de readahead en servidor sano | Hasta 138 segundos |
| Throughput de servidor problemático | 126–258 KiB/s, aproximadamente 1–2 Mbps |
| Picos de bitrate 1080p H.264 | 1.508–1.924 kbps |
| Resolución legacy `getVideoList()` | 30–49 s, unos 15 servidores y 28 peticiones |
| Stalls intermitentes | Nueve en 76 s, ninguno por encima del timeout individual |

Estas cifras no describen todos los servidores, fuentes, códecs o dispositivos.
El bitrate y el throughput deben compararse en las mismas unidades y ventanas;
una etiqueta de calidad o velocidad en el título no es una medición fiable.

Correcciones comprobadas en el código actual, sin volver a medir su efecto:

- Failover cuando mpv termina un archivo sin haber reproducido contenido, conservando
  la distinción frente a una finalización normal (`PlaybackHealth`, `endFile`).
- Watchdog de stall continuo y acumulado, configurable, por defecto 20 segundos.
- Overlay con servidor y cuenta regresiva de cambio; no muestra actualmente una
  medición de "N segundos de búfer listos".
- Eliminación del doble `sortVideos()` de la ruta legacy, commit `a1a87da78`.
- yuv420p aplicado solamente cuando no se solicita hardware decoding, `bb01541df`.
- Cancelación temporal de la descarga en cola del episodio mientras se reproduce,
  `a7fac49b4`, y coordinación de solicitudes añadida posteriormente.

## 1. Búfer antes de reanudar — implementado y validado en emulador

- [x] Añadir colchón configurable opt-in: Ajustes → Reproductor → Hosters →
  Buffer before playback, de 0 a 20 segundos. **0 conserva las opciones originales
  de mpv**; no activa buffer inicial ni cambia el umbral predeterminado.
  Un valor positivo configura `cache-pause-wait=N` y `cache-pause-initial=yes` al
  crear el reproductor. Reabrirlo aplica el cambio; no se modifica una sesión activa.
- [x] Coordinar apertura, llenado, seek, pausa solicitada y stall con un único
  watchdog de observación. La pausa temporal de resolución/carga no se confunde
  con una pausa manual; esperar superficie no suma stall.
- [x] Mostrar llenado con segundos aproximados/objetivo si mpv los proporciona,
  o sin cifra si falta esa medición. La app permite que mpv reanude antes del
  objetivo por EOF o límites de caché, sin imponer una segunda espera propia.
- [x] Validar MP4/HLS, llenado lento, caché pequeña, pausa, seek y EOF en emulador
  con fixtures controlados; resultados y límites al final.
- [ ] Medir en fuentes/dispositivo físico antes de recomendar un umbral distinto de 0.

El [manual de mpv](https://mpv.io/manual/stable/#options-cache-pause-wait) documenta
un umbral predeterminado de un segundo y que el buffer inicial también afecta al
seek. La app conserva los límites de 32/64 MiB de caché y la precedencia de opciones
avanzadas del usuario. Un colchón no aumenta el ancho de banda sostenido.

## 2. Limitar cambios automáticos — implementado y validado en emulador

- [x] Presupuesto **total compartido por episodio durante la sesión del player**:
  máximo configurable de 0 a 10 alternativas automáticas, inicialmente 3.
  0 impide cambios automáticos. Tres es un límite operativo conservador y
  configurable, no un valor óptimo acreditado por mediciones de proveedores.
- [x] Contar por motivo (resolución, apertura, EOF sin progreso suficiente y stall)
  bajo ese mismo total; no se multiplica el máximo al alternar motivos.
- [x] Contar también el fallback recursivo tras fallo al resolver un vídeo.
  No gastar por señales duplicadas, generaciones antiguas o falta de candidato.
- [x] Mantener selección manual: no consume ni reinicia presupuesto. Reabrir el
  mismo episodio mediante Intent explícito permite su selección inicial gratuita
  sin borrar contadores; cambiar de anime/episodio sí inicia otro presupuesto.
  Cerrar la sesión/crear otro ViewModel inicia una sesión nueva; no es una sanción
  persistente ni una preferencia por ID de episodio que deba remapearse en backups.
- [x] Mostrar agotamiento de presupuesto/candidatos con botón Elegir servidor,
  incluso si los controles o el spinner están ocultos. No revivir indefinidamente
  candidatos fallidos; reabrir manualmente el episodio puede reconstruir su lista
  y permitir reintentarlos.
- [x] Añadir observación mínima y limpieza de deuda tras reproducción sana; los
  fallos duros de resolución/EOF no esperan a esa histéresis, pero respetan el total.
- [x] Validar presupuesto compartido, agotamiento, selección manual y reset de
  episodio mediante eventos nativos y acciones reales de la UI.

La selección inicial de cada apertura explícita o episodio no es un cambio
alternativo automático. Las nuevas preferencias usan claves Int inéditas y entran
en el backup normal de ajustes; los contadores son estado de sesión y no se guardan.
No cambian los seis tipos de PreferenceValue ni source-api.

## 3. Bajar calidad o cambiar host según el fallo — sigue pendiente

- [ ] Diseñar selección de recuperación que distinga ancho de banda insuficiente,
  fallo de decoder, HTTP, timeout de apertura y error del servidor.
- [ ] Ante falta sostenida de ancho de banda, evaluar calidad inferior del mismo
  host; ante fallo de host, preferir diversidad de servidores.
- [ ] Conservar posición y preferencias de audio/subtítulos cuando sea compatible.

`selectBestVideo` selecciona el primer preferred válido o el primer vídeo con URL
válida; después de un fallo no optimiza por resolución ni throughput. Las listas
legacy pueden repetir un mismo CDN con distintas calidades y duplicados.
No identificar host únicamente por dominio de un segmento o texto del título sin
revisar la metadata y los casos de `NO_HOSTER_LIST`.

No se comprobó una política actual que mida y compare automáticamente ancho de
banda y bitrate. Hacen falta instrumentación, normalización de unidades, ventanas
estables y margen; no basta con reutilizar el contador de stalls. Recordar calidad
por anime, como Tadami, mejora comodidad pero no implementa esta recuperación.

## 4. Sondeos de candidatos — investigación, prioridad posterior

- [ ] Evaluar si un sondeo aporta valor frente al coste de abrir el stream real.

Un `Range` corto puede funcionar para algunos MP4, pero no representa el throughput
sostenido ni una playlist HLS completa. Hay servidores sin Range, URLs firmadas,
cookies/cabeceras, rate limits y costes de datos. Limitar tiempo, bytes y concurrencia;
no paralelizar sondeos de todos los servidores ni evadir pacing/cooldowns del fork.

## 5. Resolver vídeos bajo demanda — requiere cooperación de extensiones

- [ ] Repetir mediciones de tiempo hasta primer frame separando resolución de
  servidores, apertura, buffering y decodificación.
- [ ] Evaluar adopción de `getHosterList()`/hosters lazy en la extensión lenta.

La ruta legacy puede resolver todos los vídeos antes de devolver la lista. La app
no puede hacer incremental una respuesta que la extensión no expone así. Revisar
cancelación, caché y UI de espera del lado app; no prometer un ahorro porcentual
basado únicamente en aquella medición ni resolver todos los hosters en paralelo.

## 6. HLS maestro y ABR — corregida la hipótesis original

- [ ] Investigar soporte de selección/adaptación en la versión mpv/FFmpeg realmente
  incluida, con una playlist de prueba y cambios controlados de ancho de banda.

Pasar una playlist maestra no garantiza ABR automático. El
[manual de mpv](https://mpv.io/manual/stable/#options-hls-bitrate) documenta
`hls-bitrate` como selección de streams por bitrate, con máximo como valor por
defecto; no constituye una promesa de adaptación continua a la red.
La propuesta antigua de "ABR gratis con FFmpeg" queda descartada como conclusión.
Si una extensión devuelve una variante fija se necesita su cooperación para acceder
al master, pero eso solo es un requisito, no una solución completa. Evitar cambiar
la API o reemplazar el motor sin una prueba de concepto.

## 7. Pendientes pequeños confirmados

- [x] Añadir diagnóstico seguro a `HosterLoader.getResolvedVideo`: registra ID de
  fuente y tipos de excepción/causas al devolver null, sin mensajes, stacktrace,
  URLs, cookies ni credenciales. Conserva cancelación, propagación optativa de
  Cloudflare/errores transitorios y fallback. `HosterLoaderDiagnosticsTest` cubre
  privacidad, cadenas cíclicas, cancelación y resolución; se mantienen las pruebas
  existentes de propagación y fallback.
- [x] Reenviar `enabled` de `SliderPreference` a `BaseSliderItem` en `PreferenceItem`
  y desde allí al slider Material; `BaseSliderItem` añade el parámetro con default
  true para conservar sus otros consumidores. `StatusWrapper` sigue ocultando los
  ajustes deshabilitados: también se evita interacción durante la animación de
  salida. Aplica al timeout del reproductor y a duración/intervalo E-Ink de manga;
  no se cambian sus preferencias ni la lógica de reproducción/lectura.
- [x] Componer CPU debanding y yuv420p en una única cadena `vf` con
  `VideoFilterChain.kt` y `AniyomiMPVView.initOptions`; `VideoFilterChainTest` cubre
  combinaciones y la protección hardware. Solo CPU debanding explícito + hardware
  solicita `auto-copy`; se conservan los defaults de preferencias. La validación
  conjunta confirma ambos filtros en software durante Tensei y mediacodec-copy sin
  format en el emulador. El consumo y la matriz física siguen pendientes.
- [x] Primera tanda Tadami: streams cerrados en `PlayerActivity`, decoder desconocido
  tolerado en `PlayerEnums`, progreso externo validado en `ExternalPlaybackProgress`,
  cutout aplicado, preferencia de barra de estado actual y aspecto guardado solo al
  cambiar. EOF normal sin autoplay usa `pause()` después de la comprobación local
  de contenido; failover, watchdog, overlay y preservación de posición se conservan.
  Tests: `DecoderTest`, `ExternalPlaybackProgressTest`, `VideoFilterChainTest` y
  `utils/PlaybackHealthTest`. Detalles y validación en el
  [TODO de Tadami](tadami-player-improvements-todo.md#1-correcciones-prioritarias--implementadas-validación-nativa-pendiente).
  No son cambios del Reader de manga ni un nuevo algoritmo de buffering.

## Seguimiento de pantalla y PiP

- [x] Corregir horizontal inverso: `PlayerOrientation.toActivityOrientation` deja de
  solicitar vertical inverso; regresión reproducida en emulador y cubierta por
  `PlayerOrientationTest`.
- [x] Actualizar parámetros PiP al cambiar aspecto y respetar enablePip en entrada
  automática; omitir dimensiones inválidas sin `!!`. `PlayerDisplayTest` cubre la
  política y proporciones. El botón PiP manual anterior sí funcionaba; la falta
  de evidencia previa fue una limitación del ensayo.
- [x] Basar Stretch en tamaño real del reproductor y reaplicar el aspecto guardado
  al cargar, cambiar tamaño y volver de PiP, sin reescribir la preferencia idéntica.
- [x] Reservar cutout para el contenido en Android 15+ cuando está deshabilitado
  mostrar vídeo en el recorte; proteger controles y evitar doble inset con Compose.
  Reasignar atributos no basta porque NEVER se interpreta como ALWAYS al apuntar
  a SDK 35+. El seguimiento del TODO Tadami enlaza documentación Android oficial.
- [x] Posponer cargas y aspecto hasta SurfaceReady en `AniyomiMPVView`, con
  `SurfacePlaybackQueue`: corrige carrera de apertura rápida que abortó con WinID
  inválido en mpv. Conserva última carga/argumentos, descarta respuestas antiguas y
  cancela pendientes en cambio de episodio/Intent/destrucción. El watchdog sigue
  cubriendo resolución/apertura y no considera espera de superficie como stall.
  `SurfacePlaybackQueueTest` cubre espera, reemplazo, reentrada y ciclo de actividad.
- [x] Corregir publicación de estado y progreso de MediaSession: se reproducía
  NONE/posición 0 con mpv avanzando. Ahora publica estado, posición en milisegundos,
  velocidad y timestamp monotónico, además de título/álbum/duración. Distingue
  carga, buffering, seek, pausa y EOF; limpia la línea temporal al cambiar archivo
  y conserva posición durante failover. Stop pausa y cierra la Activity en lugar
  de invocar manualmente su ciclo de vida. Validación y límites en el
  [seguimiento MediaSession](tadami-player-improvements-todo.md#mediasession-y-validación-pendiente--2026-10-20).
- [x] Completar los casos funcionales pendientes con fixtures controlados: EOF
  nativo sin progreso suficiente y fallback que reproduce, retornos externos con
  cancelación/progreso positivo/finalización y MediaSession dentro de PiP. El
  seguimiento detectó y corrigió que keep-open dejaba pausado el fallback; se
  conserva una pausa manual al cambiar servidor. APK final: EOF normal, autoplay,
  PiP, seek HLS y stall/failover HLS también comprobados. No acredita un error real
  de decoder, reproductores externos de terceros ni hardware físico. Resultados en
  [la continuación de validación](tadami-player-improvements-todo.md#continuación-de-validación-y-corrección-de-pausa-al-eof--2026-10-20).
- [ ] Validar aparte cutout de `ReaderConfig.setCutoutShort`: tiene lógica propia
  que puede compartir la limitación de plataforma; no se portó a ciegas desde el
  reproductor ni se modificó Reader en este seguimiento.

Tests, matriz concreta y restauración del emulador: sección
[Seguimiento de PiP, cutout y rotación](tadami-player-improvements-todo.md#seguimiento-de-pip-cutout-y-rotación--2026-10-19).
PiP manual/automático y sus acciones, retorno fullscreen, cutout ON/OFF, Fit/Crop/
Stretch y giro dinámico fueron observados con fixture local. APK final: tres
arranques fríos con reproducción y una prueba breve de PiP/rotación; otros dos
intentos quedaron bloqueados por SIGSEGV del emulador host. Se restauraron 21/21
archivos por SHA256 y globals/overlays; ADB online sin ventana. No se afirman cinco
arranques completos ni validación física. Las marcas de implementación no sustituyen
la matriz de ensayos de esa sección.

## Orden propuesto y cierre

1. Continuar la validación nativa/visual de ambas tandas (primera Tadami y segunda
   de diagnóstico/slider). El seguimiento de PiP, cutout y rotación del TODO Tadami
   recoge las correcciones adicionales y sus resultados; conservar allí los casos
   observados y los pendientes, sin dar por validada toda la matriz.
2. Búfer y límites de cambio implementados juntos; conservar validación y límites
   del seguimiento final, y medir hardware/fuentes antes de cambiar sus defaults.
3. Selección de recuperación por calidad/host y preferencia recordada opcional.
4. Sondeos solo si las medidas justifican su coste; HLS/ABR como prueba separada.

Para cada implementación: tests enfocados cuando haya lógica, checks de formato y
compilación, pruebas reales de buffering, pausa/seek, EOF, cambio de episodio,
reproductor externo, subtítulos y PiP. No marcar hipótesis como completadas por
escribir preferencias mpv. La implementación de estas tandas requiere los checks
registrados en el TODO de Tadami. Dejar cambios sin stage ni commit salvo petición explícita.


## Diseño de buffering y recuperación — 2026-10-20

`PlaybackRecoveryPolicy` reserva de forma atómica una alternativa por intento;
contabiliza total/motivos y rechaza señales obsoletas o repetidas. La selección se
confirma antes de resolver el siguiente candidato, por lo que una elección manual
posterior puede sustituirla, pero no deshace un cambio automático ya confirmado.
La publicación de hosters y la selección se serializan en Main; las extensiones
se resuelven en IO. Tokens de solicitud, intento y elección manual protegen las
respuestas tardías. El timeout de resolución de vídeo usa el ajuste existente de
espera cuando el watchdog está habilitado; la obtención inicial de hosters por la
extensión sigue siendo una etapa distinta, sin prometer acelerar la ruta legacy.

`PlaybackWaitState` clasifica muestras del estado real de mpv cada segundo:

- Pausa solicitada: no cobra tiempo, reinicia continuo y conserva deuda previa.
- Apertura: cubre el stream/torrent una vez disponible la superficie; no utiliza
  como progreso las métricas del archivo anterior. START_FILE abre su época nueva.
- Llenado: crecimiento de duración aproximada (>=0.05 s) o bytes (>=1 KiB) frente
  a máximos de la misma época mantiene el estado vivo. No suma esa espera al
  acumulado; los huecos entre segmentos HLS pertenecen al mismo llenado.
- Falta de crecimiento: puede agotar el timeout continuo incluso dentro del
  llenado. Además, su gracia absoluta es max(timeout, tres veces el objetivo
  efectivo), acotada a 120 s: un goteo no concede espera indefinida. Tras esa
  gracia se cobra tiempo aunque lleguen pequeños incrementos.
- Seek: nueva época de muestras/deuda local; no suma al acumulado previo, pero
  mantiene un timeout continuo si la operación queda sin datos. No reinicia el
  presupuesto de cambios del episodio.
- Stalls reales: se acumulan por archivo. El acumulado puede disparar recuperación
  tras al menos 5 s de observación; 30 s de avance plausible sostenido limpian la
  deuda. Una posición estacionaria no acredita reproducción sana.

Las propiedades `demuxer-cache-duration` y `fw-bytes` son estimadas/opcionales;
`demuxer-cache-time` es un timestamp y no se presenta como segundos disponibles.
Véanse [propiedades de caché de mpv](https://mpv.io/manual/stable/#command-interface-demuxer-cache-duration).
Sin métricas válidas no se presupone que la caché crezca. Los tiempos son de
observación con resolución de aproximadamente un segundo, no un medidor de
throughput ni una contabilidad exacta de stalls subsegundo.

Una nueva petición explícita de reproducción no hereda la pausa de onPause al
recibir un Intent SINGLE_TOP; descarta resultados anteriores sin borrar el presupuesto
si sigue siendo el mismo episodio. Una pausa manual al elegir otro servidor sí se
conserva. El timeout heredado <=0 sigue deshabilitando el watchdog, además de su
switch de preferencias; el máximo de alternativas continúa aplicando a EOF y fallos
de resolución. Valores soportados por la UI: timeout 5–60 s, colchón 0–20 s y
máximo 0–10 alternativas.

Checks de implementación:

- 25 tests nuevos: 8 de presupuesto atómico y 17 de espera/configuración con reloj
  controlado.
  Cubren motivos compartidos, duplicados/concurrencia, generaciones, reset por
  identidad, llenado progresivo/goteo acotado, pausas/seeks, huecos HLS, acumulado
  y limpieza por avance real; EOF/caché pequeña permiten reanudar antes del objetivo.
- `testReleaseUnitTest`: **424 tests sin fallos, errores ni omitidos**; APK debug
  y formato de los archivos Kotlin propios correctos. La primera ejecución de la
  suite encontró un fallo de callback en `MigratorTest.initialVersion` (listener
  no invocado al verificar después de await); las repeticiones completas pasaron.
  No se modificó ese test ni las migraciones.
- `spotlessCheck` global conserva el fallo previo de formato de
  `eu/kanade/presentation/util/ExceptionFormatter.kt`, sin cambios en esta tarea.
  Formato propio comprobado con target temporal restringido. `git diff --check`
  y referencias locales se verifican al cerrar.
- No cambian dependencias, binarios nativos, `source-api` ni formato de backup;
  no se ejecutó release/R8 ni análisis 16 KB.

### Matriz nativa de buffering y límites

Emulador API 36 x86_64, fixtures localhost con HTTP Range y medios MP4/HLS. Las
primeras pruebas usaron APKs intermedias; la APK final se identifica al cerrar la
restauración. No se usa evidencia de callbacks de fallo/EOF simulados.

| Escenario | Resultado observado |
| --- | --- |
| Preferencia 0 y vuelta desde 10 | mpv wait=1/initial=no; al volver a 0 y reabrir se restablecen esas opciones |
| Llenado MP4 progresivo, objetivo10/timeout5 | Aproximadamente 23–24 s hasta el primer avance observado, con caché creciendo; continuo, acumulado y cambios en 0; después reproduce |
| Presupuesto compartido, límite1 | Apertura sin headers consume Opening=1; EOF nativo del siguiente clip de 0.75 s agota el presupuesto sin seleccionar automáticamente el largo |
| Aviso y selección manual | Con spinner desactivado y controles ocultos, botón Choose server real abre la sheet y tocar el largo reproduce sin gastar ni reiniciar el contador |
| Mismo Intent y otro episodio | SINGLE_TOP mantiene Activity y contador1 para el mismo episodio; episodio nuevo inicia contador0 y puede gastar su propia alternativa |
| Intención al recibir Intent | Se reprodujo pausa heredada de onPause en una APK intermedia; la nueva petición ahora inicia intención de reproducir sin modificar contadores |
| Stream congelado | Corte real de bytes cerca de 4 s activa watchdog y recupera al rápido; Stall=1, reproducción posterior y sin espera infinita |
| Pausa manual y seek | Pausa de 7 s (>timeout5) y seek a 20 s no gastan otro cambio ni suman deuda; Play reanuda |
| HLS con colchón | Target10/initial=yes, pausa/seek20 y reanudación, sin deuda ni gasto de presupuesto |
| EOF antes del objetivo | Clip de 5 s ya reproduce con una muestra de caché de unos 3.69 s (<target10), avanza y termina a 4.96 s sin recuperación falsa |
| Caché de bytes pequeña | demuxer-max-bytes=131072 efectivo; ya reproduce con una muestra de caché de unos 0.35 s (<target10), avanza y termina correctamente sin gastar cambios |
| Timeout heredado 0 | APK intermedia lo convirtió erróneamente en 5; se corrigió con función compartida y test explícito. APK corregida mantiene apertura bloqueada >8 s con intención de reproducir y contador0; elección manual al rápido reproduce sin gasto |

El mismo stream de prueba a 100 KB/s mostró primer avance alrededor de 3 s con
buffer0 y tres entradas en paused-for-cache durante sus primeros 10 s observados
(incluida espera inicial). Con buffer10 el primer avance estuvo alrededor de 24 s y
hubo una entrada inicial sostenida. Las ventanas útiles de reproducción son cortas
y distintas; no se compara una tasa de stalls por minuto ni se concluye mejora de
throughput, consumo o rendimiento general. Solo se acredita el compromiso entre
espera inicial y prelectura de este fixture, no un nuevo default universal.

Se descartaron arranques en los que un archivo de comando Stop pendiente del
harness cerró la Activity: no son fallos de producto ni resultados válidos. La
publicación de comandos de prueba pasó a rename atómico. El host del AVD se detuvo
antes de preparar un contraste positivo final; el usuario volvió a iniciarlo.
No se adjudica esa caída a la app y el respaldo original permanece intacto.

### Cierre nativo con la APK corregida

APK final x86_64 SHA256
`0512a2aa4a97447e3a5be618240e7346c291db1e3289c1ee08543b835eece0c5`.

La prelectura progresiva se acreditó en APKs iniciales; presupuesto/UI/Intents se
acreditaron en `55530003…`, que todavía tenía el defecto de timeout0. El helper
compartido `playbackStallTimeoutMs` y su test explícito corrigen esa compatibilidad
en la APK final. Después del reinicio del usuario se verificaron mismo AVD, UID y
SHA instalado antes de retomar; no se inició ni reconfiguró el emulador desde los
agentes.

- Timeout0: apertura sin headers durante más de 8 s, intención de reproducir,
  contador0; selección manual al rápido reproduce sin gastar. Default efectivo
  wait1/initial=no comprobado de nuevo.
- Contraste positivo timeout5/target2: reproduce hasta unos 3.75 s; la descarga
  congelada provoca Stall=1 y fallback sano que reproduce alrededor de 4.29 s,
  conservando aproximadamente la posición. El guard de 0 no desactiva positivos.
- HLS target10: pausa, seek20 y reanudación alrededor de 20.98 s, sin deuda/cambios.
- Clip de 5 s: posición inicial muestreada de unos 0.875 s con caché3.69 s
  (<target10), EOF4.96 s
  sin recuperación falsa.
- Límite de 131072 bytes efectivo: posición inicial muestreada cerca de 0.83 s
  con caché0.35 s,
  EOF normal y deuda/presupuesto0. mpv reanuda sin que la app imponga el objetivo.

El nuevo acceso a elección manual respeta también el bloqueo de controles; no se
pretende desactivar el bloqueo por haber agotado el presupuesto.

### Restauración y estado final

**21/21 archivos originales con SHA256 idéntico**, sin añadidos, faltantes ni
diferencias en databases/shared_prefs/files. Coinciden permisos del paquete,
overlays, 56 modos appops y globals originales (accelerometer_rotation=1,
user_rotation=0). Los grants del UID10228 coinciden tras normalizar identidades
de objetos del dump. El dump global de URI grants no es byte-idéntico después del
reinicio: cambió el alias targetPkg de grants de UID1000, de android a
com.android.server.telecom; no pertenece a la app y no se modificó desde las pruebas.
No se afirma igualdad byte a byte de todo el estado Android ni de tiempos de uso.

Harness, fixtures de dispositivo, transferencias, reverse y servidor de prueba
retirados; app detenida y APK final instalada con SHA256 comprobado. ADB online
tras el único reinicio, efectuado por el usuario. Sin cambios de drivers ni
configuración persistente del AVD. Los cambios de código/documentación quedan
sin stage ni commit.

Informe sanitizado: `/tmp/aniyomi-player-buffering/report.md`;
`restoration.json` e `installed-apk-sha256.txt` en ese directorio (ruta temporal no
versionada). Respaldos, logcat
bruto y dumps completos con datos de sesión quedan privados. Los resultados de
fixtures locales no garantizan menos stalls ni mayor ancho de banda en proveedores
reales; el colchón permanece opt-in y el máximo de cambios es configurable.
Solo afecta anime/mpv; el Reader de manga no tiene esta caché de streams, watchdog
ni elección de hosters. Permanecen pendientes fuentes/dispositivo físico, consumo,
bitrates variables reales, errores reales de extensión/decoder y la elección de
un umbral óptimo. No se completa calidad/host adaptativa, sondeos ni ABR.


## Corrección visual de los ajustes de buffering — 2026-10-20

Una captura del usuario mostró filas casi de pantalla completa y títulos invisibles
en Hosters. Se reprodujo en la APK anterior: las explicaciones largas de los dos
sliders nuevos se habían pasado como `subtitle`, que ese widget utiliza como pill
de valor. El pill consumía el ancho y comprimía el título ponderado hasta crear
una altura anormal. Las pruebas de reproducción anteriores no acreditaban este layout.

`SliderPreference.description` y `BaseSliderItem.supportingText` separan la
explicación debajo del título; el pill muestra Default/segundos y un número corto
para el presupuesto. Ambos parámetros son opcionales y no cambian los consumidores
existentes sin descripción, incluidos los ajustes E-Ink del Reader. No se modifican
preferencias, valores, watchdog, presupuesto ni source-api.

APK debug x86_64 SHA256
`21e000fee3a846ee896abee35c49c8e87aef394b460c90a1700b2f459487198e`.
`:app:assembleDebug`, formato de los cuatro archivos propios y
`testReleaseUnitTest`: correctos, **424 tests** sin fallos. No se añadieron tests
unitarios que solo duplicaran el paso de un parámetro: el layout se verifica en la
pantalla real. Se inspeccionaron capturas normales y con letra grande.

| Escenario UI | Resultado |
| --- | --- |
| 411 dp/fontScale1.0 | Ambos títulos visibles, valores Default/3 cortos, explicaciones debajo y timeout20 visible; desaparece la fila casi de pantalla completa |
| 360 dp/fontScale1.0 | Filas compactas y contenido accesible mediante scroll |
| 360 dp/fontScale1.5 | Títulos completos envueltos en dos líneas, descripción legible y valores separados; sin altura vacía anormal |
| Interacción con sliders | Buffer0→10→0 y máximo3→0→3 guardados exactamente; pills Default↔10s y 0/3 actualizadas; texto largo de buffer10 también inspeccionado |
| Timeout | Scroll con letra150% muestra el valor20seconds y el ajuste completo |
| Contraparte manga E-Ink | Duración100ms/intervalo1page compactos sin descripción adicional; tap real cambia duración a800ms y actualiza el valor |

El ensayo solo verifica presentación e interacción de ajustes; no repite la matriz
de reproducción, que no cambió con este arreglo. No hubo cierres del AVD.
Restauración final: **21/21 archivos SHA256 idénticos**, sin faltantes, añadidos
ni diferencias. Permisos, URI grants, overlays y 56 modos appops coinciden con
el baseline. wm size/density/fontScale originales restaurados y comprobados:
1080×2400, 420 dpi, escala1.0; globals1/0 originales. App detenida, ADB online y
APK corregida instalada con SHA256 verificado. Sin cierres ni reinicios del AVD.

Capturas y reporte sanitizados en `/tmp/aniyomi-player-settings-ui/` (ruta temporal,
no versionada): `baseline-hosters.png`, `fixed-normal.png`, `fixed-360.png`,
`fixed-360-font150.png`, `buffer10-font150.png` y capturas E-Ink. Respaldos y dumps
completos permanecen privados. Cambios sin stage/commit.


### Traducción al español de los controles nuevos

Se añadieron al recurso `es/strings.xml` las nueve cadenas nuevas de buffering,
límite, elección manual y estado de llenado. Se conservaron los placeholders de
la base inglesa y se comprobaron XML y claves únicas; `:app:assembleDebug` correcto.
No se modificaron traducciones ajenas a estas cadenas ni valores de preferencias.
