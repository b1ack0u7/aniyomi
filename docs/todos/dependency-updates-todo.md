# Actualización de dependencias — trabajo pendiente

Revisión: **2026-10-19**. Sustituye la comparación del 2 de agosto de 2026
(`55be95dd5`). Versiones leídas de los catálogos locales y de `mihon/main` en
`deeb9b2584b6e246135cbd31da49a39e6b6e1e52`; son referencias para investigar,
no versiones cuya compatibilidad con este fork ya esté validada.

## Relación con las mejoras de Mihon

La investigación funcional y sus prioridades están en
[Mejoras de Mihon](mihon-improvements-todo.md). Conservar ambos documentos:
este es el inventario técnico; aquel registra bugs, funciones y adaptación.

Primero abordar las correcciones de estabilidad. Adelantar una dependencia cuando
sea requisito de una corrección; las actualizaciones pequeñas e independientes
pueden seguir después de esa primera tanda, sin esperar a portar todas las funciones.
Las migraciones de build, SQLite y renderer se evalúan por separado.

La revisión del 19 de octubre no modificó dependencias. La ejecución del
**22 de octubre de 2026** está registrada en el punto 1; los puntos 2 y 3
siguen siendo inventario pendiente.

## Diferencias de estructura

El fork conserva `buildSrc`, catálogos divididos y `:macrobenchmark`. Mihon usa
build logic bajo `gradle/`, un catálogo principal, configuración SDK/NDK separada,
un módulo de perfiles y dependencias adicionales de telemetría.
Actualizar una biblioteca no exige copiar esa estructura ni adoptar telemetría.
Preservar el application ID del fork, las dos pilas y las APIs de extensiones.

## Ya alineadas — no repetir

Verificadas en los catálogos actuales:

- Coroutines **1.11.0**, ya subida para compatibilidad con extensiones.
- QuickJS **547f5b1597**, mismo fork de Zhang Hai.
- FlexibleAdapter **5.1.0**, ya resuelta desde Maven Central en `aa8b88fd8`.
- LeakCanary 2.14, RxJava 1.3.8, Swipe 1.3.0, PhotoView 2.3.0,
  desugar 2.1.5, DirectionalViewPager 1.0.0, DiskLruCache 2.0.2.
- Compose WebView 0.33.6, Material Motion 2.0.1, Preference 1.2.1,
  RecyclerView 1.4.0, ViewPager 1.1.0, ProfileInstaller 1.4.1,
  Interpolator 1.0.0, minSdk 26, targetSdk 36 y Java 17.

La lista anterior de agosto ya no era válida para OkHttp, libarchive, Injekt,
Material, Glance, Compose Grid ni subsampling-scale-image-view.

## 1. Evaluación individual — ejecución del 22 de octubre de 2026

**Estado: cerrada con los aplazamientos acordados por el usuario.**

Referencia de esta tanda: `mihon/main` en
`d001e6f210e6a077a30dd9a1392573a3225bba2f`, obtenido tras comprobar que su
rama predeterminada sigue siendo `main`. Se revisaron changelogs, POM,
metadatos Gradle, requisitos de los AAR y las APIs usadas aquí. La comparación
no implica adoptar toda la matriz de Mihon.

- [x] Revisar changelog, requisitos SDK/Kotlin y riesgos binarios de las 22 candidatas.
- [x] Separar decisiones de red, imágenes/nativas, AndroidX, tests y herramientas.
- [x] Completar rotación, reanudación exacta y cancelación manual de ambas pilas.
  Instrumentación y Conscrypt antiguo permanecen aplazados por el usuario.

Alcance de cierre acordado el **22 de octubre de 2026**: el usuario aplazó
expresamente **Conscrypt en API 26–28** hasta crear ese emulador y, después del
diagnóstico, la **instrumentación/baseline por la limitación del AVD actual**.
Ambas quedan como seguimiento independiente, sin marcarlas PASS ni afirmar
compatibilidad comprobada en esos entornos. El usuario asignó **prioridad muy
baja a ambos seguimientos**; no bloquean la etapa 1 ni el avance de las siguientes
etapas. Conscrypt corresponde a Android 8–9; el bloqueo de instrumentación se
observó en el AVD moderno de Android 16 con páginas de 16 KB.
Las ocho versiones retenidas conservan sus motivos y sus migraciones pendientes.

