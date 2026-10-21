# Reproductor de vídeo — trabajo pendiente

Revisado el **2026-10-19** contra el código actual de `dev` (HEAD `ba6adedac`, con
cambios preparados en otras áreas). Sigue vigente como plan de salud de reproducción,
buffering y failover. La comparación de correcciones y funciones con Tadami se
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

## 1. Búfer antes de reanudar — sigue pendiente

- [ ] Experimentar con un colchón configurable y medir el compromiso espera/stalls.

```ini
cache-pause-wait=10
cache-pause-initial=yes
```

Son valores de experimento, no defaults aprobados. `AniyomiMPVView` no configura
estas opciones actualmente. El [manual de mpv](https://mpv.io/manual/stable/#options-cache-pause-wait)
explica que el umbral de reanudación por defecto es un segundo y que el búfer
inicial también afecta el reinicio tras seek. Los límites de bytes, tiempo y fin
de archivo pueden hacer reanudar antes del umbral.

Un colchón no aumenta la velocidad de descarga ni corrige ancho de banda
sostenidamente insuficiente. Puede suavizar fluctuaciones a costa de una espera.

- [ ] Coordinar el búfer inicial y la recuperación con `startStallWatchdog` y
  `onStallRecovered`: el acumulado actual podría tratar la espera deliberada como
  mala salud y cambiar de servidor. Diferenciar abrir conexión, llenar colchón,
  seek, pausa manual y falta real de progreso.
- [ ] Explicar esa espera en el overlay si se incorpora y probar caché pequeña,
  fuentes lentas, MP4/HLS, seeks y final de archivo.

## 2. Limitar cambios automáticos — sigue pendiente

- [ ] Añadir presupuesto explícito por episodio y motivo, con valor a determinar
  mediante pruebas; tres cambios es una hipótesis inicial.
- [ ] Añadir histéresis o tiempo mínimo de observación y mantener selección manual.
- [ ] Informar al agotar presupuesto/candidatos y dejar elegir al usuario.

El código actual marca vídeos fallidos como `ERROR` y deja de seleccionarlos,
por lo que ya existe agotamiento de candidatos: no asumir un bucle infinito.
Reintentar automáticamente el "mejor servidor visto" necesita cooldown, evidencia
mínima y límites; no revivir candidatos fallidos indefinidamente.

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
2. Instrumentación mínima; búfer y límites de cambio diseñados juntos para no
   interferir con el watchdog continuo/acumulado.
3. Selección de recuperación por calidad/host y preferencia recordada opcional.
4. Sondeos solo si las medidas justifican su coste; HLS/ABR como prueba separada.

Para cada implementación: tests enfocados cuando haya lógica, checks de formato y
compilación, pruebas reales de buffering, pausa/seek, EOF, cambio de episodio,
reproductor externo, subtítulos y PiP. No marcar hipótesis como completadas por
escribir preferencias mpv. La implementación de estas tandas requiere los checks
registrados en el TODO de Tadami. Dejar cambios sin stage ni commit salvo petición explícita.
