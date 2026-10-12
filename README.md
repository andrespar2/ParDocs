# ParDocs

Aplicación Android 100 % local para gestionar PDF, imágenes y documentos.
Inspirada en iLovePDF pero **sin conexión a internet**: todos los archivos se
procesan en el dispositivo. Privacidad primero: la app no tiene permiso de red.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blueviolet)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/minSdk-26-green)](https://developer.android.com/tools/releases/platforms)
[![Licencia](https://img.shields.io/badge/licencia-Apache--2.0-blue)](LICENSE)

## Características

### PDF
| Herramienta | Qué hace |
|---|---|
| **Unir PDF** | Combina varios PDF (hasta 30) en uno solo. |
| **Dividir PDF** | Extrae rangos (`1-3, 5, 8-`) o trocea cada N páginas. |
| **Extraer páginas** | Guarda solo las páginas elegidas en un PDF nuevo. |
| **Organizar páginas** | Reordena, rota y elimina páginas con vista previa. |
| **Proteger con contraseña** | Cifrado AES-128 y permisos (impresión, copia, edición). |
| **Desbloquear PDF** | Elimina el cifrado con la contraseña correcta. |
| **PDF a imágenes** | Cada página a JPG o PNG. |
| **PDF a texto** | Extrae el texto seleccionable a un TXT. |
| **Comprimir PDF** | Rasteriza a menor resolución (ideal para escaneos). |
| **Limpiar metadatos PDF** | Borra autor, fechas, creador y otros datos. |

> ⚠️ **Honestidad de la compresión:** "Comprimir PDF" rasteriza las páginas
> (las convierte en imagen). Reduce mucho el tamaño de escaneos, pero **el
> texto deja de ser seleccionable**. La compresión estructural ligera (definida
> como "deshacer redundancias internas con los píxeles intactos") **no está
> implementada**; no se simula.

### Imágenes
| Herramienta | Qué hace |
|---|---|
| **Convertir formato** | JPG, PNG, WEBP, BMP, TIFF en ambas direcciones.  |
| **Comprimir imágenes** | Redimensiona y re-comprime para web o mensajería. |
| **Imágenes a PDF** | Agrupa fotos o escaneos en un PDF (A4 o tamaño de imagen). |
| **Metadatos EXIF** | Consulta y borra GPS, cámara, fechas y autor, individual o en lote, sin perder calidad de píxeles. |

Formatos que se **leen** pero no se pueden generar en el dispositivo:
HEIC/HEIF, GIF, AVIF e ICO (la app lo explica con claridad en lugar de
simularlo). TIFF usa un codec propio: RGB, escala de grises y paleta; **no**
soporta mosaicos (tiled) ni CMYK; la compresión soportada es sin compresión,
Deflate, PackBits y LZW.

### Documentos
| Herramienta | Qué hace |
|---|---|
| **Documentos a texto** | Extrae texto de Word (.docx), Excel (.xlsx), PowerPoint (.pptx), TXT, CSV, HTML y RTF. |
| **Documentos a PDF** | Convierte documentos a PDF; el HTML se renderiza con su formato mediante el motor WebView del dispositivo (con fallback a texto). |

> ⚠️ Los formatos binarios heredados (.doc, .xls, .ppt, ODF) usan contenedores
> propietarios y **no pueden procesarse localmente**; la app lo explica y
> sugiere guardar como .docx/.xlsx/.pptx.

## Privacidad

- **Sin permiso `INTERNET`.** El procesamiento es íntegramente local.
- Los archivos importados se copian al espacio privado de la app (`cache`) y se
  procesan desde ahí; los resultados van a `output/` en el directorio externo
  específico de la app (no requiere permisos de almacenamiento).
- Nada se sube a ningún servidor.

## Arquitectura

- **Lenguaje/UI:** Kotlin 2.0 + Jetpack Compose (Material 3), tema *AMOLED negro*
  (fondo puro `#000000`).
- **Módulo único:** `app` (paquete `com.pardocs.app`).
- **PDF estructural:** [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)
  (2.0.27.0, Apache-2.0, Java puro → APK universal sin librerías nativas).
- **Renderizado y generación:** `android.graphics.pdf.PdfRenderer` /
  `PdfDocument` + flujo de impresión WebView para HTML→PDF.
- **Codecs propios:** BMP y TIFF en Kotlin puro a nivel de píxel
  (clase `PixelImage`), probados en JVM.
- **Metadatos EXIF:** `androidx.exifinterface`.
- **Office:** extracción de texto OOXML con `XmlPullParser` (sin librerías
  pesadas).

```
app/src/main/java/com/pardocs/app/
├── core/     Modelo de archivos, formatos, almacenamiento, rangos de página
├── engine/   Motores (PDF, imágenes, EXIF, office, texto) y codecs BMP/TIFF
├── tools/    Catálogo de las 16 herramientas
├── ui/       Compose: navegación, pantallas, tema AMOLED, componentes
└── vm/       ViewModel del flujo de herramientas
```

## Compilar

Requisitos: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```bash
# Para depuración (app: com.pardocs.app.debug)
./gradlew.bat :app:assembleDebug

# Para publicación (firma con el keystore de desarrollo incluido)
./gradlew.bat :app:assembleRelease
# APK universal: app/build/outputs/apk/release/app-release.apk
```

La firma de *release* usa `keystore.properties` + `pardocs-dev.keystore`
(incluidos para que la construcción sea reproducible). **Antes de publicar en
una tienda, genera tu propio keystore** y actualiza ambos archivos.

### Tests

```bash
# Tests unitarios de JVM (rangos, formatos, texto, OOXML, codecs BMP/TIFF)
./gradlew.bat :app:testDebugUnitTest

# Tests instrumentados (motores reales) — requiere dispositivo/emulador
./gradlew.bat :app:connectedDebugAndroidTest
```

## Instalación

Copia `app-release.apk` al dispositivo y habilita "Instalar apps desconocidas"
o instala con `adb install app/build/outputs/apk/release/app-release.apk`.

## Licencia

Apache-2.0. Ver [LICENSE](LICENSE).