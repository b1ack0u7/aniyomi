# Contrato de sincronización v1 — borrador para revisión

Fecha: 28 de octubre de 2026. Estado: **diseño, sin motor ni infraestructura implementados**.

Este documento recoge las decisiones confirmadas con el usuario en este chat y propone el diseño
técnico necesario para cumplirlas. «Debe» expresa un requisito del futuro motor, no una garantía
comprobada del código actual. Los formatos y mecanismos identificados como diseño derivado siguen
sujetos a revisión del borrador; no son elecciones técnicas que haya realizado el usuario.

Guía de revisión: sección 1 para decisiones; 5–7 para comportamiento y privacidad; 8–12 para
persistencia, entrega y recuperación; 13 para ejemplos; 15 para las propuestas aún por revisar.

Referencia de partida: [auditoría de backup/restore](audits/backup-restore-sync-audit.md), especialmente
sus secciones 6–11. Allí se conserva la propuesta histórica. La etapa 0 está cerrada en su alcance;
A3–A6 requieren este contrato y una implementación posterior. Las pruebas anteriores no validan este
protocolo. Este borrador tampoco declara cerrada la etapa 1 ni autoriza implementar la etapa 2.

## 1. Decisiones de producto confirmadas

| ID | Decisión |
| --- | --- |
| D01 | Una persona, teléfono y tablet; sin servicio público multiusuario ni bibliotecas familiares compartidas en v1. |
| D02 | AWS serverless y configuración mediante Terraform. Lambda/API Gateway/DynamoDB son la arquitectura de referencia propuesta. |
| D03 | El backend puede leer los datos, con autenticación, acceso restringido, HTTPS y cifrado en almacenamiento. No se exige E2EE. |
| D04 | Biblioteca, leído/visto, posición, marcadores, relleno anime, categorías, temporadas e historial en v1. |
| D05 | Estadísticas de tiempo y sincronización de preferencias quedan fuera de v1. |
| D06 | Intentar enviar al guardar progreso y recibir al abrir/reanudar. Priorizar traer el progreso reciente antes de continuar, con espera breve; permitir uso offline. |
| D07 | Incógnito temporal: no conservar progreso, historial ni tiempo de la sesión; las acciones manuales de contenido también son temporales. |
| D08 | Actividad de incógnito tampoco puede filtrarse en instantáneas, backups o resincronizaciones posteriores. |
| D09 | Preguntar ante avance concurrente con retroceso intencional y ante completado automático con desmarcado manual. |
| D10 | Resolver los conflictos conocidos antes de la siguiente apertura normal del título afectado. No interrumpir ni mover una sesión ya abierta. |
| D11 | Resolver offline con las alternativas conocidas; otra acción incompatible desconocida puede exigir nueva resolución al reconectar. |
| D12 | Permitir abrir en incógnito un título con conflicto; no resuelve el conflicto público. |
| D13 | Dos avances concurrentes en el mismo capítulo/episodio y ciclo conservan el más adelantado. |
| D14 | Avances concurrentes en hijos diferentes conservan ambos progresos y preguntan cuál ofrecer en «Continuar». |
| D15 | Preguntar ante marcas manuales opuestas, quitar/añadir favorito y conflictos de edición/eliminación de categorías. |
| D16 | Reiniciar vence al progreso de la lectura anterior; conserva el historial. Dos reinicios concurrentes de la misma lectura forman un nuevo comienzo común. |
| D17 | Incorporar una biblioteca existente mediante combinación con previsualización; conservar entradas exclusivas y revisar discrepancias. |
| D18 | Mostrar una entrada aunque falte su extensión, conservando su estado. Instalar la extensión es una acción aparte. |
| D19 | Proponer y confirmar asociaciones entre categorías creadas independientemente, aunque tengan el mismo nombre. Manga/anime permanecen separados. |
| D20 | Mantener separadas las ediciones de fuentes diferentes. Vinculación entre fuentes y sincronización de la fuente Local quedan fuera de v1. |
| D21 | Quitar de biblioteca elimina el favorito en ambos dispositivos, conservando progreso e historial. Leer no vuelve a añadir el favorito. |
| D22 | Borrado global de historial con prioridad a privacidad: excluir también actividad pendiente de dispositivos que desconocían el borrado. Conservar progreso. |
| D23 | Tras 90 días desconectado, exigir resincronización y revisión de pendientes; no descartar avances locales silenciosamente. |
| D24 | Cuenta personal recuperable por correo. Cognito es el candidato propuesto por el usuario, no un recurso ya creado. |
| D25 | Restaurar backups como importación con previsualización, sin revertir automáticamente decisiones posteriores ni reponer historial borrado. |
| D26 | Aplicación remota y resolución de conflictos no generan actualizaciones adicionales de trackers ni órdenes de descargar/borrar archivos. |
| D27 | Estado e historial elegibles sin caducidad automática. Los registros técnicos pueden compactarse con garantías de conservación. |
| D28 | Objetivo de recuperación: pérdida máxima de 24 horas del estado del servidor que no pueda recuperarse de los dispositivos; debe validarse. |
| D29 | Backups gestionados del backend pueden conservar datos borrados hasta su expiración, con máximo de 35 días. La restauración no debe republicarlos. |
| D30 | Drive para backups independientes; transporte de sync mediante Drive queda para el futuro y no es prioritario. |

## 2. Alcance y fronteras del dato

Se mantienen `Database`/`MangaDatabaseHandler` y `AnimeDatabase`/`AnimeDatabaseHandler`, sus modelos,
repositorios y transacciones separados. No se transportan IDs SQLite, archivos SQLite ni `.tachibk`
como protocolo bidireccional. Un coordinador compartido no convierte ambas bases en una transacción.

### 2.1 Datos incluidos

- Edición de manga/anime: identidad, favorito y metadatos mínimos para representarla sin extensión.
- Capítulo/episodio: identidad por padre, posición, leído/visto y marcador; relleno solo en anime.
- Categorías: identidad, nombre, orden manual de categorías y pertenencias explícitas, incluido vacío.
- Anime: relación de temporada con su padre, número descriptivo y orden de temporadas.
- Historial elegible: actividad con identidad y procedencia; borrados por hijo, título o stack completo.
- Conflictos pendientes, resoluciones, generaciones de lectura y barreras de privacidad necesarias.

Metadatos mínimos: nombre, identificador/nombre de fuente, URL de edición/hijo, nombre de hijo y
relación padre. Idioma/número/orden de fuente pueden acompañar como descripción, nunca como identidad.
No es necesario replicar el catálogo completo de una fuente ni sus refrescos de caché. Una entrada
ausente de biblioteca pero con progreso o historial sigue siendo representable.

### 2.2 Datos excluidos

Archivos e imágenes, APKs, vídeos o enlaces temporales de reproducción, credenciales de fuentes o
trackers, vínculos/configuración de trackers, rutas, descargas/colas, caché, ajustes del lector/player,
preferencias globales y estadísticas de tiempo. No sincronizar blobs de `flags`, `memo` o preferencias
sin lista de campos: pueden mezclar presentación, procedencia y datos privados.

Frontera propuesta para revisión: `Category.hidden`, modos de ordenación de títulos y otros flags de
presentación permanecen locales por D05; el orden manual de las categorías pertenece a su organización.
Esta delimitación no fue una pregunta específica del chat y se identifica expresamente en la sección 15.

No hay equivalencia automática por título, número, tracker o fuente diferente. Un capítulo retirado
del catálogo no borra la intención personal ni su historial. Un refresco de fuente no sobrescribe
una marca manual de relleno: se distingue valor de fuente de override personal.

### 2.3 Métricas futuras

No sumar `readDuration` de distintos dispositivos ni convertir duración anime en tiempo activo.
No inventar sesiones históricas. Reservar identificadores de actividad, ciclo y procedencia para
una ampliación posterior; no autoriza transportar `activeMs` ni nuevos tipos de métricas en v1.
Los acumulados legacy permanecen locales/con su backup hasta acordar una política de importación
de métricas. Duplicar un backup nunca debe contabilizarse como una sesión nueva.