Los cambios de versiones se limitan a `gradle/libs.versions.toml` y
`gradle/androidx.versions.toml`. Se conservan Kotlin 2.2.0, AGP 8.9.1,
Gradle 8.13, SDK 36, minSdk 26 y el BOM estable de Compose 2025.03.00.
Se actualizan o alinean **14 de las 22 candidatas** y, adicionalmente, AndroidX
Test `junit-ktx`; ocho se conservan por los límites descritos abajo. Las columnas
inicial/final son versiones **declaradas**; una transitiva puede haber elevado
ya la versión efectiva antes de esta tanda.

### Red, imágenes y bibliotecas nativas

| Dependencia | Inicial | Final | Decisión |
| --- | --- | --- | --- |
| OkHttp | 5.4.0 | 5.4.0 | 5.5.0 bloqueada: `okhttp-android` exige compileSdk 37; comprobado también por `checkReleaseAarMetadata`. |
| Okio | 3.10.2 | **3.18.2** | La app ya resolvía 3.17.0 por OkHttp 5.4. 3.18.2 corrige la regresión de base64 de 3.18.1 para consumidores compilados contra versiones anteriores a 3.18. |
| Jsoup | 1.19.1 | **1.20.1** | Última versión anterior a las eliminaciones de API de 1.21.1. No subir directamente a 1.23.2. |
| Coil | 3.1.0 | 3.1.0 | Desde 3.2.0 eleva Compose a 1.8.x; 3.6.3 exige SDK 37. Coordinar con el punto 3. |
| Conscrypt | 2.5.3 | **2.7.0** | `newProvider()` se conserva; AAR compatible y ELF de 64 bits alineados a 16 KB. JNI y R8 comprobados; queda HTTPS en API 26–28. |
| Libarchive | 1.1.6 | 1.1.6 | AAR 1.1.7 exige compileSdk 37.2; queda ligado al punto 3. |
| Shizuku | 13.1.0 | 13.1.0 | Todos los parches 13.1.1–13.1.5 eliminan `Shizuku.newProcess`, usado por ambos instaladores. Requiere migrarlos primero. |
| Logcat | 0.1 | 0.1 | 0.2–0.4 cambian la API binaria; 0.4 elimina sobrecargas estáticas y añade un método abstracto al logger. Evaluar como migración. |

El mismo consumidor JVM, compilado una sola vez contra OkHttp 5.4.0,
Okio 3.10.2 y Jsoup 1.19.1, pasó **14 verificaciones** sobre JDK 17 con la
combinación final **5.4.0 / 3.18.2 / 1.20.1**. Se cubrieron base64, Buffer,
DNS e interceptor personalizados, autenticación, formularios, respuesta HTTP
local, selectores CSS, URLs absolutas, scripts y XML. También pasó con el
runtime inicial realmente resuelto (Okio 3.17.0). No se recompiló el consumidor
entre ambas ejecuciones.

El análisis de miembros públicos/protegidos de los JAR no encontró eliminaciones
en Jsoup 1.19.1 → 1.20.1. Como control negativo, el mismo consumidor con
Jsoup 1.23.2 produjo `NoSuchMethodError` en
`Document.updateMetaCharsetElement(boolean)`. `ParsedHttpSource` y
`ParsedAnimeHttpSource` exponen Jsoup a extensiones: este límite afecta a ambas
pilas. Jsoup 1.20.1 además cambia el tratamiento de etiquetas HTML no vacías
autocerradas; las dos fuentes probadas abajo no cubren todos los sitios.

Conscrypt 2.7.0 elimina APIs ChannelID/CT antiguas aunque conserve el proveedor
usado aquí; no se garantiza su ABI para consumidores arbitrarios. La app solo
instala ese proveedor en Android 8–9 (API 26–28): un emulador moderno no cubre
por sí solo esa ruta. Las pruebas JVM tampoco garantizan compatibilidad con
todos los APK de extensiones existentes.

### AndroidX

