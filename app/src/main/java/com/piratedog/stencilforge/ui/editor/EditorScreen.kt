package com.piratedog.stencilforge.ui.editor

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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.piratedog.stencilforge.processor.StencilPreset
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

    val original        by viewModel.originalBitmap.collectAsState()
    val stencil         by viewModel.stencilBitmap.collectAsState()
    val params          by viewModel.params.collectAsState()
    val isProcessing    by viewModel.isProcessing.collectAsState()
    val saveResult      by viewModel.saveResult.collectAsState()
    val loadedStencil   by viewModel.loadedStencil.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()
    val canUndo         by viewModel.canUndo.collectAsState()
    val canRedo         by viewModel.canRedo.collectAsState()
    val showingOriginal by viewModel.showingOriginal.collectAsState()
    val processMode     by viewModel.processMode.collectAsState()

    val isEditMode = stencilId != null

    var stencilName      by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // true  = estado inicial: imagen 65% / menú 35%  (menú visible, botón ↓ para expandir)
    // false = menú expandido: imagen 40% / menú 60%  (botón ↑ para volver)
    var menuExpanded by remember { mutableStateOf(true) }

    // Zoom state
    var scale   by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // menuExpanded=true → imagen 65%, menuExpanded=false → imagen 40%
    val imageWeight by animateFloatAsState(
        targetValue = if (menuExpanded) 0.65f else 0.40f,
        animationSpec = tween(300),
        label = "imageWeight"
    )
    val panelWeight = 1f - imageWeight

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
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditMode) loadedStencil?.name ?: "Editar stencil" else "Nuevo stencil",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver atrás" }) {
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
                            Icons.Default.Refresh, contentDescription = null,
                            tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.graphicsLayer(scaleX = -1f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo,
                        modifier = Modifier.semantics { contentDescription = "Rehacer" }
                    ) {
                        Icon(
                            Icons.Default.Refresh, contentDescription = null,
                            tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    if (stencil != null) {
                        IconButton(onClick = { viewModel.shareStencil() }, modifier = Modifier.semantics { contentDescription = "Compartir stencil" }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                    }
                    if (isEditMode && loadedStencil != null) {
                        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.semantics { contentDescription = "Eliminar stencil" }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        // Layout principal: imagen estática arriba + panel scrolleable abajo
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // ── Imagen FIJA (no scrollea) — 65% o 40% de la pantalla ──────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(imageWeight)
                    .background(Color(0xFF1A1A1A))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                            else { offsetX = 0f; offsetY = 0f }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (original != null && stencil != null) {
                    val displayBitmap = if (showingOriginal) original!! else stencil!!
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = if (showingOriginal) "Imagen original" else "Vista previa del stencil procesado",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                    )
                    // Badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (showingOriginal) "Original" else if (processMode == EditorViewModel.ProcessMode.GRAYSCALE) "B&N" else "Stencil",
                            style = MaterialTheme.typography.labelSmall, color = Color.White
                        )
                    }
                    // Rotate buttons — bottom left
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.rotateImage(false) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rotar izquierda",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp).graphicsLayer(scaleX = -1f)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.rotateImage(true) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rotar derecha", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    // Toggle original/processed — bottom right
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                if (showingOriginal) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                else Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        TextButton(
                            onClick = { viewModel.toggleShowOriginal() },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (showingOriginal) "Ver stencil" else "Ver original",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
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
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Color.White) }
                }
            }

            // ── Botón expandir/colapsar menú ──────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { menuExpanded = !menuExpanded },
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .semantics { contentDescription = if (menuExpanded) "Expandir menú" else "Reducir menú" }
                ) {
                    Icon(
                        imageVector = if (menuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Panel de configuración SCROLLEABLE (siempre presente) ────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(panelWeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                    Spacer(Modifier.height(8.dp))

                    // Botón elegir imagen
                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isEditMode) 0.38f else 1f)
                            .semantics { contentDescription = "Elegir imagen de galería" },
                        enabled = !isEditMode
                    ) { Text("Elegir imagen") }

                    if (isEditMode) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Editando stencil guardado — la imagen original está fija",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val isStencilMode = processMode == EditorViewModel.ProcessMode.STENCIL

                    Spacer(Modifier.height(16.dp))

                    // Modo de conversión
                    Text("Modo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ElevatedFilterChip(
                            selected = isStencilMode,
                            onClick = { viewModel.setProcessMode(EditorViewModel.ProcessMode.STENCIL) },
                            label = { Text("Stencil") },
                            colors = FilterChipDefaults.elevatedFilterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        ElevatedFilterChip(
                            selected = !isStencilMode,
                            onClick = { viewModel.setProcessMode(EditorViewModel.ProcessMode.GRAYSCALE) },
                            label = { Text("Blanco y negro") },
                            colors = FilterChipDefaults.elevatedFilterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }

                    if (isStencilMode) {
                        Spacer(Modifier.height(16.dp))

                        // Presets
                        Text("Estilos rápidos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

                        // Ajuste fino
                        Text("Ajuste fino", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))

                        ParamSlider("Umbral de bordes", params.edgeThreshold, "Menor = más bordes detectados", "Ajustar umbral de bordes") {
                            viewModel.updateParams(params.copy(edgeThreshold = it))
                        }
                        ParamSlider("Grosor de línea", params.lineThickness, "Engrosamiento de trazos", "Ajustar grosor de línea") {
                            viewModel.updateParams(params.copy(lineThickness = it))
                        }
                        ParamSlider("Intensidad de sombra", params.shadowIntensity, "Simulación de degradado/sombreado", "Ajustar intensidad de sombra") {
                            viewModel.updateParams(params.copy(shadowIntensity = it))
                        }
                        ParamSlider("Contraste", params.contrast, "Realce de detalles antes del proceso", "Ajustar contraste") {
                            viewModel.updateParams(params.copy(contrast = it))
                        }
                        ParamSlider("Reducción de ruido", params.blurRadius, "Suaviza la imagen antes de detectar bordes", "Ajustar reducción de ruido") {
                            viewModel.updateParams(params.copy(blurRadius = it))
                        }
                        ParamSlider("Nitidez", params.sharpness, "Realza detalles finos antes de detectar bordes", "Ajustar nitidez") {
                            viewModel.updateParams(params.copy(sharpness = it))
                        }
                        ParamSlider("Conectividad de bordes", params.edgeConnectivity, "Cuántos bordes débiles se conectan a bordes fuertes", "Ajustar conectividad de bordes") {
                            viewModel.updateParams(params.copy(edgeConnectivity = it))
                        }

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
                                Text("Líneas blancas sobre fondo negro", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = params.invertColors, onCheckedChange = { viewModel.updateParams(params.copy(invertColors = it)) })
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Conversión directa a escala de grises con contraste optimizado",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // Guardar
                    AnimatedVisibility(visible = stencil != null) {
                        Column {
                            Text("Guardar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = stencilName,
                                onValueChange = { stencilName = it },
                                label = { Text("Nombre del stencil") },
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nombre del stencil" },
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.saveStencil(stencilName) },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = if (isEditMode) "Actualizar stencil guardado" else "Guardar stencil" },
                                    enabled = !isProcessing
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isEditMode) "Actualizar" else "Guardar")
                                }
                                Button(
                                    onClick = { scope.launch { exportToGallery(ctx, stencil!!) } },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "Exportar stencil a la galería" },
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
            }           // fin Column panel scrolleable
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
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = semanticLabel },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                stream = java.io.FileOutputStream(java.io.File(dir, filename))
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