## 3. Identidades y unidades — diseño derivado

### 3.1 Ámbitos e identidades

`collectionId` identifica la colección personal del backend y está asociada al principal autenticado.
No se deriva del correo mutable. La identidad de autenticación incluye emisor y `sub`; recrear una
cuenta con el mismo correo no otorga acceso automático a la colección anterior.

`deviceId` es aleatorio por instalación/inscripción; los streams manga/anime tienen secuencias
independientes. Reinstalar, clonar un backup o reinscribir un dispositivo retirado crea otra
identidad de inscripción. Ningún backup común restituye tokens, deviceId, cursores u outbox.

Propuesta canónica `E(s1,...,sn)`: para cada cadena, longitud UTF-8 en entero de 32 bits big-endian
seguida de sus bytes. Sin normalización Unicode, espacios ni cambios de mayúsculas de las URLs.
`H` es SHA-256 hexadecimal en minúsculas sobre esa codificación; su colisión se trata como error,
no como permiso para fusionar dos claves distintas.

| Entidad | Identidad propuesta |
| --- | --- |
| Edición | `H(E("aniyomi-sync/v1/edition", collectionId, stack, sourceIdDecimal, sourceUrl))` |
| Hijo | `H(E("aniyomi-sync/v1/item", editionId, itemUrl))` |
| Categoría nueva | UUID aleatorio, siempre dentro de colección y stack |
| Categoría del sistema | Identidad reservada por stack; no exportar el ID SQLite `0` como ID compartido |
| Operación | UUID aleatorio más dot único `(deviceId, stack, sequence)` |
| Actividad | UUID propio, conservado al repetir checkpoints/importar el mismo origen |

Guardar y comprobar la clave completa junto con el hash. `sourceIdDecimal` conserva el Long con
signo; el nombre de extensión no reemplaza su ID. Aceptar las URLs relativas usadas por la fuente
como cadenas exactas. No quitar parámetros ni fragmentos agresivamente: cambiaría la identidad.
URLs con secretos o credenciales requieren tratamiento explícito y no se envían por defecto;
rechazar su publicación con error visible antes que filtrar el secreto o fusionar otra identidad.

Dos altas offline de la misma clave producen la misma edición/hijo. URLs de hijo iguales bajo
padres distintos producen IDs distintos. Cambios de URL requieren un alias demostrado dentro de
la misma fuente; sin evidencia se conservan separados. La migración entre fuentes no crea aliases.

Asociar categorías es una operación explícita y previsualizada: guarda aliases de ambos UUIDs,
elige un representante canónico determinista y reconcilia sus campos/pertenencias sin perder
conflictos. El nombre no prueba equivalencia. No borrar aliases mientras pueda usarlos un cliente
admitido. Asociaciones manga/anime o entre colecciones se rechazan.

Temporadas referencian el ID estable del anime padre. No usar `BackupAnime.id` fuera del archivo.
Un padre ausente queda como referencia pendiente representable; no se elimina el hijo. Rechazar
autorrelaciones y ciclos; si relaciones concurrentes crean un ciclo, conservar el conflicto y
no publicar una topología inválida ni descartar una relación según orden de llegada.

### 3.2 Unidades

| Campo del contrato | Unidad y regla |
| --- | --- |
| `pageIndex` | Entero no negativo con base cero; página visible 1 equivale a `0` |
| `positionMs`, `durationMs` | Enteros no negativos en milisegundos; duración desconocida es `null` |
| `occurredAtUtcMs` | Milisegundos UTC desde Unix epoch; presentación de historial, nunca árbitro de conflictos |
| `sequence`, revisión y cursor lógico | Enteros no negativos, sin significar tiempo civil |
| Número de capítulo/episodio/temporada | Dato descriptivo; no ordenar causalmente ni identificar con él |

En el wire JSON, los enteros de 64 bits se codifican como cadenas decimales canónicas, sin ceros
iniciales salvo `"0"`; versiones pequeñas y booleanos conservan su tipo JSON. Validar rangos antes
de proyectar a Long. `null`, ausencia de campo y valor cero no son intercambiables.

Verificación local: el [lector](../app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt)
persiste `page.index`; el [player](../app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerViewModel.kt)
multiplica segundos por 1000. `lastSecondSeen`/`totalSeconds` son nombres heredados engañosos.
Posición desconocida no se inventa ni se convierte en completado; no comparar posiciones de hijos
distintos, ni limitar una posición por una duración de otra edición.

## 4. Operaciones, causalidad y conflictos — diseño derivado

### 4.1 Sobre común

Toda operación incluye `protocolMajor`, `operationVersion`, `collectionId`, `stack`, `dataEpoch`,
`deviceId`, `sequence`, `operationId`, `kind`, destino, payload y contexto de dependencias.
La secuencia se reserva en la misma transacción local que el dato y la outbox. No hay huecos
por reservar números antes de una escritura fallida. Operaciones ya publicadas son inmutables.

`dataEpoch` separa las reconstrucciones del servicio; no es el ciclo de relectura ni la barrera
de historial. El servidor no acepta escrituras contra un epoch recuperado/retirado.

Se distinguen dos contextos:

- Contexto de transporte: prefijos contiguos por dispositivo/stack realmente recibidos y aplicados;
  permite exigir dependencias y detectar huecos. Puede compactarse con una base de instantánea.
- `basis`: candidatos/revisiones del campo que la acción observó y reemplaza. Se captura en la vista
  o sesión que origina la acción; no se amplía porque llegue una actualización al fondo.

El segundo evita que un checkpoint de un reader abierto anule una decisión remota que su sesión
todavía no incorporó. La secuencia de red, `lastModifiedAt` y `version` locales no sustituyen esa
intención. Una acción posterior que efectivamente parte del estado recibido sí lo reemplaza.

### 4.2 Familias de operaciones

Los nombres siguientes son el vocabulario propuesto; no son clases/tablas ya añadidas.

| Familia | Payload significativo |
| --- | --- |
| `DeclareEdition` / `DeclareItem` | Clave estable y metadatos mínimos permitidos |
| `SetFavorite` | Booleano explícito y basis del favorito |
| `SetCompletion` | Leído/visto explícito, `manual` o `automatic`, cycleRef y basis |
| `SetBookmark` / `SetFiller` | Booleano explícito y basis; filler solo anime |
| `Advance` / `Reposition` | Hijo, posición tipada, cycleRef, rama de intención y basis |
| `SelectContinue` | Hijo elegido y basis de continuación; no marca otros hijos como completos |
| `ResetProgress` | Ámbito título/hijo, ciclo base y nuevo ciclo derivado |
| `CreateCategory` / `EditCategory` / `DeleteCategory` | ID y campos explícitos; revisión del objeto al borrar |
| `AssociateCategories` | IDs, correspondencia confirmada y revisiones previsualizadas |
| `SetMembership` | Edición, categoría, booleano y basis de esa relación |
| `SetSeasonParent` / `SetSeasonOrder` | Padre/orden explícitos, dentro del stack anime |
| `RecordActivity` | ID de actividad, hijo/ciclo, UTC, checkpoint y barreras de historial observadas |
| `ClearHistory` | Ámbito stack/título/hijo y barrera de privacidad |
| `ResolveConflict` | ID, candidatos observados, elección tipada y basis de resolución |
| `ImportDecision` | Origen de importación, campo seleccionado y decisión explícita previsualizada |

Varias ediciones no se modifican mediante un blob que reemplace toda la biblioteca. Una acción
«quitar todas las categorías» emite bajas explícitas sobre sus relaciones observadas; un conjunto
vacío es una decisión válida. Una adición concurrente no observada no se elimina en silencio:
se presenta la discrepancia conforme a D15. Renombrar no recrea una categoría.

### 4.3 Registros de candidatos

Por campo se conservan las alternativas no reemplazadas por una acción que las incluya en su basis.
Alternativas concurrentes del mismo valor se consolidan sin pregunta, conservando procedencia.
Valores incompatibles generan un conflicto durable, no un valor ganador por timestamp o deviceId.
Cambios en campos, hijos o relaciones diferentes se combinan salvo dependencia de un borrado.