| Dependencia | Inicial | Final | Decisión |
| --- | --- | --- | --- |
| AppCompat | 1.7.0 | **1.8.0** | Requiere SDK 34 / AGP 8.1.1; revisar temas, idiomas y rotación. |
| Annotation | 1.9.1 | **1.11.0** | Compatible con Kotlin actual; comprobar compilación de ambas APIs de fuentes. |
| Core KTX | 1.15.0 | **1.18.0** | Exige SDK 36 / AGP 8.9.1, disponibles. 1.19.1 exige SDK 37 / AGP 9.1.0. |
| Splashscreen | 1.0.1 | **1.2.0** | Exige SDK 35 / AGP 8.6.0; comprobar arranque frío. |
| ConstraintLayout (Views) | 2.2.1 | **2.2.2** | Afecta layouts del reproductor y descargas; no cambia ConstraintLayout Compose. |
| WorkManager | 2.10.0 | **2.12.0** | Exige SDK 35 / AGP 8.6.0 y minSdk 24. Revisar trabajos de ambas bibliotecas y migración interna de Room a 2.7.0. |
| Paging | 3.3.6 | 3.3.6 | 3.4.x/3.5.3 elevan Compose UI a 1.10 y runtime a 1.9; mantener runtime/compose alineados hasta el punto 3. |
| Lifecycle | 2.8.7 | 2.8.7 | 2.9.2 compila, pero reproduce un fallo Voyager al volver de búsqueda y cambiar de pila; se retira. Versiones posteriores también elevan Compose/SDK. |
| Macrobenchmark | 1.3.3 | **1.5.0** | SDK 34 / AGP 8.1.1, minSdk 24. Compilar módulo y comprobar instrumentación. |
| Espresso | 3.6.1 | **3.7.0** | Alinear también `androidx.test.ext:junit-ktx` 1.2.1 → **1.3.0**. |
| UIAutomator | 2.3.0 | **2.4.0** | SDK 34 / AGP 8.1.1, minSdk 23; conservar selectores del baseline. |

La inspección del grafo confirmó Compose **1.7.8**, Material3 **1.3.1** y
Lifecycle **2.9.2** durante la tentativa inicial, incluyendo savedstate y
módulos Compose transitivos. Esa versión falló en el emulador: al volver de
búsqueda de anime y abrir fuentes de manga, Voyager 1.0.1 intenta pasar un
`AndroidScreenLifecycleOwner` de `DESTROYED` a `STARTED` y Lifecycle lanza
`IllegalStateException`. La prueba A/B con **2.8.7** repitió esa misma secuencia
sin pantalla de error y con el proceso estable; por eso se conserva esa versión.
El cambio que hizo `DESTROYED` terminal está documentado desde
[Lifecycle 2.9.0-alpha06](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.9.0-alpha06).
AppCompat/Core/WorkManager no fuerzan una versión posterior.

La actualización no añade `work-analytics` ni sustituye el driver de las bases de anime/manga.
Room corresponde a la base interna de WorkManager, pero también eleva las APIs
compartidas `sqlite`, `sqlite-framework` y `sqlite-ktx` a **2.5.0 efectiva**
(frente a 2.4.0 declarada). Esta transitiva requiere comprobar lectura/escritura
en ambas bases: debug en API 30+ usa Framework, mientras release/Preview usa
Requery. No basta probar únicamente el APK debug. El análisis JVM de SupportSQLite
2.4.0 → 2.5.0 conserva las firmas usadas por SQLDelight 2.0.2 y Requery 3.49.0;
los dos métodos añadidos a `SupportSQLiteDatabase` son `default`, sin nuevos
abstractos obligatorios. Esto no sustituye las pruebas de ambas implementaciones.

El grafo también muestra stdlib **2.3.0** pedida por la dependencia existente
`io.github.secozzi:torrserver:0.1.0`, y Coroutines 1.11.0 ya pedía stdlib 2.2.20.
Por eso no debe confundirse Kotlin del plugin (2.2.0) con la stdlib resuelta.
Los avisos de D8 sobre metadatos Kotlin requieren revisar la matriz del punto 3;
no se resuelven cambiando solo la versión declarada de AndroidX.

### Tests y herramientas

