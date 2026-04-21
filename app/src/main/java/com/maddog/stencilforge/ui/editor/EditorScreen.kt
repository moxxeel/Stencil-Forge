package com.maddog.stencilforge.ui.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maddog.stencilforge.processor.StencilParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    stencilId: Long? = null,
    viewModel: EditorViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val original by viewModel.originalBitmap.collectAsState()
    val stencil by viewModel.stencilBitmap.collectAsState()
    val params by viewModel.params.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    val loadedStencil by viewModel.loadedStencil.collectAsState()

    // true = estamos editando un stencil existente, false = nuevo
    val isEditMode = stencilId != null

    var stencilName by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Carga el stencil existente al entrar
    LaunchedEffect(stencilId) {
        if (stencilId != null) {
            viewModel.loadExistingStencil(stencilId)
        }
    }

    // Pre-pobla el nombre cuando se carga la entidad
    LaunchedEffect(loadedStencil) {
        loadedStencil?.let { if (stencilName.isBlank()) stencilName = it.name }
    }

    // Manejo de resultados de guardado / eliminación
    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            when (result) {
                is EditorViewModel.SaveResult.Success -> {
                    Toast.makeText(ctx, "Stencil guardado", Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveResult()
                }
                is EditorViewModel.SaveResult.Error -> {
                    Toast.makeText(ctx, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                    viewModel.clearSaveResult()
                }
                is EditorViewModel.SaveResult.Deleted -> {
                    viewModel.clearSaveResult()
                    onBack()
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.loadImage(it) } }

    // Diálogo de confirmación de eliminación
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar stencil") },
            text = { Text("¿Eliminar \"${loadedStencil?.name}\"? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteCurrentStencil()
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditMode) loadedStencil?.name ?: "Editar stencil"
                        else "Nuevo stencil",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    if (stencil != null) {
                        IconButton(onClick = {
                            scope.launch { exportToGallery(ctx, stencil!!) }
                        }) {
                            Icon(Icons.Default.Share, "Exportar PNG")
                        }
                    }
                    // Botón eliminar solo en modo edición
                    if (isEditMode && loadedStencil != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Eliminar stencil",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // --- Preview ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = if (showOriginal) original else stencil
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (showOriginal) "Original" else "Stencil",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                } else if (isProcessing) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text("Selecciona una imagen", color = Color.Gray)
                }

                if (isProcessing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {

                // --- Botones de acción superiores ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "Elegir imagen" solo disponible en modo nuevo
                    Box(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isEditMode) 0.38f else 1f),
                            enabled = !isEditMode
                        ) { Text("Elegir imagen") }
                    }

                    if (original != null) {
                        Button(
                            onClick = { showOriginal = !showOriginal },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) { Text(if (showOriginal) "Ver stencil" else "Ver original") }
                    }
                }

                // Aviso cuando está bloqueado el selector de imagen
                if (isEditMode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Editando stencil guardado — la imagen original está fija",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))

                // --- Parámetros ---
                Text(
                    "Parámetros",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                ParamSlider(
                    label = "Umbral de bordes",
                    value = params.edgeThreshold,
                    description = "Menor = más bordes detectados"
                ) { viewModel.updateParams(params.copy(edgeThreshold = it)) }

                ParamSlider(
                    label = "Grosor de línea",
                    value = params.lineThickness,
                    description = "Engrosamiento de trazos"
                ) { viewModel.updateParams(params.copy(lineThickness = it)) }

                ParamSlider(
                    label = "Intensidad de sombra",
                    value = params.shadowIntensity,
                    description = "Simulación de degradado/sombreado"
                ) { viewModel.updateParams(params.copy(shadowIntensity = it)) }

                ParamSlider(
                    label = "Contraste",
                    value = params.contrast,
                    description = "Realce de detalles antes del proceso"
                ) { viewModel.updateParams(params.copy(contrast = it)) }

                ParamSlider(
                    label = "Reducción de ruido",
                    value = params.blurRadius,
                    description = "Suaviza la imagen antes de detectar bordes"
                ) { viewModel.updateParams(params.copy(blurRadius = it)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Invertir colores", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Líneas blancas sobre fondo negro",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = params.invertColors,
                        onCheckedChange = { viewModel.updateParams(params.copy(invertColors = it)) }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // --- Guardar ---
                AnimatedVisibility(visible = stencil != null) {
                    Column {
                        Text(
                            "Guardar",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = stencilName,
                            onValueChange = { stencilName = it },
                            label = { Text("Nombre del stencil") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveStencil(stencilName) },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing
                            ) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isEditMode) "Actualizar" else "Guardar")
                            }
                            Button(
                                onClick = { scope.launch { exportToGallery(ctx, stencil!!) } },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Exportar PNG")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    description: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "%.2f".format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private suspend fun exportToGallery(ctx: Context, bitmap: Bitmap) {
    withContext(Dispatchers.IO) {
        try {
            val filename = "stencil_${System.currentTimeMillis()}.png"
            val stream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StencilForge")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                stream = uri?.let { ctx.contentResolver.openOutputStream(it) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val file = java.io.File(dir, filename)
                stream = java.io.FileOutputStream(file)
            }
            stream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "Exportado a Galería/StencilForge", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