`conflictId` se deriva del ámbito/campo y los IDs de candidatos ordenados. Todas las réplicas con
las mismas operaciones obtienen el mismo conjunto de alternativas y bloqueo, aunque no haya
todavía un único valor elegido por el usuario. Esa convergencia de estado conflictivo es un
resultado válido: «converger» no significa resolver arbitrariamente una elección pendiente.

Una resolución conserva la elección del usuario como operación con basis de todos los candidatos
mostrados. Si aparece después otro candidato independiente no observado, se reabre/amplía el
conflicto. Dos resoluciones concurrentes iguales se consolidan; distintas vuelven a preguntar.
No invalidar la elección offline solo por carecer de conexión ni fingir que observó datos futuros.

La lista de alternativas y sus revisiones sobrevive a instantáneas, reintentos y reinicios. El
desempate por IDs solo ordena su presentación; no decide el valor de un campo personal.

## 5. Progreso, reinicios y categorías

### 5.1 Tabla de resolución

| Situación | Resultado requerido |
| --- | --- |
| Acción que observa y sustituye una anterior | Gana la acción posterior sobre ese campo, incluido retroceder o quitar una marca |
| Avances concurrentes, mismo hijo y ciclo, sin retroceso | Máximo de posición; no sumar posiciones |
| Avance frente a retroceso explícito concurrente | Conflicto con elección del usuario |
| Avances en hijos distintos | Conservar ambos; conflicto en «Continuar» |
| Completado automático frente a desmarcado manual concurrente | Preguntar; no privilegiar automáticamente uno |
| Marcas manuales opuestas | Preguntar |
| Quitar/añadir favorito concurrentemente | Preguntar; continuar leyendo por sí solo no emite `SetFavorite(true)` |
| Reset frente a progreso del ciclo anterior | Nuevo ciclo actual; progreso anterior no lo deshace |
| Dos resets del mismo ámbito y ciclo base | Un ciclo nuevo común; incorporar ambos avances compatibles |
| Renombres distintos de la misma categoría | Preguntar |
| Borrar categoría frente a modificación/pertenencia concurrente | Preguntar antes de confirmar la eliminación efectiva |
| Borrado de historial frente a actividad sin conocerlo | Excluir la actividad por privacidad, sin conflicto que permita filtrarla |

Un retroceso es intención explícita, no una posición menor inferida por datos incompletos, reapertura
o truncamiento. El adaptador de lectura/player debe distinguir navegación/seek deliberado de
checkpoints automáticos. Los avances siguientes heredan la rama del retroceso: no pueden perder
su intención y pasar a la regla de máximo mientras sigue siendo concurrente con otra rama.

Completado, posición y continuación son campos relacionados pero distintos. Marcar visto/leído
no inventa una posición/duración; desmarcar no equivale automáticamente a una relectura completa.
Un `Advance` remoto no dispara heurísticas locales de autocompletado. El cliente que observó una
finalización registra la operación correspondiente, incluida su procedencia automática.

### 5.2 Ciclos

Cada título tiene un ciclo raíz y cada hijo una generación dentro de él. `ResetProgress` sobre
un título inicia un nuevo ciclo para sus hijos actuales y futuros: posición inicial y leído/visto
false. Un reset de hijo afecta solo a ese hijo dentro del ciclo del título. Favorito, marcadores,
relleno, categorías e historial no se borran por un reset de progreso.

Propuesta determinista: el ciclo sucesor se deriva de colección, stack, ámbito y ciclo base.
Dos resets independientes sobre la misma base generan el mismo sucesor, pero mantienen sus IDs
de operación para causalidad. Un reset que observa ese sucesor crea otra generación distinta.
La llegada tardía del segundo reset sobre la base antigua no vuelve a poner a cero el sucesor
ni borra avances ya escritos en él. No representar el reset como «poner todas las filas a cero»
sin marcador de ciclo: eso no protege frente a avances offline antiguos.

`cycleRef` identifica ciclo de título y generación del hijo. Un reset de hijo bajo un ciclo de
título retirado tampoco reinicia el ciclo actual. Historial del ciclo anterior sigue siendo
elegible salvo borrado de historial; su progreso puede archivarse, pero no actuar como continuación.

### 5.3 Categorías y temporadas

La pertenencia es un registro por par edición/categoría, con alta y baja explícitas. Editar el
nombre y añadir un título son cambios independientes. Confirmar borrar una categoría elimina
sus pertenencias, no títulos, progreso ni historial. Las operaciones concurrentes de pertenencia
se incluyen en el conflicto de borrado; no se resuelven mediante un cascade SQL inadvertido.

Propuesta de orden manual: lista de IDs y revisión de lista. Dos reordenaciones concurrentes
distintas se conservan como conflicto, sin imponer el orden de llegada. Cambios independientes
de nombre/pertenencia se conservan. La categoría del sistema se deriva de ausencia de categorías
personales, no se convierte en una categoría editable/eliminable compartida.

La misma estrategia se aplica al orden manual de temporadas; las relaciones deben producir un
grafo válido. Un conflicto de organización no debe bloquear toda la app: se resuelve al operar
el objeto afectado. Los conflictos de progreso/marcas de un título sí bloquean su próxima apertura
normal conforme a D10; el resto de títulos y el transporte siguen funcionando.

## 6. Historial, borrados y retención

### 6.1 Actividad elegible

`RecordActivity` representa una sesión/actividad identificada y sus checkpoints de última actividad,
sin tiempo activo en v1. Repetir un checkpoint no crea otra sesión. El historial visible se deriva
de actividades elegibles, no de la fecha de recepción; preservar UTC y procedencia al importar.
Cambiar el reloj puede alterar el orden visual de fechas, nunca la causalidad o la barrera de borrado.

Una entrada histórica puede sobrevivir sin fila de catálogo, favorito o extensión; debe retener
la identidad y descripción mínima necesarias. No dejarla a merced de `ON DELETE CASCADE` de caché.

### 6.2 Borrado con prioridad a privacidad

Cada `ClearHistory` crea una barrera durable para su ámbito. La actividad declara las barreras
de stack, título e hijo que conocía. Solo es elegible si incorpora todas las barreras vigentes
que la afectan; una actividad anterior o concurrente que no las conoce queda excluida, aunque
su UTC sea posterior y aunque llegue después del borrado. También se excluyen checkpoints
posteriores de una actividad iniciada antes de conocer la barrera. Una nueva actividad después
de aplicarla usa otra identidad/contexto y sí puede aparecer.

Si la barrera llega mientras un reader/player público sigue abierto, cerrar lógicamente su actividad
histórica anterior y crear otra identidad solo con la siguiente actividad real posterior a recibirla.
No hace falta interrumpir la reproducción ni fingir una lectura al recibir la barrera; tampoco mantener
todos los avances futuros atados a una sesión histórica que ya quedó excluida.

Las barreras concurrentes se combinan por unión causal; observar solo una no basta. No sustituirlas
por un contador que pierda una de dos limpiezas offline. La propia operación de limpieza permanece
en su outbox si se hizo offline; se aplica localmente de inmediato, y se indica pendiente global
hasta confirmación del servidor. No se promete borrar en una tablet que aún no la recibió.

Al recibirla, retirar el contenido afectado de proyecciones, instantáneas publicables y registros
de actividad accesibles por la API. No conservar una copia legible del historial borrado en el
log activo solo porque ya no figure en la pantalla. Mientras se purga materialización/log antiguos,
las lecturas deben filtrar por la barrera y no exponer sus payloads. Conservar únicamente el
recibo mínimo de supresión necesario para impedir reapariciones, sin fechas, URLs ni nombres
de la actividad eliminada. IDs/barreras pueden mantenerse como metadatos técnicos.

Redacción propuesta para el log: sustituir el payload histórico suprimido por un marcador tipado con
el ID/dot, revisión y hash originales, sin el contenido. Mantener la deduplicación y continuidad del
stream; no cambiar su identidad ni desplazar revisiones. La capacidad obligatoria de barreras incluye
interpretar estos marcadores sin recrear actividad. Verificar barreras actuales al servir páginas y
snapshots: un pull de revisiones antiguas no concede acceso al payload eliminado.