| Dependencia | Inicial | Final | Decisión |
| --- | --- | --- | --- |
| MockK | 1.13.17 | **1.14.11** | Mantener JUnit Jupiter 5.11.4 y Kotest 5.9.1; ejecutar toda la suite existente. |
| Spotless | 7.0.2 | **8.10.4** | Requiere JDK 17 / Gradle 7.3+, ya disponibles. Conservar la configuración de reglas. |
| ktlint | 1.5.0 | 1.5.0 | Se probó 1.8.0: `spotlessCheck` exige reformatear código ajeno, empezando por ambas fuentes locales. Se conserva 1.5.0; preparar una tanda exclusiva de formato. |

No se ejecutó `spotlessApply` ni se cambiaron reglas para ocultar las diferencias
de ktlint 1.8.0. Los cambios anteriores del usuario siguen separados en staging;
esta tanda se deja sin stage ni commit.

### Validación de esta tanda

Estado inicial: `spotlessCheck testReleaseUnitTest :app:assembleDebug` pasó,
con tareas reutilizadas de la caché. La prueba de ktlint 1.8.0 falló por formato,
y la tentativa de OkHttp 5.5.0 falló por SDK; ambos cambios se retiraron.

Combinación final, conservando Lifecycle 2.8.7 y Savedstate 1.2.1:

- `./gradlew --max-workers=4 spotlessCheck testReleaseUnitTest :app:assembleDebug :macrobenchmark:assembleBenchmark`: **PASS**.
- **531 tests** sin fallos ni omisiones: app 379, domain 92, core/common 39 y data 21.
- `./gradlew --max-workers=2 :app:assembleRelease :app:assemblePreview`, con R8
  y resource shrinking, después de retirar Lifecycle 2.9.2: **PASS**.
- Regresiones del verificador de alineación: **15 tests PASS**.
- Verificador sobre APK debug, release y Preview de ARM64/x86_64: **FAIL conocido**, mismos
  cuatro ELF con alineación de 4 KB: `libffmpegkit.so`,
  `libffmpegkit_abidetect.so`, `libimagedecoder.so` y `libtorrserver.so`.
  Conscrypt 2.7.0 pasa tanto ELF como ZIP en ambas ABI. No se considera resuelta
  la compatibilidad global de 16 KB; ver [el informe](../16kb-page-size.md).

R8 finalizó con avisos de metadatos Kotlin y clases opcionales Window/Sidecar;
no se añadieron reglas para silenciarlos. La compatibilidad de la matriz
Kotlin/R8 sigue pendiente del punto 3.

Emulador: API 36, x86_64, páginas reales de **16 KB**. Instalación debug con
`adb install -r`, conservando datos y Lifecycle 2.8.7.
Preview usa la ruta Requery de release: ambas bases aceptaron escrituras de
favoritos sintéticos y los conservaron tras `force-stop` y arranque frío. El
vídeo sintético reprodujo en Preview, y el lector mostró PNG, JPEG y GIF con
frames rojo → azul → rojo. Logcat confirma la carga correcta de
`libsqlite3x.so` en Preview, sin `FATAL`, `SQLiteException` ni `LinkageError`
observados en estas rutas.
Ya confirmados:

- Arranque frío, regreso desde segundo plano y portadas de las dos bibliotecas.
- Cambio claro/oscuro en Preview, con capturas de ambas apariencias.
- Lector con CBZ propio de PNG, JPEG y GIF animado; avance y reapertura.
- Reproductor con MP4 sintético en debug y Preview. La prueba inicial de 30 s
  no fue concluyente para reanudar; el cierre usó un vídeo propio de **600 s**:
  guardó **2:06** y, tras `force-stop` y arranque frío, la sesión pausada confirmó
  **126000 ms exactos** mediante MediaController/dumpsys.
- Rotación del lector retrato → paisaje → retrato con página renderizada.
  El reproductor pasó paisaje → retrato → paisaje conservando **2:06**; se
  restauró rotación libre.
- Catálogo, búsqueda y scroll de Jkanime (anime) y MangaFire (manga), sin fallos
  de parsing ni enlace JVM observados. No se reprodujeron ni descargaron capítulos
  o episodios de esas fuentes. Esto cubre esas extensiones y rutas, no todo APK.
