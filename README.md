# SmoothRec

Grabador de pantalla Android independiente.

- Package / applicationId: `com.smoothrec.app`
- Kotlin + Android SDK nativo, interfaz XML (sin Compose)
- Sin dependencias externas (solo Android SDK y Kotlin stdlib)
- Sin C++, NDK ni bibliotecas nativas

## Compilación

Se usa el Gradle instalado en el entorno (9.9.2), AGP 8.7.3 y Kotlin 2.0.21:

```sh
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Fase 0 — Base

- Proyecto Gradle (`compileSdk 35`, `minSdk 23`, `targetSdk 35`, JVM 17).
- Interfaz oscura y minimalista con botón circular central, estados y ajustes.
- Solicitud de consentimiento de `MediaProjection`.
- Servicio en primer plano tipo `mediaProjection` con notificación persistente.
- `MediaProjection.Callback` para reaccionar a la revocación del consentimiento.
- Preparación del archivo en MediaStore.

## Fase 1 — Motor de grabación (implementado)

- `ScreenCapturer`: `VirtualDisplay` con `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` alimentado por la Surface del encoder.
- `HardwareVideoEncoder`: `MediaCodec` asíncrono con entrada Surface.
- `EncoderFactory`: selecciona el encoder H.264 hardware del dispositivo (`c2.qti.avc.encoder` en el Redmi Note 13 4G).
- `Mp4Muxer`: escribe los paquetes H.264 en un contenedor MP4 sobre el descriptor de MediaStore.
- `CaptureSession`: orquesta captura → codificación → muxer → MediaStore y libera todos los recursos.
- Finalización: `signalEndOfInputStream()`, drenado, `MediaMuxer.stop()/release()`, cierre del `ParcelFileDescriptor` y publicación del archivo con `IS_PENDING=0`.

### Configuración validada en el dispositivo

| Parámetro | Valor |
|---|---|
| Resolución | Seleccionable: 1080p / Original / 864p / 720p, siempre derivada de la relación de aspecto real |
| FPS | Seleccionable: 60 / 30 (objetivo, no garantía) |
| Códec | H.264 (`video/avc`) |
| Encoder | Hardware Qualcomm (`c2.qti.avc.encoder`) |
| Perfil / nivel | High / AVC Level 5 |
| Bitrate inicial | 8 Mbps |
| Contenedor | MP4 |
| Almacenamiento | `Movies/SmoothRec` vía MediaStore |

`CaptureSizeResolver` obtiene los píxeles reales del contenido al iniciar (`WindowManager.currentWindowMetrics.bounds` en API 30+, `Display.getRealSize` en versiones anteriores; la densidad se lee de `DisplayMetrics.densityDpi`). Calcula la relación de aspecto real, escala para que el lado largo no supere 1920 y el corto 1080, y alinea ambas dimensiones a la granularidad que reporta `VideoCapabilities` del encoder (2 en este dispositivo, evitando impares). La orientación queda fija durante la sesión.

No se escribe metadato de rotación: el `VirtualDisplay`, el encoder y el muxer usan las mismas dimensiones, por lo que el vídeo no aparece girado ni estirado.

**Limitación:** girar el teléfono durante una grabación no cambia la orientación del MP4. Hacerlo obligaría a reiniciar el encoder y a crear otra pista, lo que puede corromper el archivo; por eso no se implementa. El contenido puede aparecer con bandas negras si la pantalla física y el lienzo virtual tienen distinta relación de aspecto, pero el archivo sigue siendo válido.

60 FPS es el objetivo, no una garantía: depende del encoder, la carga térmica y la complejidad de la pantalla.

## Configuración de grabación

La pantalla de ajustes permite elegir resolución (1080p, Original, 864p, 720p) y FPS (60 o 30). Las preferencias se guardan en `SharedPreferences` y se aplican al iniciar cada grabación. La opción por defecto sigue siendo **1080p · 60 FPS**.

`CaptureSizeResolver` consulta `VideoCapabilities` del encoder hardware (`c2.qti.avc.encoder`) para validar tamaño, alineación y tasa. Si la combinación elegida no es compatible, se prueba primero la misma tasa con menor resolución y después 30 FPS, se muestra un aviso al usuario y la notificación refleja los valores realmente aplicados. Nunca se estira, recorta ni gira la imagen.

En el Redmi Note 13 4G (pantalla 1080×2400, límite hardware 1920×1920), las opciones 1080p, Original y 864p convergen en **864×1920** porque conservar la relación 9:20 impide alcanzar 1080 píxeles en el lado corto sin deformar. 720p produce **720×1600**. En pantallas con otra relación (p. ej. 1080×2160) las opciones sí difieren: 1080p → 960×1920, 864p → 864×1728, 720p → 720×1440.

## Pendiente (no implementado)

- Captura de audio y sincronización A/V.
- Selección manual de bitrate.
- Control de duración, pausa/reanudación y grabaciones en segundo plano extendido.