Un borrado global en ambas bases es una intención coordinada con dos operaciones durables, una
por stack. La UI informa pendiente manga/anime por separado y no muestra «completo» hasta confirmar
ambas. Si el proceso muere tras escribir solo una, debe reanudar la intención restante antes de
declarar éxito. No se atribuye atomicidad entre las dos bases a un helper compartido.

### 6.3 Compacción

Estado e historial elegibles no caducan automáticamente. Sí pueden compactarse operaciones y
recibos cuando una instantánea conserva valores, candidatos, aliases, ciclos y barreras, y los
dispositivos admitidos han reconocido un corte suficiente. Registrar un `retentionFloor` por stack.
Por debajo del corte no se admiten publicaciones viejas como si fueran nuevas: requieren bootstrap
y previsualización. No usar un TTL de tombstones basado solo en su edad.

Los 90 días regulan admisión incremental, no caducidad de biblioteca ni promesa de borrar todos
los metadatos técnicos a los 90 días. Los 35 días regulan las copias de contenido recuperables
gestionadas, no la duración del estado actual o de las barreras mínimas de supresión.

## 7. Incógnito temporal

Usar una capa temporal separada del estado público, no una bandera que solo omita la outbox.
Incluye progreso, completado, historial, tiempo y acciones manuales de biblioteca/categorías/marcas.
Se descarta al cerrar/salir; si muere el proceso, no se recupera como actividad pública ni como
importación pendiente. No escribir actividad privada en filas públicas para después «restaurarlas».

La sesión no genera tracker, descarga/borrado automático ni eventos públicos por su actividad,
incluso si hay trackers configurados. No se exceptúa la privacidad por esa configuración.
Los controles deben mostrar que las acciones de contenido son temporales. Los ajustes locales
del dispositivo quedan fuera de esta capa de contenido y fuera de la sincronización.

Las escrituras públicas pendientes anteriores pueden seguir sincronizándose y la base pública
puede recibir cambios mientras dura incógnito. Ninguna exportación lee la capa temporal: revisar
outbox, instantánea, bootstrap, backup durante la sesión, backup posterior y registros de diagnóstico.
Salir muestra la base pública más reciente, no la copia anterior a la entrada en incógnito.

Permitir acceso temporal a un título con conflicto público sin resolverlo. La elección privada
de posición no es `ResolveConflict`. El bloqueo normal persiste al salir. La garantía no borra
datos ya públicos ni pretende ocultar al proveedor la existencia/tráfico de una conexión.

Esto exige cambiar en la implementación posterior las rutas actuales de manga y anime evaluadas
en A6; este documento no afirma que el player/reader ya cumplan esta semántica.

## 8. Persistencia local y aplicación sin efectos secundarios

### 8.1 Escritura local

En **cada base** se confirman juntos: mutación pública, operación serializada inmutable, secuencia
y metadatos de intención/ciclo. Un fallo revierte todos. No observar SharedPreferences después para
fabricar una outbox en otra base. Las colas de tracking corregidas en A8 **no son una outbox de sync**.

Se pueden agrupar checkpoints antes de crear una operación durable, conservando la última posición
local de forma transaccional y sin confirmar sync prematuramente. No mutar operaciones ya publicadas,
coalescer a través de un retroceso/reset/borrado ni perder su intención. Muerte antes de persistir
un checkpoint limita la captura a la última escritura confirmada, no a cada frame/página observada.

### 8.2 Recepción

En una transacción de la base receptora: comprobar epoch/dependencias, registrar deduplicación/inbox,
actualizar proyección y conflictos, y avanzar el checkpoint contiguo. Si falla la proyección, ninguno
de esos pasos queda aplicado. Recibir duraderamente una página no significa haberla aplicado.
Un checkpoint no salta sobre una operación inválida o no soportada.

Manga y anime tienen checkpoints independientes. Si manga confirma y anime falla, reanudar anime;
no volver a emitir manga ni presentar éxito global. El coordinador puede informar éxito parcial
sin fingir una transacción común. No se necesita un stream mixto para este MVP.

Las mutaciones tienen origen explícito `local-public`, `remote`, `import` o `incognito`, además
del tipo de intención. La aplicación remota actualiza proyecciones, no invoca ciegamente acciones
de UI como `SetReadStatus`/`SetSeenStatus` con sus efectos de tracking y borrado de descargas.

### 8.3 Sin eco y sin efectos indirectos

- `remote` no crea otra operación local por el mismo cambio.
- `ResolveConflict` sí registra una decisión de sync nueva, pero no actualiza trackers ni archivos.
- Importación registra solo decisiones confirmadas, no una reemisión de toda la actividad como nueva.
- Rutas de automatización/observers también respetan el origen: no basta con omitir la llamada directa.
- Un worker local independiente puede ejecutar sus políticas habituales; recibir estado remoto no
  constituye por sí solo una orden nueva de limpieza, descarga, actualización de fuente o tracker.
- La actividad local normal fuera de incógnito mantiene sus integraciones existentes; sync no les
  concede autoridad adicional ni sincroniza tokens. Los cambios que esas integraciones hagan a
  campos incluidos deben capturar su procedencia, sin producir bucles tracker → sync → tracker.

Una intención que abarque ambos stacks, como limpiar historial global, se coordina mediante un
registro durable de trabajo en la primera base modificada, junto con su operación. Reintenta
la segunda con el mismo ID de intención y registra ambos resultados; no usa una tercera outbox
como sustituto de atomicidad. Las tablas concretas son trabajo de etapa 2.

## 9. Transporte AWS — diseño de referencia

### 9.1 Responsabilidades

| Componente | Responsabilidad propuesta |
| --- | --- |
| Cognito User Pool | Identidad personal y recuperación por correo verificado; no almacenar la biblioteca en Cognito Sync |
| API Gateway HTTP API + Lambda | Autorizar cada petición, validar contrato, publicar/leer operaciones y gestionar dispositivos |
| DynamoDB Standard on-demand | Registro durable, recibos, cabezas de streams, snapshots y metadatos de admisión |
| AWS Backup/PITR | Recuperación del almacenamiento, separada de snapshots del protocolo y de `.tachibk` |
| Terraform | Definición futura de recursos/permisos/retención; no es una copia de los datos |

La app usa autenticación de usuario; no lleva claves AWS ni un client secret confidencial embebido.
Propuesta móvil: authorization code con PKCE y almacenamiento local protegido de tokens. Cognito
documenta ese flujo y recuperación por correo verificado. [Autorización][aws-cognito-auth],
[recuperación][aws-cognito-recovery]. No se crea ningún recurso como parte de este documento.

### 9.2 Publicación y confirmación

Por colección/stack, el servidor mantiene una cabeza de revisión contigua. Una publicación válida
escribe en una transacción condicional el evento, su recibo/digest, la cabeza y el estado de secuencia
del dispositivo; comprueba también inscripción, epoch y permisos vigentes. Ante una carrera, relee
y reintenta. No reserva revisiones separadamente que puedan dejar huecos.

La serialización de publicación es orden de entrega, no prioridad semántica de las acciones offline.
La proyección puede materializarse después, pero el ACK solo se emite tras persistencia del registro
autoritativo y sus metadatos. Un conflicto semántico es una operación recibida que conserva sus
candidatos; no un fallo de transporte ni una pérdida de la acción.

`operationBytes` son los bytes UTF-8 originales persistidos por el cliente; el servidor devuelve su
SHA-256 como `operationHash`. El reintento conserva bytes, ID y dot. Mismo ID/dot con bytes distintos
es error de integridad, no una actualización. Un recibo durable propio evita depender del token
temporal de idempotencia de AWS. [Límites transaccionales][aws-transaction-api],
[idempotencia de DynamoDB][aws-transactions].