- Actualización por categoría de fixtures locales: `MangaLibraryUpdateJob` y
  `AnimeLibraryUpdateJob` terminaron en `SUCCESS`. La primera prueba duró
  38/151 ms y no permitió cancelar; la continuación usó fuentes Rx temporales
  con retraso controlado de 60 s, categorías aisladas y cancelación real.
  Anime canceló a los **22,6 s** y manga a los **13,2 s**. WorkDB confirmó
  `RUNNING` → `CANCELLED` y la fuente interrumpió su solicitud. Nuevos controles
  con retraso cero confirmaron `SUCCEEDED` en las dos pilas.
- Probe Android aislado de Conscrypt **2.7.0**, usando Java y JNI del mismo APK:
  proveedor disponible, `newProvider`, `SSLContext.init` e inicio de handshake
  `SSLEngine` con protocolos TLS 1.2/1.3. No prueba la ruta de inicio en API 26–28
  ni un handshake HTTPS completo.

Hubo dos desconexiones del emulador, recuperadas con reinicios del usuario.
Se observó presión de memoria y se cerraron los daemons de build inactivos
antes de la última prueba, liberando recursos. No se estableció la causa de
las detenciones ni se atribuyen a una dependencia. La prueba A/B final y los
dos catálogos reales pasaron después del primer reinicio. Hubo además un
`offline` transitorio de ADB durante rotación que se recuperó solo en unos
15 segundos; se restauró rotación libre y no se repitió esa prueba. No se
dio por validada entonces la rotación. La continuación de cierre sí completó
rotación de lector/reproductor y el cambio claro/oscuro en Preview.

Estado al terminar la primera ejecución: emulador conectado, rotación libre y
debug con los **2 anime /
4 manga originales**. Se retiraron favoritos, categorías, historial, archivos
y probes propios de debug; se conservaron los datos previos. Preview
(`xyz.b1ack0u7.aniyomi.mi.debug`) queda instalada con **1 anime / 1 manga**
sintéticos en `Documents/Codex_Dependency_Preview` para revisión. Se mantienen
las entradas no favoritas de caché generadas por la exploración; no se borraron
registros directamente de las bases.

Pendientes funcionales que no cubre un build:

- [ ] **Prioridad muy baja, aplazado por el usuario:** HTTPS con Conscrypt en Android API 26–28,
  incluida la ruta de inicio de app. Se retomará cuando cree el emulador; el
  entorno actual solo tiene Android 36.1 de 16 KB.
- [x] Rutas de catálogo/búsqueda y scroll de una fuente APK real de cada pila.
  Otros sitios, filtros y modalidades de paginación siguen requiriendo pruebas propias.
- [x] Rotación de lector/reproductor y reanudación exacta en Preview: **126000 ms**.
- [x] Cancelación manual de anime y manga: copias de WorkDB/WAL de solo lectura
  confirman `RUNNING` (1) → acción Cancel de la notificación → `CANCELLED` (5).
  Los tokens Rx registraron `entered` → `cancelled`, sin `completed`.
  Con retraso cero, nuevas solicitudes de ambas pilas terminaron `SUCCEEDED` (2).
- [ ] **Migración futura:** instalación con Shizuku activo después de sustituir
  `newProcess`. Shizuku permanece en 13.1.0 y esta migración no bloquea la tanda.
- [ ] **Prioridad muy baja, aplazado por el usuario:** completar instrumentación de
  Macrobenchmark/UIAutomator y regeneración del baseline en un entorno que
  produzca trazas válidas. ColdStartup se ejecutó y falló por la captura ftrace;
  BaselineProfileGenerator quedó interrumpido. No se reemplazó el perfil fuente.

### Continuación para cerrar la tanda

Se adaptó `BaselineProfileGenerator` al recorrido actual Anime → Manga →
Updates → More → History → volver → Settings, con esperas acotadas y selección
de pestaña comprobada. El APK benchmark con R8 y el test actualizado compilaron;
`macrobenchmark:spotlessCheck` pasó.

