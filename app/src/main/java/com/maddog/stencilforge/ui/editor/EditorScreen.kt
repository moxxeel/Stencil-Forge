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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maddog.stencilforge.processor.StencilParams
import com.maddog.stencilforge.processor.StencilPreset
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
    val snackbarHostState = remember { SnackbarHostState() }

    val original by viewModel.originalBitmap.collectAsState()
    val stencil by viewModel.stencilBitmap.collectAsState()
    val params by viewModel.params.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    val loadedStencil by viewModel.loadedStencil.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()

    val isEditMode = stencilId != null

    var stencilName by remember { mutableStateOf("") }
    // 0f = full stencil, 1f = full original (before/after)
    var compareSlider by remember { mutableFloatStateOf(0f) }
    var showCompare by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(stencilId) {
        if (stencilId != null) viewModel.loadExistingStencil(stencilId)
    }

    LaunchedEffect(loadedStencil) {
        loadedStencil?.let { if (stencilName.isBlank()) stencilName = it.name }
    }

    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            when (result) {
                is EditorViewModel.SaveResult.Success -> {
                    snackbarHostState.showSnackbar("Stencil guardado")
                    viewModel.clearSaveResult()
                }
                is EditorViewModel.SaveResult.Error -> {
                    snackbarHostState.showSnackbar("Error: ${result.message}")
                    viewModel.clearSaveResult()
                }
                is EditorViewModel.SaveResult.Deleted -> {
                    viewModel.clearSaveResult()
                    onBack()
                }
            }
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Reset zoom when new image loaded
            scale = 1f; offsetX = 0f; offsetY = 0f
            viewModel.loadImage(it)
        }
    }

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
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
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
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Volver atrás" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo,
                        modifier = Modifier.semantics { contentDescription = "Deshacer" }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (canUndo) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.graphicsLayer(scaleX = -1f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo,
                        modifier = Modifier.semantics { contentDescription = "Rehacer" }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (canRedo) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    if (stencil != null) {
                        IconButton(
                            onClick = { viewModel.shareStencil() },
                            modifier = Modifier.semantics { contentDescription = "Compartir stencil" }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                    }
                    if (isEditMode && loadedStencil != null) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.semantics { contentDescription = "Eliminar stencil" }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
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
            // --- Preview con zoom y before/after ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFF1A1A1A))
                    .clip(RoundedCornerShape(0.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (original != null && stencil != null) {
                    if (showCompare) {
                        // Before/After split view
                        BeforeAfterView(
                            original = original!!,
                            stencil = stencil!!,
                            splitRatio = compareSlider,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY
                        )
                    } else {
                        Image(
                            bitmap = stencil!!.asImageBitmap(),
                            contentDescription = "Vista previa del stencil procesado",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    }

                    // Badge top-left
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (showCompare) "Comparar" else "Stencil",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }

                    // Hint de zoom si hay imagen
                    if (scale == 1f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text("Pellizca para zoom", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        }
                    }

                } else if (isProcessing) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📷", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Selecciona una imagen", color = Color.Gray)
                    }
                }

                if (isProcessing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {

                Spacer(Modifier.height(12.dp))

                // --- Botones de acción ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isEditMode) 0.38f else 1f)
                                .semantics { contentDescription = "Elegir imagen de galería" },
                            enabled = !isEditMode
                        ) { Text("Elegir imagen") }
                    }

                    if (original != null && stencil != null) {
                        Button(
                            onClick = { showCompare = !showCompare },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = if (showCompare) "Ver solo stencil" else "Comparar original con stencil" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showCompare)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (showCompare)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) { Text(if (showCompare) "Stencil" else "Comparar") }
                    }
                }

                if (isEditMode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Editando stencil guardado — la imagen original está fija",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // --- Slider Before/After ---
                AnimatedVisibility(visible = showCompare && original != null && stencil != null) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Stencil", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("Original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = compareSlider,
                            onValueChange = { compareSlider = it },
                            valueRange = 0f..1f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Slider de comparación antes y después" },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- Presets ---
                Text(
                    "Estilos rápidos",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(StencilPreset.entries) { preset ->
                        ElevatedFilterChip(
                            selected = params == preset.params,
                            onClick = { viewModel.applyPreset(preset) },
                            label = { Text(preset.label) },
                            modifier = Modifier.semantics { contentDescription = "Aplicar estilo ${preset.label}" },
                            colors = FilterChipDefaults.elevatedFilterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // --- Parámetros ---
                Text(
                    "Ajuste fino",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                ParamSlider(
                    label = "Umbral de bordes",
                    value = params.edgeThreshold,
                    description = "Menor = más bordes detectados",
                    semanticLabel = "Ajustar umbral de bordes"
                ) { viewModel.updateParams(params.copy(edgeThreshold = it)) }

                ParamSlider(
                    label = "Grosor de línea",
                    value = params.lineThickness,
                    description = "Engrosamiento de trazos",
                    semanticLabel = "Ajustar grosor de línea"
                ) { viewModel.updateParams(params.copy(lineThickness = it)) }

                ParamSlider(
                    label = "Intensidad de sombra",
                    value = params.shadowIntensity,
                    description = "Simulación de degradado/sombreado",
                    semanticLabel = "Ajustar intensidad de sombra"
                ) { viewModel.updateParams(params.copy(shadowIntensity = it)) }

                ParamSlider(
                    label = "Contraste",
                    value = params.contrast,
                    description = "Realce de detalles antes del proceso",
                    semanticLabel = "Ajustar contraste"
                ) { viewModel.updateParams(params.copy(contrast = it)) }

                ParamSlider(
                    label = "Reducción de ruido",
                    value = params.blurRadius,
                    description = "Suaviza la imagen antes de detectar bordes",
                    semanticLabel = "Ajustar reducción de ruido"
                ) { viewModel.updateParams(params.copy(blurRadius = it)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics { contentDescription = "Invertir colores del stencil" },
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Nombre del stencil" },
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveStencil(stencilName) },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = if (isEditMode) "Actualizar stencil guardado" else "Guardar stencil" },
                                enabled = !isProcessing
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isEditMode) "Actualizar" else "Guardar")
                            }
                            Button(
                                onClick = {
                                    scope.launch { exportToGallery(ctx, stencil!!) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Exportar stencil a la galería" },
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Galería")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BeforeAfterView(
    original: Bitmap,
    stencil: Bitmap,
    splitRatio: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Base: original image
        Image(
            bitmap = original.asImageBitmap(),
            contentDescription = "Imagen original",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
        )
        // Overlay: stencil clipped from left by splitRatio
        val animatedRatio by animateFloatAsState(targetValue = splitRatio, label = "split")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            Image(
                bitmap = stencil.asImageBitmap(),
                contentDescription = "Stencil procesado",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Divider line
        if (splitRatio > 0f && splitRatio < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = (splitRatio * 100).dp.let { it })
            ) {
                // vertical divider line visual hint
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(2.dp)
                        .height(60.dp)
                        .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    description: String,
    semanticLabel: String,
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
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = semanticLabel },
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