El ACK identifica colección, stack, epoch, operación/hash y revisión confirmada. «Guardado local»,
«confirmado por servidor» y «aplicado por la tablet» son estados diferentes. Un HTTP 200 genérico,
un ACK de recepción en memoria o el éxito de WorkManager no prueban los tres.

### 9.3 Lectura, cursores y semántica de errores

El cursor opaco vincula colección, stack, epoch y revisión aplicada. Una página devuelve límite
superior, operaciones ordenadas y siguiente cursor; un pull vacío no salta por encima de eventos
pendientes. Leer una cabeza y su prefijo mediante claves primarias/lecturas consistentes; no usar
un GSI eventualmente consistente como fuente que permita avanzar más allá de un evento omitido.
La garantía de lecturas consistentes está limitada según el tipo de índice. [Documentación][aws-consistency].

Vocabulario de respuestas propuesto, independiente de los paths HTTP finales:

| Resultado | Acción del cliente |
| --- | --- |
| `committed` / `duplicate` con recibo coincidente | Marcar esa operación confirmada de forma durable |
| `pending-dependency` / hueco de secuencia | Publicar/recibir el prefijo faltante; no cambiar la intención |
| `unauthenticated` | Renovar sesión o pedir login; conservar la outbox |
| `device-revoked` | Detener acceso de esa inscripción; no descartar ni reenviar automáticamente sus datos con otra |
| `resync-required` / epoch distinto / cursor bajo floor | Bootstrap y previsualización de pendientes |
| `upgrade-required` / operación desconocida | Pausar el stack/capacidad afectada y mostrar actualización requerida |
| `invalid-operation` / hash distinto | Error visible y operación retenida para corrección; no afirmar éxito ni saltarla |
| Límite de servicio, timeout, fallo temporal | Reintentar con backoff, jitter y límites de concurrencia |

Un lote puede confirmar solo un prefijo; la respuesta enumera recibos, no confirma todo por un
único status. No eliminar el resto de outbox. Una respuesta perdida tras commit se recupera con
el mismo ID; un reintento incluso días después no puede crear otra actividad.

Propuesta de límites v1: operación de hasta 64 KiB, lote de hasta 32 operaciones y 256 KiB, página
de lectura de hasta 256 KiB. Se pagina la biblioteca; no se limita a 32 títulos. Campos y referencias
deben validarse con cuotas explícitas. Un objeto demasiado grande falla de forma visible sin truncar
URLs, valores ni contexto. La revisión de implementación ajustará estos límites con negociación.

Si se compactó un recibo, el floor y la secuencia admitida impiden reaplicar el dot. Responder
`resync-required` para reconciliar con snapshot antes que fabricar un ACK cuyo hash ya no puede
demostrarse. El cliente no borra la operación únicamente porque su número sea antiguo.

### 9.4 Garantías verificadas del proveedor y límites

Consulta documental: 28 de octubre de 2026. Ninguna prueba de integración AWS ejecutada.

| Capacidad oficial | Consecuencia para este diseño |
| --- | --- |
| `TransactWriteItems`: hasta 100 acciones/4 MB, en una cuenta/región; sin repetir ítem | La transacción por publicación debe caber incluyendo recibos y condiciones; no prometer atomicidad interregional. [API][aws-transaction-api] |
| Token de idempotencia transaccional válido 10 minutos | Hace falta deduplicación durable del protocolo. [Guía][aws-transactions] |
| Streams/índices/backups reciben gradualmente cambios de transacciones; un restore puede contener parte de una transacción reciente | No tratar un stream ni una copia restaurada como un commit completo del protocolo. [Guía][aws-transactions] |
| Lectura consistente disponible en tabla/LSI, no GSI/stream | El cursor autoritativo no depende de descubrir eventos mediante un índice retrasado. [Consistencia][aws-consistency] |
| Recuperación PITR crea otra tabla, con ventana configurable hasta 35 días | Reabrir requiere validar y cambiar epoch; no sustituir la tabla en caliente. [PITR][aws-pitr] |
| AWS Backup permite programación y copia interregional/intercuenta | Es una opción para proteger el objetivo de desastre; no demuestra RPO ni tiempo de copia. [Backup][aws-backup] |
| Revocar tokens Cognito no invalida una comprobación JWT puramente local de firma/expiración | El backend debe comprobar la inscripción/revocación de sync. [Revocación][aws-cognito-revoke] |

Una consulta paginada sobre proyecciones mutables no se convierte en snapshot consistente por activar
lecturas consistentes. El diseño de la sección 10 usa un corte de log y objetos inmutables publicados,
no un `Scan` del estado seguido de fijar el cursor a «ahora».

## 10. Bootstrap, incorporación y resincronización

### 10.1 Corte consistente por stack

1. Capturar cabeza `H` del log mediante lectura consistente y fijar una retención temporal del prefijo.
2. Reconstruir desde una instantánea válida previa más operaciones hasta `H`, incluyendo conflictos,
   dependencias, aliases, ciclos y barreras. No leer filas cambiantes como si todas fueran de `H`.
3. Escribir chunks inmutables con hashes y un manifiesto completo con `snapshotId`, epoch, stack,
   corte `H`, contexto causal resumido y versión/capacidades. Publicar el manifiesto solo después
   de verificar que todos los chunks existen y coinciden. Nunca descubrir completitud por listado.
4. Descargar a staging de la base correspondiente y comprobar esquema, conteos, hashes y referencias.
5. Incorporar el prefijo posterior a `H`, incluyendo las barreras de privacidad actuales, y activar
   la nueva generación local mediante transacción/puntero de generación con checkpoint consistente.
6. Conservar los cambios locales pendientes y su base; rebasarlos/reconciliarlos mediante la
   previsualización, no sobrescribirlos con la instantánea ni reemitir todo como actividad nueva.

Una operación propia incluida en `H` y cuyo ACK se perdió se reconoce por identidad/recibo; no
se aplica dos veces al superponer pendientes. Si cambia el epoch, vence la retención del snapshot
o falla un chunk, reiniciar el bootstrap conservando pendientes. Manga y anime pueden completar
en momentos diferentes; se muestran sus estados independientes.

Una limpieza de historial invalida snapshots que expongan su contenido. Antes de entregar/activar
un snapshot se verifica su barrera de privacidad contra la actual: regenerar o aplicar la limpieza
antes de hacerlo visible. Un chunk descargado antes del borrado no puede eludirlo al activarse después.
El borrado local pendiente también se superpone antes de presentar historial de la instantánea.

### 10.2 Primer dispositivo y bibliotecas existentes

El primer dispositivo también pasa por previsualización de lo que publicará. El segundo no reemplaza
su biblioteca: identificar coincidencias exactas de edición/hijo y conservar exclusivos. No hay
causalidad entre los valores legacy solo porque se conecte antes un dispositivo. Mostrar conflictos
de progreso/marcas y proponer asociaciones de categorías para confirmación, sin deduplicar por nombre.

La previsualización fija las revisiones observadas. Si cambian antes del commit, incorporar la nueva
discrepancia; no aplicar una confirmación vieja como consentimiento a perder datos nuevos. Es posible
avanzar por lotes con estado de importación durable y reanudar tras fallo parcial, sin afirmar éxito
global antes de tiempo. La fuente Local y campos excluidos no se publican mediante ese recorrido.

Una entrada sin extensión es visible con identidad/nombre, progreso, historial y categorías;
abrir contenido informa de la fuente faltante. No instalar APKs, contactar trackers ni descargar
contenido automáticamente para materializarla. No confundir «fuente ausente» con un borrado personal.

### 10.3 Dispositivo retirado por inactividad

Medir los 90 días con tiempo del servidor desde el último reconocimiento válido de aplicación
del stack; una petición vacía o un heartbeat que no permite progresar no prolonga indefinidamente
la retención. Separar admisión por stack cuando solo uno ha conseguido ponerse al día.

Antes de publicar tras el plazo, comprobar su estado: la inscripción antigua ya no puede drenar
su outbox contra el stream actual. Conservar una copia local de los pendientes, descargar la base
actual y previsualizar intención recuperable. Historial sin las barreras actuales no se reintroduce
automáticamente. Reinscripción y nuevas decisiones usan nueva identidad/dots, con procedencia de
los pendientes antiguos; no renumerar ciegamente una outbox vieja para saltarse un borrado.

