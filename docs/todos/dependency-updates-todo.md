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

No se actualizaron dependencias ni se ejecutaron builds en esta revisión.

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

## 1. Candidatas para evaluar individualmente

- [ ] Revisar changelog, requisitos SDK/Kotlin y compatibilidad binaria de cada una.
- [ ] Separar red, imágenes, AndroidX, tests y herramientas en cambios revisables.

| Dependencia | Fork | Mihon main |
| --- | --- | --- |
| OkHttp | 5.4.0 | 5.5.0 |
| Okio | 3.10.2 | 3.18.2 |
| Jsoup | 1.19.1 | 1.23.2 |
| Coil | 3.1.0 | 3.6.3 |
| Conscrypt | 2.5.3 | 2.7.0 |
| Libarchive | 1.1.6 | 1.1.7 |
| Shizuku | 13.1.0 | 13.1.5 |
| Logcat | 0.1 | 0.4 |
| AppCompat | 1.7.0 | 1.8.0 |
| Annotation | 1.9.1 | 1.11.0 |
| Core | 1.15.0 | 1.19.1 |
| Splashscreen | 1.0.1 | 1.2.0 |
| ConstraintLayout (Views) | 2.2.1 | 2.2.2 |
| WorkManager | 2.10.0 | 2.12.0 |
| Paging | 3.3.6 | 3.5.3 |
| Lifecycle | 2.8.7 | 2.11.0 |
| Macrobenchmark | 1.3.3 | 1.5.0 |
| Espresso | 3.6.1 | 3.7.0 |
| UIAutomator | 2.3.0 | 2.4.0 |
| MockK | 1.13.17 | 1.14.11 |
| Spotless | 7.0.2 | 8.10.4 |
| ktlint | 1.5.0 | 1.8.0 |

Ninguna fila se considera automáticamente de bajo riesgo por conservar el major.
AndroidX puede requerir SDK/AGP más nuevos; las bibliotecas usadas por extensiones
pueden compilar en la app y aun romper enlaces en tiempo de ejecución.

Coil necesita pruebas de portadas, GIF, caché y lector; Conscrypt y libarchive,
revisión de binarios nativos. Spotless/ktlint van aparte porque pueden generar
reformateos extensos: revisar el diff antes de aceptar cambios automáticos.

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
| SQLite | framework/ktx 2.4.0 + requery 3.49.0 | sqlite-bundled 2.7.1 | Cambio de driver y conexiones |

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
