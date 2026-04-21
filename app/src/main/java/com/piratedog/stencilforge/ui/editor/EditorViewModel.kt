package com.piratedog.stencilforge.ui.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piratedog.stencilforge.data.StencilEntity
import com.piratedog.stencilforge.data.StencilRepository
import com.piratedog.stencilforge.processor.StencilParams
import com.piratedog.stencilforge.processor.StencilPreset
import com.piratedog.stencilforge.processor.StencilProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = StencilRepository(app)

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _stencilBitmap = MutableStateFlow<Bitmap?>(null)
    val stencilBitmap: StateFlow<Bitmap?> = _stencilBitmap.asStateFlow()

    private val _params = MutableStateFlow(StencilParams())
    val params: StateFlow<StencilParams> = _params.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    private val _loadedStencil = MutableStateFlow<StencilEntity?>(null)
    val loadedStencil: StateFlow<StencilEntity?> = _loadedStencil.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Undo/redo stacks
    private val undoStack = ArrayDeque<StencilParams>()
    private val redoStack = ArrayDeque<StencilParams>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // For toggle-preset: stores params before last preset was applied
    private var previousParams: StencilParams? = null

    // true while showing original image instead of stencil
    private val _showingOriginal = MutableStateFlow(false)
    val showingOriginal: StateFlow<Boolean> = _showingOriginal.asStateFlow()

    enum class ProcessMode { STENCIL, GRAYSCALE }

    private val _processMode = MutableStateFlow(ProcessMode.STENCIL)
    val processMode: StateFlow<ProcessMode> = _processMode.asStateFlow()

    // Params stored before entering GRAYSCALE mode, to restore on switch back
    private var paramsBeforeGrayscale: StencilParams? = null

    fun setProcessMode(mode: ProcessMode) {
        if (_processMode.value == mode) return
        if (mode == ProcessMode.GRAYSCALE) {
            paramsBeforeGrayscale = _params.value
            // Grayscale mode: high contrast, minimal blur, no edge processing artifacts
            _params.value = StencilParams(
                edgeThreshold   = 1.0f,
                shadowIntensity  = 0.0f,
                lineThickness    = 0.0f,
                contrast         = 0.8f,
                blurRadius       = 0.1f,
                invertColors     = false,
                sharpness        = 0.0f,
                edgeConnectivity = 0.0f
            )
        } else {
            paramsBeforeGrayscale?.let { _params.value = it }
            paramsBeforeGrayscale = null
        }
        _processMode.value = mode
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch { processWithCurrentParams() }
    }

    private var debounceJob: Job? = null

    fun loadExistingStencil(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = repo.getById(id) ?: run {
                    _errorMessage.value = "No se encontró el stencil"
                    return@launch
                }
                _loadedStencil.value = entity

                val params = StencilParams(
                    edgeThreshold = entity.edgeThreshold,
                    shadowIntensity = entity.shadowIntensity,
                    lineThickness = entity.lineThickness,
                    contrast = entity.contrast,
                    invertColors = entity.invertColors,
                    blurRadius = entity.blurRadius,
                    sharpness = entity.sharpness,
                    edgeConnectivity = entity.edgeConnectivity
                )
                _params.value = params
                undoStack.clear()
                redoStack.clear()
                updateUndoRedoState()

                val bmp = BitmapFactory.decodeFile(entity.originalImagePath) ?: run {
                    _errorMessage.value = "No se pudo cargar la imagen original"
                    return@launch
                }
                _originalBitmap.value = bmp
                processWithCurrentParams()
            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar: ${e.message}"
            }
        }
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val bmp = loadAndDownsample(ctx, uri) ?: run {
                    _errorMessage.value = "No se pudo cargar la imagen"
                    return@launch
                }
                _originalBitmap.value = bmp
                processWithCurrentParams()
            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar imagen: ${e.message}"
            }
        }
    }

    fun updateParams(newParams: StencilParams) {
        pushUndo(_params.value)
        _params.value = newParams
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300)
            processWithCurrentParams()
        }
    }

    fun applyPreset(preset: StencilPreset) {
        if (_params.value == preset.params) {
            // Tapping the active preset restores previous params
            previousParams?.let { updateParams(it) }
            previousParams = null
        } else {
            previousParams = _params.value
            updateParams(preset.params)
        }
    }

    fun toggleShowOriginal() {
        _showingOriginal.value = !_showingOriginal.value
    }

    fun rotateImage(clockwise: Boolean) {
        val bmp = _originalBitmap.value ?: return
        val matrix = android.graphics.Matrix().apply {
            postRotate(if (clockwise) 90f else -90f)
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        _originalBitmap.value = rotated
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            processWithCurrentParams()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.addLast(_params.value)
        _params.value = prev
        updateUndoRedoState()
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(150)
            processWithCurrentParams()
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(_params.value)
        _params.value = next
        updateUndoRedoState()
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(150)
            processWithCurrentParams()
        }
    }

    private fun pushUndo(params: StencilParams) {
        undoStack.addLast(params)
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
        updateUndoRedoState()
    }

    private fun updateUndoRedoState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private suspend fun processWithCurrentParams() {
        val bmp = _originalBitmap.value ?: return
        _isProcessing.value = true
        try {
            val result = withContext(Dispatchers.Default) {
                when (_processMode.value) {
                    ProcessMode.STENCIL   -> StencilProcessor.processAndConvert(bmp, _params.value)
                    ProcessMode.GRAYSCALE -> StencilProcessor.toGrayscaleBitmap(bmp)
                }
            }
            _stencilBitmap.value = result
        } catch (e: Exception) {
            _errorMessage.value = "Error al procesar imagen: ${e.message}"
        } finally {
            _isProcessing.value = false
        }
    }

    fun saveStencil(name: String) {
        val stencil = _stencilBitmap.value ?: return
        val original = _originalBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            try {
                val ctx = getApplication<Application>()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = File(ctx.filesDir, "stencils").also { it.mkdirs() }

                val existing = _loadedStencil.value
                val origPath = existing?.originalImagePath ?: run {
                    val f = File(dir, "original_${timestamp}.jpg")
                    FileOutputStream(f).use { original.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    f.absolutePath
                }

                val stencilFile = if (existing != null) {
                    File(existing.stencilImagePath)
                } else {
                    File(dir, "stencil_${timestamp}.png")
                }
                FileOutputStream(stencilFile).use { stencil.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val p = _params.value
                val entity = StencilEntity(
                    id = existing?.id ?: 0,
                    name = name.ifBlank { existing?.name ?: "Stencil $timestamp" },
                    originalImagePath = origPath,
                    stencilImagePath = stencilFile.absolutePath,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    edgeThreshold = p.edgeThreshold,
                    shadowIntensity = p.shadowIntensity,
                    lineThickness = p.lineThickness,
                    contrast = p.contrast,
                    invertColors = p.invertColors,
                    blurRadius = p.blurRadius,
                    sharpness = p.sharpness,
                    edgeConnectivity = p.edgeConnectivity
                )
                val savedId = repo.saveStencil(entity)
                _loadedStencil.value = entity.copy(id = savedId)
                _saveResult.value = SaveResult.Success(savedId, stencilFile.absolutePath)
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Error al guardar")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun shareStencil() {
        val stencil = _stencilBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
                val file = File(dir, "stencil_share.png")
                FileOutputStream(file).use { stencil.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(intent, "Compartir stencil").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                _errorMessage.value = "Error al compartir: ${e.message}"
            }
        }
    }

    fun deleteCurrentStencil() {
        val entity = _loadedStencil.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.deleteStencil(entity)
                withContext(Dispatchers.Main) {
                    _saveResult.value = SaveResult.Deleted
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error al eliminar: ${e.message}"
            }
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
    fun clearError() { _errorMessage.value = null }

    private fun loadAndDownsample(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val maxDim = 1200
            val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            null
        }
    }

    sealed class SaveResult {
        data class Success(val id: Long, val path: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
        object Deleted : SaveResult()
    }
}