El floor no se adelanta sobre un dispositivo admitido sin snapshot suficiente o sin retirarlo
explícitamente de esa garantía. Durante fallos del servicio no se finge que un dispositivo ha reconocido
operaciones. Ni inactividad ni compacción autorizan descartar su progreso local sin revisión.

## 11. Experiencia, cuenta y capacidades

### 11.1 Apertura y conflictos

Al guardar progreso, programar envío; al abrir/reanudar, intentar catch-up del stack relevante.
Propuesta operativa revisable: espera inicial de hasta cinco segundos, sin fijarla como SLA.
Si no se logra actualizar, indicar «estado local / cambios pendientes» y permitir continuar offline
cuando no existe conflicto conocido. No afirmar que la tablet está al día si el teléfono no subió.

Conflicto conocido de un título: resolver antes de su siguiente apertura normal; la resolución
offline es válida con las alternativas disponibles. No saltar de página/minuto ni cerrar un reader
o player activo al descubrirlo. Conservar su rama y nuevos checkpoints hasta el siguiente acceso.
El transporte de otros títulos/stack continúa; el conflicto no bloquea el cursor por estar pendiente
de una elección si todos sus candidatos se han aplicado correctamente.

WorkManager sirve para reintento persistente y trabajo diferido, no para prometer sincronización
inmediata con la app suspendida; la periodicidad mínima es 15 minutos y el sistema puede diferirla.
[Documentación de Android][android-work]. No fijar una frecuencia de polling agresiva como requisito
de producto; el envío se agrupa y la latencia real se medirá en la etapa de implementación.

### 11.2 Recuperación de cuenta y revocación

La cuenta personal recuperable por correo permite volver a autorizar una instalación tras perder
ambos dispositivos; no se necesita recuperar una clave E2EE porque no se eligió ese modelo.
El correo debe estar verificado y la configuración de MFA/recuperación no debe dejar al usuario
sin un método válido. La elección exacta de MFA y configuración de correo queda en el diseño de
autenticación; no se presuponen SMS ni sus cargos. [Cognito][aws-cognito-recovery].

Cada petición vincula principal, colección e inscripción. En escritura, la condición de inscripción
se verifica con el commit; para lectura se comprueba al admitir la petición. Revocar bloquea nuevas
peticiones de esa inscripción incluso si queda un JWT firmado no expirado. No prometer retirar
datos ya descargados ni cancelar retroactivamente una respuesta autorizada antes de la revocación.
La reinscripción requiere autenticación nueva y el flujo de reconciliación, no revivir el deviceId.

Cerrar sesión/cambiar cuenta detiene workers y separa tokens, outbox, inbox, staging y cursores por
colección. Datos de A no se publican a B sin una importación explícita. Los datos retenidos localmente
de A no se muestran como sincronizados con B. La política de borrado completo de cuenta no se infiere
de «cerrar sesión» y no se añade como función de v1 en este borrador.

### 11.3 Clientes antiguos

Handshake con versión mayor, versiones de operación y capacidades obligatorias por stack: resolución
manual, ciclos, barreras de historial e incógnito temporal. Un cliente sin ellas no puede escribir
en una colección v1 como si cumpliera la privacidad; una app anterior sin motor sigue funcionando
localmente, sin recibir ni publicar sync.

Campos opcionales desconocidos pueden ignorarse solo si su semántica lo permite y no cambian la
proyección requerida. Operaciones/capacidades desconocidas bloquean aplicación/confirmación del
prefijo afectado; no convertirlas en no-op exitoso. Recepción durable y ACK de aplicación son distintos.
Una actualización de servidor no puede introducir operaciones obligatorias sin negociación ni
retirar clientes para compactar sin el procedimiento de resincronización.

## 12. Backups de aplicación y recuperación del servicio

### 12.1 Importar `.tachibk`

El backup es una fuente de importación previsualizada, no un reemplazo global ni un stream de sync.
Calcular `importSourceId` sobre los bytes del archivo y conservar un manifiesto durable de decisiones
por origen/entidad. Repetir los mismos bytes no repite las decisiones ya aceptadas; dos archivos
distintos con el mismo contenido requieren reconciliar por entidades y procedencia, no tratarlos
como dos sesiones nuevas. No se afirma una deduplicación perfecta de saldos legacy sin procedencia.

Las fechas del backup no demuestran causalidad. Discrepancias, desmarcados, bajas y categorías se
previsualizan contra la base actual. Respetar barreras de borrado aunque el backup sea anterior:
recuperar historial borrado exige selección explícita, presentada como recuperación de contenido,
sin cambiar su fecha original ni contarlo como actividad nueva. Registrar esa autorización como
una decisión nueva que observa las barreras actuales. No reactivar tokens/dispositivos del archivo.

Preservar los seis tipos/discriminadores `PreferenceValue`, números protobuf y números retirados,
campos desconocidos compatibles y opciones de backup actuales. La futura inclusión de procedencia
de sync necesita extensión aditiva versionada y pruebas de downgrade, no serializar una outbox
dentro de preferencias ni reutilizar claves con otro tipo. El formato concreto de esa extensión
queda para su implementación; un backup legacy sin ella sigue siendo importable con revisión.

Drive almacena copias de aplicación independientes; su integración/permisos/rotación concretos
requieren otra revisión, no se deducen garantías de la API de archivos. Las copias externas no
gestionadas por el servicio conservan contenido hasta que su propietario las borre o rote.
No se promete que el borrado global de historial elimine esas copias ni las copias de terceros.

### 12.2 Objetivo operativo de recuperación

Confirmado: RPO objetivo de hasta 24 horas para estado del servidor irrecuperable desde dispositivos.
No se acordó un RTO de disponibilidad ni se promete recuperación en cinco minutos. Un ACK de sync
normal indica persistencia en el servicio activo, no inmunidad frente al desastre cubierto por el RPO.

Propuesta derivada para evaluar en infraestructura:

- Región primaria con almacenamiento administrado replicado, PITR hasta 35 días y backups diarios.
- Copia recuperable fuera del fallo que se desea cubrir; para pérdida regional, copia en otra región.
- Retención propuesta de siete copias diarias y cuatro semanales, cada copia de contenido con edad
  máxima de 35 días. Incluir tablas auxiliares, manifiestos y snapshots abandonados/restaurados.
- Verificar que el último punto **recuperable y completo** tiene antigüedad menor o igual a 24 horas;
  empezar un backup cada 24 horas no basta si tarda en copiarse o falla.
- Supervisar fallos y retrasos de backup, restauración y expiración; practicar la recuperación.

PITR y backups programados son capacidades oficiales, no un procedimiento ya configurado.
[Backup/PITR][aws-backup-overview], [copias y programación][aws-backup]. Deben verificarse región,
permisos, cifrado, disponibilidad de Cognito y copias antes de afirmar cumplimiento interregional.
El RPO es un requisito para aceptar la implementación, no una medición realizada en este borrador.

El máximo de 35 días es requisito de producto para contenido borrado recuperable en backups
gestionados. Inventariar copias manuales/automáticas, retenciones, tablas de restore y material de
prueba; solicitar borrado no prueba su finalización física. El soporte y plazo real del proveedor
para esa garantía siguen pendientes de validación operativa. No configurar retención inmutable
mayor ni archivo frío que impida cumplirla. Una desviación no se declara conforme silenciosamente.

Copiar o restaurar un backup no reinicia el plazo del contenido que sigue suprimido. Conservar su
fecha límite original, purgarlo antes de crear nuevas copias y comprobar que ninguna réplica/copia
gestionada lo retiene pasado el límite del borrado. Una recuperación explícita elegida por el usuario
es una decisión distinta; un restore técnico no equivale a esa autorización.

### 12.3 Restauración segura del backend