La primera ejecución instrumentada de
`ColdStartupBenchmark#startupNoCompilation` con Macrobenchmark 1.5.0 falló en
la iteración 000; no se considera PASS. El proceso auxiliar
`trace_processor_shell` terminó en una aserción Perfetto (`UD2`/`SIGILL`) al
restaurar tablas, después de que la consulta encontrara `no such table: slice`.
El análisis con Perfetto oficial detectó el error anterior
`FTRACE_STATUS_PARTIAL_PAGE_READ`: la captura contiene dos estadísticas de fin
con ese error y ningún paquete de eventos ftrace.

El síntoma coincide con el [fallo de tamaño de sub-buffer del kernel para 16 KB](https://android.googlesource.com/kernel/common/+/66e97080dfaa246d68d21ee8f48c05c693463d6e).
El tamaño exacto de los sub-buffers no pudo verificarse por los permisos de
tracefs; se necesita una captura válida en otro entorno antes de atribuir el
fallo a la biblioteca. No se ignoraron errores de
parsing ni se cambió Macrobenchmark por esta hipótesis. Solo se suprimió la
advertencia `EMULATOR` prevista para ejecutar el smoke test; los tiempos no se
interpretan como medición de rendimiento.

Para generar el perfil fuente se compiló otra variante con
`-Pdisable-code-shrink`: `BaselineProfileRule` no desofusca nombres de una app
minificada. La configuración normal de release conserva R8. La primera captura
de baseline quedó interrumpida por una detención del emulador, avisada al
usuario; no produjo un perfil válido ni se sustituyó el perfil del repositorio.
La cancelación manual de los workers continuó tras esa recuperación. La
instrumentación y regeneración del perfil quedaron después **aplazadas
expresamente por el usuario**, por el bloqueo del entorno; no se repetirán
en este AVD ni se ignorarán errores para obtener un resultado positivo.

**Estado final de la continuación:** el usuario reinició el emulador y se
confirmó conexión/boot completo. Las lecturas devolvieron páginas de 16384 bytes,
`tracing_on=0`, `ro.debuggable=0`, `ro.secure=1` y tipo `user`; el acceso al
sub-buffer e instancias de tracefs fue denegado. No se intentó `adb root`,
escribir tracefs ni cambiar SELinux. Ningún reinicio fue automático.

La limpieza terminó: debug conserva los **2 anime / 4 manga originales** y
Preview los **1 anime / 1 manga anteriores**, con el episodio de 30 s y su
historial preservados. Se retiraron el vídeo nuevo de 600 s y su thumbnail,
favoritos/categorías de cancelación, ambos APK de fuentes temporales, servidor
y redirección ADB. Permisos de notificación, restricciones de actualización y
fuentes locales seleccionadas quedaron como antes; rotación libre y emulador
conectado, sin scripts pendientes.

Quedan dos entradas en `__APP_STATE_trusted_extensions`, limitadas a los paquetes
de prueba `eu.kanade.tachiyomi.extension.en.codexdependencytest` y
`eu.kanade.tachiyomi.animeextension.en.codexdependencytest`, ya desinstalados.
La UI solo permite revocar toda la confianza; se conservaron esas entradas para
preservar las firmas ajenas, sin editar preferencias o bases manualmente.
También permanece la caché no favorita normal de las fuentes visitadas.
Benchmark y su runner quedan instalados con onboarding/almacenamiento propios;
el APK instalado de benchmark es la variante sin shrinking, solo de prueba.

### Resultado de las comprobaciones de cierre

- **PASS:** rotación de lector/reproductor y reanudación pausada en 126000 ms.
- **PASS:** cancelación manual de ambos workers mientras estaban RUNNING, con
  confirmación en WorkDB y en la suscripción Rx, seguida de controles exitosos.
- **PASS:** `spotlessCheck :macrobenchmark:assembleBenchmark` con las versiones
  normales del catálogo. La variante temporal 1.3.3 se compiló para diagnóstico,
  pero no se ejecutó ni se adoptó; el APK final de pruebas conserva 1.5.0.
- **Prioridad muy baja, aplazados por el usuario:** Conscrypt API 26–28 e instrumentación/baseline
  en un AVD compatible. Las ejecuciones fallida/interrumpida permanecen como tal.
- **PASS:** limpieza y comprobación de bibliotecas originales, con los dos
  residuos de confianza acotados descritos arriba.

La **etapa 1 queda cerrada para el alcance acordado**: 14 candidatas actualizadas
o alineadas, AndroidX Test adicional y ocho versiones retenidas con justificación.
Conscrypt API 26–28 e instrumentación/baseline conservan su seguimiento aplazado de prioridad muy baja;
las migraciones de los puntos 2 y 3 y los bloqueos globales de 16 KB siguen abiertos.

### Fuentes y cómo reproducir la revisión

Leer también `META-INF/com/android/build/gradle/aar-metadata.properties` dentro
del AAR **Android**, no solo el POM/JAR JVM. Ese contraste fue el que detectó el
requisito de SDK de OkHttp 5.5.0. Los artefactos provienen de Maven Central y
Google Maven; sus versiones y coordenadas permiten repetir la inspección.

- [OkHttp](https://github.com/lysine-dev/okhttp/blob/parent-5.5.0/CHANGELOG.md),
  [Okio](https://github.com/lysine-dev/okio/blob/parent-3.18.2/CHANGELOG.md) y
  [Jsoup 1.20.1](https://jsoup.org/news/release-1.20.1).
- [Coil](https://coil-kt.github.io/coil/changelog/),
  [Conscrypt](https://github.com/google/conscrypt/releases/tag/2.7.0),
  [libarchive](https://github.com/zhanghai/libarchive-android/releases/tag/v1.1.7),
  [Shizuku](https://github.com/RikkaApps/Shizuku-API) y
  [Logcat](https://github.com/square/logcat).
- AndroidX: [AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat),
  [Annotation](https://developer.android.com/jetpack/androidx/releases/annotation),
  [Core/Splashscreen](https://developer.android.com/jetpack/androidx/releases/core),
  [ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout),
  [WorkManager](https://developer.android.com/jetpack/androidx/releases/work),
  [Paging](https://developer.android.com/jetpack/androidx/releases/paging) y
  [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle).
- Instrumentación: [Benchmark](https://developer.android.com/jetpack/androidx/releases/benchmark),
  [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test) y
  [UIAutomator](https://developer.android.com/jetpack/androidx/releases/test-uiautomator).
- [MockK](https://github.com/mockk/mockk/releases/tag/v1.14.11),
  [Spotless](https://github.com/diffplug/spotless/blob/main/plugin-gradle/CHANGES.md) y
  [ktlint 1.8.0](https://github.com/ktlint/ktlint/releases/tag/1.8.0).

## 2. Cambios de API o arquitectura

| Dependencia | Fork | Mihon main | Revisión necesaria |
| --- | --- | --- | --- |
| Serialization | 1.9.0 | 1.11.0 | Compatibilidad con Kotlin y backups |
| XMLUtil | 0.90.3 | 1.0.2 | ComicInfo y serialización |
| Moko Resources | 0.24.5 | 0.26.4 | MR y AYMR |
| SQLDelight | 2.0.2 | 2.4.1 | Generación, consultas y drivers |
| AboutLibraries | 11.6.3 | 15.2.0 | Plugin Android y APIs |
| Reorderable | 2.4.3 | 3.1.0 | Uso de API 3.x |
| JUnit | 5.11.4 | 6.1.3 | Runtime y plataforma de tests |
| Kotest assertions | 5.9.1 | 6.2.6 | Compatibilidad de tests |
| Injekt | fork mihonapp:91edab2317 | uy.kohesive.injekt:injekt-core:1.16.1 | Comprobar coordenadas y puente con Metro upstream |
| Material | 1.12.0 | 1.14.0 | Temas y UI basada en Views |
| Glance | 1.1.1 | 1.2.0 | Widgets y dependencias Compose |
| Compose Grid | io.woong.compose.grid:1.2.2 | com.cheonjaeung.compose.grid:2.9.0 | Cambio de grupo y API |
| Unifile | tachiyomiorg:e0def6b3dc | com.github.mihon:08f224c8f9 | Fork y comportamiento de almacenamiento |
| Subsampling image view | tachiyomiorg:66e0db195d | mihonapp:1b283e2bc4 | Fork e integración con decoder |
| Image decoder | tachiyomiorg:41c059e540 | ca.mpreg:imagedecoder:16 | Otra implementación; migración del lector |
| SQLite | framework/ktx 2.4.0 declarada, 2.5.0 efectiva por WorkManager; requery 3.49.0 | sqlite-bundled 2.7.1 | La tanda 1 eleva la API SupportSQLite; sustituir el driver y conexiones sigue pendiente |

- [ ] Comparar cada uso antes de modificar coordenadas o versiones.
- [ ] Para SQLite, estudiar `com.eygraber:sqldelight-androidx-driver:0.2.2`,
  configuración de conexiones y `async-extensions`; validar las dos bases,
  esquemas, migraciones y recuperación tras interrupción.
- [ ] Para backups, conservar tipos de preferencias, ProtoNumbers y compatibilidad
  con versiones anteriores/nuevas. No cambiar contratos para imitar upstream.
- [ ] Revalidar coordenadas de forks antes de resolver artefactos.

## 3. Modernización del build — proyecto separado

| Componente | Fork | Mihon main |
| --- | --- | --- |
| Kotlin | 2.2.0 | 2.4.21 |
| AGP | 8.9.1 | 9.4.1 |
| Gradle | 8.13 | 9.8.1 |
| Voyager | 1.0.1 | 2.2.21-1.10.3 |
| compileSdk | 36 | 37.2 |
| NDK | 27.1.12297006 | 29.0.14206865 |
| Compose BOM | 2025.03.00, estable | 2026.10.00, canal beta |

- [ ] Resolver la matriz Kotlin/AGP/Gradle/SDK antes de elegir versiones objetivo.
- [ ] Conservar el canal estable de Compose salvo decisión explícita de cambiarlo;
  el número de un BOM beta no es intercambiable con el del BOM estable.
- [ ] Tratar Voyager como migración propia y comprobar las dos pilas de navegación.
- [ ] Revisar NDK solo junto a los componentes que realmente se recompilan.

**Subir el NDK de la app no realinea `.so` precompilados de dependencias.** Los
bloqueos documentados en [16 KB page size](../16kb-page-size.md) requieren artefactos
compatibles o reconstruir sus proyectos de origen. No marcar esa compatibilidad
como resuelta por cambiar AGP, NDK o empaquetado sin revisar ELF y ZIP del APK real.
El informe de 16 KB describe el decoder antiguo: si se migra al nuevo, comprobar
sus requisitos y binarios de nuevo en lugar de reutilizar aquella conclusión.

## Bibliotecas adicionales y dependencias propias

Mihon incorpora Metro, Apollo, kotlinx-datetime, MaterialKolor, renderer Markdown,
Rich Editor, Material Symbols y WebGPU viewer, entre otras. Adoptarlas cuando una
función lo justifique; no son pendientes por el simple hecho de faltar aquí.
Evaluar por separado la sustitución de `compose-richtext` si se necesita su nueva UI.
No incorporar Firebase/Crashlytics o telemetría como efecto lateral de un sync.

Conservar las dependencias propias y revisar su uso antes de eliminar nada:
`kotlinx-collections-immutable`, Insetter, Compose Stable Marker,
LocalBroadcastManager, Android Shortcut Gradle y la pila anime (`aniyomi-mpv-lib`,
`ffmpeg-kit`, TorrServer, NanoHTTPD, Seeker, TrueTypeParser, Smart Exceptions).
Mihon no valida la reproducción ni las descargas de vídeo.

## Cómo retomar y validar

```sh
git status --short
git remote -v
git ls-remote --symref mihon HEAD
git fetch mihon
git show mihon/main:gradle/libs.versions.toml
git show mihon/main:gradle/mihon.versions.toml
```

Usar la rama predeterminada real si cambia. Registrar el nuevo SHA y actualizar
solo las filas verificadas. Para cada modificación, empezar por tests del área;
antes de cerrar una actualización transversal:

```sh
./gradlew spotlessCheck
./gradlew testReleaseUnitTest
./gradlew :app:assembleDebug
./scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Comprobar también release con R8 cuando se toquen JNI, serialización, reflexión o
reglas de conservación. Probar en dispositivo fuentes de manga y anime, lector,
reproductor y las funciones afectadas; un build verde no prueba compatibilidad con
extensiones APK existentes. Documentar los bloqueos sin marcar la tarea completada.
Dejar cambios sin stage ni commit salvo autorización explícita.
