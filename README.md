# SmoothRec — Grabador de pantalla para Android

Grabador de pantalla nativo desarrollado en **Kotlin** con Android SDK. Sin Compose, sin NDK, sin C++ y sin dependencias externas más allá del propio SDK y la biblioteca estándar de Kotlin.

- `applicationId`: `com.smoothrec.app`
- `versionCode 12` · `versionName 0.5.5`
- `minSdk 23` · `targetSdk 35` · `compileSdk 35` · JVM 17
- Interfaz XML oscura y minimalista, con botón central circular, estados de grabación y ajustes.
- Salida en MP4 dentro de `Movies/SmoothRec` mediante MediaStore.

## Repositorios y versiones

| Repositorio | Uso | Última release |
|---|---|---|
| [`buildgradlekts/SmoothRec`](https://github.com/buildgradlekts/SmoothRec) | Repositorio principal y fuente de actualizaciones OTA | **v12 · 0.5.5 · código 12** |
| [`buildgradlekts/SmoothRec-Grabador-De-Pantalla`](https://github.com/buildgradlekts/SmoothRec-Grabador-De-Pantalla) | Repositorio histórico; mantiene la v8 | v8 · 0.5.1 · código 8 |

### Descarga actual

- APK directo (v12): https://github.com/buildgradlekts/SmoothRec/releases/download/v12/smoothrec-v0.5.5-code12-universal.apk
- Página de la release: https://github.com/buildgradlekts/SmoothRec/releases/tag/v12
- SHA-256: `a935f5d4279db81f3e82fd326f6efdd099ccda3f04d3479028cf65ab41c83493`
- Firma: certificado `Android Debug`, con la misma huella SHA-256 `17927bed24613406386d0c19f735e692d241668408c0fd6851b56dc8760553e9` que las versiones anteriores, por lo que Android permite actualizar sin desinstalar.

Para instalar un APK descargado fuera de Play Store, Android puede pedir autorizar **“Instalar aplicaciones desconocidas”**. No desinstales la versión anterior si quieres conservar los vídeos y las preferencias.

> Nota: la v8 instalada busca actualizaciones en el repositorio histórico. Instala una vez la v9 o posterior desde el repositorio principal para que la app pase a consultar `buildgradlekts/SmoothRec`.

> Las optimizaciones de rendimiento listadas en *Rendimiento* están commiteadas localmente después de v12; **no se ha publicado una release nueva** con ellas.

## Actualizaciones dentro de la app (OTA)

El actualizador está en `UpdateManager.kt` y consulta por defecto:

```text
https://api.github.com/repos/buildgradlekts/SmoothRec/releases/latest
```

- **Ajustes → Comprobar actualizaciones** muestra si no hay versiones nuevas, si ya está actualizada o el número de la nueva versión.
- Al abrir la app aparece una **ventana inferior** con las notas del release y las opciones **Actualizar / Más tarde / No volver a preguntar**. Esta ventana sustituye al antiguo aviso por notificación.
- La descarga se inicia al pulsar **Actualizar**: usa `HttpURLConnection` con reanudación por `Range`/`If-Range`, manejo de `416`, reintentos cortos, progreso y bytes descargados/total.
- El permiso de instalación se solicita **después** de descargar y verificar. Al volver de Ajustes se lanza el instalador; «Ahora no» deja el APK cacheado.
- Si queda un APK válido sin instalar, Inicio muestra la fila **«Actualización lista para instalar»**.
- Solo se aceptan URL **HTTPS**, releases publicados (se ignoran borradores, prereleases y assets cuyo estado no sea `uploaded`) y assets `.apk`.
- Se prefiere el `versionCode` explícito de las notas (`versionCode: 12`); si no está, se usa el tag como referencia. Se ignoran las versiones ya omitidas con «No volver a preguntar».
- Antes de abrir el instalador se validan SHA-256 (`digest`, asset `.sha256` o línea `SHA-256:` de las notas), `applicationId`, `versionCode`, `minSdk` y huella del certificado de firma.
- La instalación **nunca** es silenciosa: se confirma con el instalador oficial de Android.
- Si GitHub no responde o devuelve un error HTTP distinto de 404, se muestra un mensaje de error; 404 significa “sin releases publicados”. Tras un error se reintenta a los 15 min; el intervalo normal de comprobación es de 6 h.

## Asistente de permisos (una sola vez)

El asistente visual se muestra únicamente la primera vez, o cuando se abre explícitamente desde la app:

- Explica audio, micrófono, captura de pantalla, notificaciones y optimización de batería con iconos y estado por paso.
- La aceptación se guarda de forma permanente en `SharedPreferences` (`smoothrec_setup`, clave `setup_acknowledged`).
- Ya no depende de `versionCode`, SDK, ajustes ni estado de permisos: una actualización OTA no vuelve a mostrarlo.
- Las instalaciones que aceptaron el aviso con el formato anterior se migran automáticamente.
- Si el usuario revoca un permiso desde Ajustes de Android, no reaparece el recuadro: `MainActivity` solicita **solo el permiso que falta** cuando se pulsa Grabar.
- Hay una única instancia activa del diálogo, sin duplicados entre pantallas ni componentes.

## Motor de grabación

- `ProjectionHost`: **un solo `VirtualDisplay`** por consentimiento de `MediaProjection`. El callback se registra una vez, antes de crear el display; cada grabación solo cambia la Surface (`setSurface`) y su tamaño (`resize`). `onCapturedContentResize()` ajusta la proporción del contenido sin superar el tamaño del encoder.
- `CaptureSession`: orquesta captura → codificación → muxer → MediaStore. Al terminar, llama a `host.detach()` **antes** de liberar renderer y encoder.
- `HardwareVideoEncoder`: `MediaCodec` asíncrono con entrada Surface; callback instalado antes de `configure()` y repuesto tras cada `reset()`. El codec y la Surface se limpian con `try/finally` si el arranque falla.
- `EncoderFactory.createAndStart()`: prueba cada codificador H.264 (hardware primero, software como último recurso) hasta que uno arranca de verdad y libera el anterior si falla.
- `AacAudioEncoder`: el EOS se reintenta hasta encolarse y se confirma; `stop()` devuelve si el hilo terminó. Si el hilo AAC no acaba a tiempo, el MP4 se descarta en vez de publicarse incompleto.
- `Mp4Muxer`: escribe H.264 y AAC en MP4 sobre el descriptor de MediaStore; ruta estable sin copias de `ByteBuffer` y PTS monótonos por pista.
- Finalización: `signalEndOfInputStream()`, drenado, `MediaMuxer.stop()/release()`, cierre del `ParcelFileDescriptor` y publicación con `IS_PENDING=0`.
- `MediaProjection.Callback`: la revocación del consentimiento cierra la sesión activa y libera el host.
- Servicio en primer plano tipo `mediaProjection` con notificación persistente y WakeLock con timeout.

### Configuración de grabación

| Parámetro | Valor |
|---|---|
| Resolución | Pantalla / 1080P / 720P, derivada de la relación de aspecto real |
| FPS | 60 / 30 como objetivo, no garantía |
| Audio | Audio interno / Micrófono / Interno + micrófono |
| Códec | H.264 (`video/avc`) |
| Perfil / nivel | High / AVC Level 5 |
| Bitrate | AUTO (3–24 Mbps según resolución y FPS) / 4 / 8 / 12 / 16 / 24 Mbps |
| AAC | 96 kbps estéreo o 64 kbps mono |
| Contenedor | MP4 |
| Almacenamiento | `Movies/SmoothRec` vía MediaStore |
| Por defecto | Pantalla · 60 FPS · audio interno |

También están disponibles el límite de duración **Ilimitado / 5 / 15 / 30 / 60 min** y la **pausa/reanudación** desde la interfaz, la notificación y la barra flotante.

`CaptureSizeResolver` consulta `VideoCapabilities` para validar tamaño, alineación y tasa. Si la combinación elegida no es compatible, prueba la misma tasa con menor resolución y después 30 FPS, avisa al usuario y refleja los valores aplicados en la notificación. Nunca se estira, recorta ni gira la imagen.

En el Redmi Note 13 4G (1080×2400), Pantalla y 1080P convergen en **864×1920** y 720P produce **720×1600**. En pantallas 1080×2160, 1080P produce **960×1920** y 720P **720×1440**.

No se escribe metadato de rotación: el encoder mantiene fijas las dimensiones de la grabación. En Android 14+, `onCapturedContentResize()` redimensiona el `VirtualDisplay` a la proporción del contenido compartido sin superarlas; el MP4 no cambia de resolución a mitad de grabación.

## Audio

- Audio interno mediante `AudioPlaybackCaptureConfiguration` sobre `MediaProjection` (API 29+).
- El formato PCM se detecta en tiempo de ejecución: se prueba estéreo a 48 kHz y se desciende a mono y/o 44,1 kHz si hace falta. Se comprueban `AudioRecord.getMinBufferSize()`, el estado real de `AudioRecord` y la existencia de un codificador AAC.
- La mezcla de interno + micrófono aplica ganancia y limitación suave para evitar saturación; ambas fuentes comparten reloj PCM y se codifican en una sola pista AAC sincronizada con el vídeo.
- Si una modalidad falla, se informa al usuario y se graba solo vídeo o la fuente disponible.
- Se han probado con sondas de API reales el codificador `c2.android.aac.encoder`, 48 kHz estéreo y el muxer AAC.

## Rendimiento

Cambios aplicados al motor sin modificar la interfaz, el audio, las preferencias ni la calidad seleccionada:

- La ruta GL con marca de agua coalesce eventos de `SurfaceTexture`: si llegan varios frames mientras se renderiza, se conserva el más reciente y se descartan los obsoletos (`staleFramesDropped`). **No se bajan los FPS configurados.**
- Se eliminó el `glClear` de pantalla completa porque el quad OES cubre todo el viewport; `eglSwapInterval(0)` evita esperas a vsync en la superficie del encoder.
- `glViewport` y `glDisable(GL_BLEND)` se fijan en `initEgl()`: **2 llamadas de estado GL menos por frame**, con el mismo resultado visual.
- La comprobación del APK cacheado (`getPackageArchiveInfo()`) se hace en el hilo del actualizador, no en `onResume`, para evitar bloqueos al volver a Inicio.
- Las miniaturas se deduplican durante el scroll: una sola decodificación por grabación, con actualización de todas las filas visibles enlazadas a ese id.
- Los fallos de liberación de codec/Surface que antes se silenciaban ahora quedan registrados en el diagnóstico.
- Telemetría separada y real: `captureFps` mide frames entregados por `SurfaceTexture`; `effectiveFps` mide los PTS de MediaCodec. Se registran frames obsoletos, cola máxima, edad máxima del frame, huecos de PTS, escrituras lentas y estado térmico (`PowerManager`, API 29+).
- Contadores primitivos y snapshots publicados como máximo dos veces por segundo. La ruta estable del muxer no copia `ByteBuffer`.
- `ThermalMonitor` solo informa: no reduce resolución, bitrate ni FPS automáticamente.

Los datos se escriben en `Descargas/SmoothRec-diagnostico.txt` con líneas `Telemetría:` cada 10 s y un resumen `Rendimiento:` / `Temperatura final:` al detener. Las comprobaciones de UI añaden las líneas `UI: fila de actualización comprobada en …ms (hilo=SmoothRecUpdate)` y `UI: miniaturas del lote anterior peticiones=N duplicadasEvitadas=M`. También se emite el broadcast `com.smoothrec.app.action.RECORDING_STATS` con `extra_capture_fps`, `extra_dropped_frames`, `extra_pts_gap_frames`, `extra_max_write_ms` y `extra_thermal_status`. El overlay flotante muestra únicamente FPS reales del encoder.

**Limitación de medición:** con la marca de agua desactivada, `VirtualDisplay` escribe directamente en la Surface de MediaCodec y no hay un consumidor intermedio que cuente frames sin añadir una copia; en ese modo `captureFps` es `0` (no medido), nunca un valor estimado. Las mejoras de FPS/latencia deben compararse en dispositivo con las líneas `Telemetría:` y `Rendimiento:`; este entorno no tiene teléfono ni `adb`.

`DeviceCompat.kt` registra en el diagnóstico el modelo, Android/One UI, códec y FPS soportados, sin bloquear funciones por dispositivo.

## Compilación

Con el wrapper incluido (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21):

```sh
./gradlew assembleDebug --offline --no-daemon
```

O con el Gradle del entorno:

```sh
gradle assembleDebug
```

APK generado: `app/build/outputs/apk/debug/app-debug.apk`

## Estado de verificación

- `assembleDebug --offline --no-daemon`: correcto tras cada uno de los últimos cambios.
- `test`: `NO-SOURCE` (el proyecto no tiene tests automáticos).
- `lintDebug`: sin repetir en esta ronda; históricamente 17 errores y 100 avisos preexistentes.
- El `digest` de GitHub y el hash de la release v12 coinciden (`a935f5d4…c83493`).
- La huella del certificado debug es idéntica antes y después de las optimizaciones: `17927bed…0553e9`.
- APK local de esta ronda: 1.772.217 bytes, SHA-256 `73855617663e164669e507b75640d0ff76ba7dbb2aee3831afe5c7cdd9ce753d`; APK publicado en v12: 1.705.661 bytes.
- No hay teléfono ni `adb` en este entorno: **no se ha validado en dispositivo** la instalación OTA real, la grabación simultánea de voz y audio interno, la estabilidad a 60 FPS en juegos ni las tres grabaciones seguidas en Android 14+.

## Pendiente

- Publicar una nueva release (v13) con las optimizaciones locales cuando se validen en teléfono; **no se ha publicado ni subido APK sin autorización**.
- Tres grabaciones seguidas con el mismo consentimiento en Android 14+, con y sin marca de agua; pausa/reanudación; giro y cambio de tamaño en modo «Una app».
- Fallo simulado del primer encoder, cierre con AAC colgado (no publicar MP4) y OTA completa desde la versión anterior.
- Control numérico libre de bitrate en kbps (por ahora se seleccionan perfiles: AUTO / Bajo / Medio / Alto / Ultra / Máximo).
- Validación en dispositivo de grabaciones largas en segundo plano con las políticas de batería del fabricante, especialmente Samsung Galaxy A17 / One UI.
- Interfaz editable para cambiar la URL del servidor OTA (el backend ya admite `setReleasesUrl`).

## Acerca de SmoothRec

- Creador y responsable del proyecto: **Keiner**.
- Asistencia de desarrollo e inteligencia artificial: **ChatGPT (OpenAI)**.
- Implementación y colaboración técnica: **OpenClaude y OpenCode**.
- TikTok: https://www.tiktok.com/@keinerxzv
- Instagram: https://www.instagram.com/keinerxzv_/