1. Aislar el servicio restaurado: no aceptar tráfico de sync sobre una tabla recién recuperada.
2. Elegir un punto consistente del protocolo y validar log, cabeza, secuencias, recibos y hashes.
   Una copia DynamoDB puede contener solo parte de una transacción reciente: reconstruir el mayor
   prefijo completo demostrable y poner el resto en cuarentena. Recalcular las proyecciones.
3. Recuperar barreras de privacidad y revocaciones desde un registro de control protegido del mismo
   rollback. Ese registro mínimo no contiene historial legible. Su durabilidad/copia es requisito
   independiente: restaurar todas las tablas al mismo instante antiguo no conserva borrados posteriores.
4. Aplicar todas las supresiones conocidas antes de publicar snapshots o historial. Si no puede
   demostrarse que ese registro está completo, mantener el historial restaurado en cuarentena;
   no republicarlo basándose en un backup más viejo. Requerir revisión de recuperación del propietario.
5. Crear un `dataEpoch` nuevo fuera del estado que se acaba de restaurar; retirar cursores e
   inscripciones de escritura antiguos. Conservar referencias de procedencia para reconciliar.
6. Reabrir con bootstrap por stack y previsualización de los pendientes/dispositivos supervivientes.
   No confiar en un cursor alto de un cliente ni reaplicar ciegamente un ACK previo al desastre.
7. Medir pérdida efectiva y completar recuperación de datos recuperables desde dispositivos; no
   contar importaciones/reintentos como actividad recién realizada.

El registro de supresión/revocación requiere diseño de almacenamiento y confirmación que sobreviva
al escenario de desastre elegido. Si se confirma una limpieza sin proteger ese registro y después
se pierde, el contrato no permite afirmar que el restore respetó el borrado. Esa prueba es un gate
de infraestructura, no una propiedad automática de DynamoDB/Cognito. D28 permite pérdida de estado;
no concede permiso para exponer historial borrado por D22.

### 12.4 Costes

La conversación estimó, con 1 GB, 100.000 peticiones/mes y cuatro copias semanales, aproximadamente
USD 1,41/mes en AWS antes de cuotas gratuitas, sin servicios auxiliares/impuestos. Era una hipótesis,
no una factura ni un tope. Los backups diarios, copia de desastre, registro de supresión, Cognito/
correo, logs y recursos auxiliares exigen recalcularla. USD 5/mes fue una reserva inicial propuesta,
no un límite acordado que autorice rebajar RPO, privacidad o retención.

Las tarifas deben revisarse al diseñar Terraform; no añadir por defecto NAT, balanceadores o
capacidad permanente para una API intermitente sin justificar su coste. Cognito y el proveedor de
correo tienen condiciones y cargos propios. [Precios de Cognito][aws-cognito-pricing].

## 13. Ejemplos de mensajes y vectores de convergencia

### 13.1 Sobre ilustrativo

Ejemplo JSON abreviado con aliases legibles en lugar de los IDs/hash reales. No es un endpoint
implementado ni un esquema final de autenticación. `basis` hace referencia a una posición ya
recibida; las dependencias deben existir o venir en un prefijo anterior del lote.

```json
{
  "protocolMajor": 1,
  "operationVersion": 1,
  "collectionId": "personal",
  "stack": "anime",
  "dataEpoch": "epoch-1",
  "deviceId": "tablet",
  "sequence": "8",
  "operationId": "tablet-anime-8",
  "kind": "Reposition",
  "entityId": "episode-E",
  "context": {"phone": "12", "tablet": "7"},
  "basis": ["phone-anime-12"],
  "payload": {
    "cycleRef": {"editionCycleId": "C0", "itemCycleId": "I0"},
    "intentId": "tablet-anime-8",
    "positionMs": "300000"
  }
}
```

Un avance del teléfono concurrente a `1080000` ms no gana por ser mayor. Forma un conflicto con
esta rama de retroceso. Resolver offline registra todos los candidatos mostrados, sin inventar
contexto de las acciones del teléfono que todavía no hayan llegado.

### 13.2 Matriz de resultados requeridos

Notación: `A || B` significa acciones independientes con la misma base; `A → B`, B observa A en
su basis. `T` es un título, `X/Y` hijos distintos, `C0/C1` ciclos. Los números de páginas de la
tabla son visibles para el usuario; los vectores wire deben convertirlos a base cero.

| Caso | Entrada | Resultado esperado |
| --- | --- | --- |
| V01 | Alta de misma fuente/URL en teléfono y tablet, distintos IDs SQLite | Una edición compartida; ambos mapeos locales válidos |
| V02 | Misma URL de hijo bajo dos padres/fuentes | Dos hijos; ni progreso ni historial cruzados |
| V03 | Página 35 `||` página 50, X/C0, solo avance | Página 50; sin conflicto ni suma |
| V04 | Minuto 18 `||` retroceso a minuto 5, X/C0 | Conflicto; próxima apertura normal bloqueada |
| V05 | Retroceso a 5 → avance a 6 `||` avance a 18 ajeno a esa rama | Sigue el conflicto; el checkpoint no elimina la intención de retroceso |
| V06 | Página 50 → retroceso observado a 12 | Página 12; máximo antiguo no la restituye |
| V07 | Avance en X `||` avance en Y del mismo T | Ambos progresos; elección pendiente de «Continuar» |
| V08 | Completado automático `||` no leído/visto manual | Conflicto, sin ganador por reloj ni por origen |
| V09 | Marcador true `||` false | Conflicto; editar relleno en otro hijo se conserva |
| V10 | Resolver V04 offline por minuto 5 y luego aparece minuto 20 desconocido de otra rama | La elección local se conserva y se presenta el nuevo conflicto |
| V11 | Dos resoluciones concurrentes iguales/distintas | Iguales se consolidan; distintas preguntan otra vez |
| V12 | Reset C0→C1 `||` avance tardío en C0 | C1 actual; no vuelve el progreso antiguo; historial elegible anterior permanece |
| V13 | Dos resets sobre C0; uno progresa antes de recibir el otro | Un C1 común; el segundo reset no borra avances en C1 |
| V14 | Reset observado C0→C1 → otro reset | C2, no consolidar con C1 |
| V15 | Quitar favorito `||` seguir leyendo | No favorito; progreso/historial elegibles conservados |
| V16 | Quitar favorito `||` añadir favorito explícitamente | Conflicto; no resolver por orden de publicación |
| V17 | Quitar última categoría; no hay cambio concurrente | Pertenencia vacía en ambos; categoría del sistema derivada |
| V18 | Categorías «Pendientes» con UUID independientes | No unir hasta confirmación; renombre posterior mantiene identidad |
| V19 | Renombrar categoría `||` añadir miembro | Conservar ambos; borrar categoría `||` añadir miembro sí pregunta |
| V20 | Relaciones de temporadas concurrentes que crearían ciclo | Conflicto/topología no publicada como válida; no borrar hijos |
| V21 | ClearHistory `||` actividad offline que no lo conoce, incluso UTC posterior | Actividad excluida; progreso sigue siendo reconciliable |
| V22 | Dos ClearHistory concurrentes; nueva actividad solo conoce uno | Actividad excluida hasta incorporar ambas barreras e iniciar otra actividad |
| V23 | Recibir ClearHistory → iniciar nueva actividad | Nueva actividad visible; historial borrado no reaparece |
| V24 | Leer, marcar, añadir favorito/categoría en incógnito; backup durante/después; cierre o muerte del proceso | Ningún cambio privado durable/exportado; sin tracker ni archivos afectados |
| V25 | Incógnito con conflicto; llega progreso público remoto mientras se lee | Sesión privada permitida; al salir, base pública actual y conflicto aún pendiente |
| V26 | ACK perdido y reintento del mismo ID/dot/bytes, incluso después de 10 minutos | Un efecto y recibo coincidente; jamás otra actividad |
| V27 | Mismo ID/dot con otro payload | Error de integridad; no sobrescritura ni éxito |
| V28 | Muerte entre proyección, inbox y checkpoint de un stack | Todo o nada; reintento idempotente |
| V29 | Manga confirma; anime falla | Manga conservado, anime pendiente; sin ACK global falso |
| V30 | Snapshot a H; se publica un avance mientras se construye | Snapshot H más replay posterior incluye el avance, sin hueco |
| V31 | ClearHistory tras empezar un snapshot, antes de su activación | Snapshot invalidado/limpiado; no mostrar ni publicar historial eliminado |
| V32 | Biblioteca no vacía al incorporar tablet; falta extensión | Previsualización, exclusivos conservados, entrada visible sin instalar APK |
| V33 | Dispositivo supera 90 días y conserva operaciones anteriores a un borrado | Requiere bootstrap/revisión; no drena outbox antigua ni pierde avances silenciosamente |
| V34 | Repetir el mismo backup; importar otro archivo con base histórica común | No repetir decisiones ya importadas ni inventar sesiones/sumar saldos legacy |
| V35 | Backup anterior a ClearHistory | No restaura el historial salvo recuperación explícita previsualizada |
| V36 | Token JWT válido de dispositivo revocado | Backend rechaza nueva petición; no basta validar firma/expiración |
| V37 | Cliente recibe operación/capacidad obligatoria desconocida | No confirma aplicación ni avanza el prefijo; informa actualización |
| V38 | Cambiar cuenta A→B con outbox A pendiente | Ninguna operación A se publica en B |
| V39 | Aplicar leído/visto o resolver conflicto remoto con trackers/descargas configurados | Cero actualizaciones adicionales de tracker y cero órdenes de archivos |
| V40 | Restore con log/recibos/cabeza de una transacción capturados parcialmente | Servicio aislado; reconstruir prefijo válido, nuevo epoch; nada de reapertura automática |
| V41 | Restore anterior a borrado/revocación; registro de control disponible/no demostrable | Reaplicar barreras/revocaciones; si falta prueba, historial en cuarentena |
| V42 | Fuente retira un hijo con historial o cambia metadatos de relleno | Conservar intención/historial; no borrar ni reemplazar override manual |

