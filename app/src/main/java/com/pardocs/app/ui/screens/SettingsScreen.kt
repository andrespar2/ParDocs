package com.pardocs.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pardocs.app.BuildConfig
import com.pardocs.app.ads.AdsConsentManager
import com.pardocs.app.core.Storage
import com.pardocs.app.ui.components.ParDocsCard
import com.pardocs.app.ui.components.SecondaryButton
import com.pardocs.app.ui.components.SectionLabel
import com.pardocs.app.ui.theme.TextFaint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TopAppBar(
            title = { Text("Ajustes") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        Spacer(Modifier.height(8.dp))

        ParDocsCard(Modifier.fillMaxWidth()) {
            Text("ParDocs", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            Spacer(Modifier.height(10.dp))
            Text(
                "Gestión, edición y conversión de archivos. Todas las operaciones ocurren en tu dispositivo: " +
                    "tus archivos nunca se envían a ningún servidor.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Funciones reales incluidas")
        Spacer(Modifier.height(8.dp))
        ParDocsCard(Modifier.fillMaxWidth()) {
            FeatureLine("PDF: unir, dividir, extraer, organizar, comprimir, proteger, desbloquear, PDF ↔ imágenes y texto.")
            FeatureLine("Imágenes: conversión JPG/PNG/WEBP/BMP/TIFF, lectura HEIC/GIF/AVIF, compresión y agrupación en PDF.")
            FeatureLine("Metadatos EXIF: visionado y borrado (GPS, cámara, fechas…) individual o por lotes, sin pérdida de calidad.")
            FeatureLine("Documentos: extracción de texto de Word, Excel, PowerPoint, TXT, RTF y HTML; conversión a PDF.")
            FeatureLine("Procesamiento local, selección múltiple, operaciones por lotes, guardado y uso compartido.")
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Limitaciones honestas")
        Spacer(Modifier.height(8.dp))
        ParDocsCard(Modifier.fillMaxWidth()) {
            FeatureLine("No se puede generar HEIC/AVIF: Android no expone un codificador público (sí se pueden leer).")
            FeatureLine("Los .doc/.xls/.ppt binarios (OLE) no se procesan localmente: guarda como .docx/.xlsx/.pptx.")
            FeatureLine("Comprimir PDF rasteriza las páginas: reduce tamaño pero elimina el texto seleccionable.")
            FeatureLine("Los PDF escaneados no tienen texto que extraer (sin OCR en esta versión).")
            FeatureLine("Los TIFF en mosaico (tiled) y CMYK no están soportados.")
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Privacidad y anuncios")
        Spacer(Modifier.height(8.dp))
        ParDocsCard(Modifier.fillMaxWidth()) {
            FeatureLine("ParDocs muestra un banner publicitario de Google AdMob únicamente en la pantalla de inicio.")
            FeatureLine("El banner usa la conexión solo para solicitar el anuncio; tus documentos y su procesamiento siguen siendo 100 % locales.")
            FeatureLine("Si no hay conexión, no hay consentimiento o no hay anuncios disponibles, la app funciona con total normalidad.")
        }
        if (AdsConsentManager.privacyOptionsRequired) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "Opciones de privacidad",
                icon = Icons.Outlined.PrivacyTip,
                onClick = {
                    val activity = context.findActivity()
                    if (activity != null) {
                        AdsConsentManager.showPrivacyOptions(activity)
                    } else {
                        Toast.makeText(context, "No disponible en este momento", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Mantenimiento")
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = "Vaciar caché de trabajo",
            onClick = {
                Storage.clearWork(context)
                Toast.makeText(context, "Caché eliminada", Toast.LENGTH_SHORT).show()
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Las copias temporales que ParDocs hace de tus archivos viven en la caché de la app y se limpian aquí o al desinstalar.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextFaint,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.CheckCircle,
            null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Unwrap the Compose [Context] until the hosting [Activity] is found. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}