### 13.3 Cómo verificar la matriz posteriormente

Para cada caso aplicable a ambos stacks, ejecutar como mínimo A→B y B→A en orden de entrega,
duplicaciones, paginación y respuesta perdida, preservando las dependencias declaradas. Las entradas
fuera de orden se reciben/esperan, no se aplican fingiendo bases inexistentes. Comparar valores,
candidatos, aliases, ciclos, barreras y estado de bloqueo normalizados; excluir IDs SQLite/cursores
de transporte de la igualdad de dominio. Un conflicto pendiente debe coincidir en ambos dispositivos.

Después de resolver un conflicto, volver a comprobar convergencia incluyendo resoluciones concurrentes.
Las pruebas de privacidad comparan también outbox, log legible, chunks, staging, backups y efectos
externos; comprobar solo la UI no demuestra ausencia de filtración.

Estos son vectores declarativos: **no se ejecutó un simulador, motor, backend ni prueba Android de
sync**. El simulador y sus generadores de permutaciones pueden ser una entrega posterior autorizada.

## 14. Validación y condiciones antes de implementar/publicar

Este cambio solo crea documentación y enlaza desde la auditoría. No modifica esquema, workers,
API pública de extensiones, aplicación ID, recursos, signing ni preferencias. No crea cuentas,
Terraform, infraestructura, automatizaciones, commits, PRs o despliegues.

Validación de esta entrega: comprobar referencias locales, JSON ilustrativo, ausencia de errores
de diff y preservación del índice/archivos ajenos. No corresponde ejecutar Gradle o emulador para
validar prosa, ni reutilizar resultados históricos como evidencia de convergencia.

Antes de declarar un motor conforme deben existir pruebas de:

1. Reductores de conflictos/ciclos/barreras y matriz V01–V42, con permutaciones y duplicados.
2. Transacciones reales por stack, rollback/muerte, importación y snapshot bajo escrituras concurrentes.
3. Idempotencia, autorización por dispositivo, cursores/floors y commit con respuesta perdida en AWS.
4. Incógnito y exportación durante/después de sesión, incluyendo player externo y trackers configurados.
5. Reincorporación tras 90 días, cliente antiguo, cambio de cuenta y revocación con JWT no expirado.
6. Restauración real desde copias diarias, prefijos parcialmente recuperados, barreras posteriores,
   pérdida regional si se declara cubierta, medición de RPO y cumplimiento de retención de 35 días.
7. Regresión backup legacy/downgrade y pruebas de UI en teléfono/tablet para la experiencia acordada.

## 15. Puntos de revisión del borrador y garantías aún no demostradas

Las decisiones D01–D30 están confirmadas. No se presentan las siguientes propuestas como aceptación
del usuario ni como garantías existentes:

- Frontera exacta de presentación de categorías: propuesta `hidden`/flags locales, orden manual de
  categorías compartido; revisar si coincide con el significado de «categorías» esperado en v1.
- Formato wire, IDs canónicos, límites, basis y reducción de ciclos: diseño derivado a contrastar
  con los vectores antes de congelar una versión implementable.
- Cognito, MFA/correo, inscripción, protección de tokens y registro de control de supresiones:
  verificar configuración y modelo de amenaza sin inferir revocación desde JWT.
- Regiones concretas, copia/confirmación del registro de control, backups diarios y expiración:
  demostrar RPO de 24 horas, máximo de 35 días y recuperación sin resucitar historial. Si una
  garantía del proveedor no alcanza el requisito, informar la brecha antes de publicar.
- Integración/rotación concreta de backups Drive y extensión aditiva de procedencia de `.tachibk`:
  diseño pendiente, sin convertir Drive ni backup en protocolo bidireccional.
- Tiempo de espera inicial de cinco segundos y cadencia de checkpoints: parámetros técnicos propuestos,
  sujetos a medición. No hay latencia, RTO ni presupuesto máximo garantizados.

El siguiente paso de esta entrega es revisar el borrador. La aprobación de este documento no sustituye
la autorización para implementar motor/infraestructura, ni las validaciones requeridas para publicarlos.

## 16. Fuentes y puntos de integración

Se leyeron `AGENTS.md`, `CONTRIBUTING.md` y la auditoría de partida. Las ubicaciones siguientes son
referencias al checkout, no una lista de archivos modificados:

- [Modelo Chapter](../domain/src/main/java/tachiyomi/domain/items/chapter/model/Chapter.kt) y
  [modelo Episode](../domain/src/main/java/tachiyomi/domain/items/episode/model/Episode.kt).
- [Historial manga](../domain/src/main/java/tachiyomi/domain/history/manga/model/MangaHistory.kt) y
  [historial anime](../domain/src/main/java/tachiyomi/domain/history/anime/model/AnimeHistory.kt).
- [Categorías](../domain/src/main/java/tachiyomi/domain/category/model/Category.kt) y
  [modelo Anime](../domain/src/main/java/tachiyomi/domain/entries/anime/model/Anime.kt).
- [Inyección](../app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt), repositorios de ambos stacks,
  captura de intención en reader/player y acciones manuales; no limitar captura a los reproductores.
- [Auditoría](audits/backup-restore-sync-audit.md): detalles A3–A6, compatibilidad y límites históricos.

Las referencias oficiales sustentan solo las capacidades concretas citadas, no el protocolo propio.
Toda integración y garantía operativa indicada como pendiente requiere comprobación posterior.

[aws-transaction-api]: https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html
[aws-transactions]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html
[aws-consistency]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html
[aws-pitr]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/PointInTimeRecovery_Howitworks.html
[aws-backup-overview]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Backup-and-Restore.html
[aws-backup]: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/backuprestore_HowItWorksAWS.html
[aws-cognito-auth]: https://docs.aws.amazon.com/cognito/latest/developerguide/authorization-endpoint.html
[aws-cognito-recovery]: https://docs.aws.amazon.com/cognito/latest/developerguide/managing-users-passwords.html
[aws-cognito-revoke]: https://docs.aws.amazon.com/cognito/latest/developerguide/token-revocation.html
[aws-cognito-pricing]: https://aws.amazon.com/cognito/pricing/
[android-work]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
