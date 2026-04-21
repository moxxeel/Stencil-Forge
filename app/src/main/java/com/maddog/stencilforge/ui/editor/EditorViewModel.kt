package com.maddog.stencilforge.ui.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maddog.stencilforge.data.StencilEntity
import com.maddog.stencilforge.data.StencilRepository
import com.maddog.stencilforge.processor.StencilParams
import com.maddog.stencilforge.processor.StencilProcessor
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

    // Cuando se edita un stencil existente, guarda la entidad cargada
    private val _loadedStencil = MutableStateFlow<StencilEntity?>(null)
    val loadedStencil: StateFlow<StencilEntity?> = _loadedStencil.asStateFlow()

    private var debounceJob: Job? = null

    // Carga un stencil existente desde la base de datos y su imagen original
    fun loadExistingStencil(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = repo.getById(id) ?: return@launch
            _loadedStencil.value = entity

            val params = StencilParams(
                edgeThreshold = entity.edgeThreshold,
                shadowIntensity = entity.shadowIntensity,
                lineThickness = entity.lineThickness,
                contrast = entity.contrast,
                invertColors = entity.invertColors
            )
            _params.value = params

            val bmp = BitmapFactory.decodeFile(entity.originalImagePath) ?: return@launch
            _originalBitmap.value = bmp
            processWithCurrentParams()
        }
    }

    // Carga una imagen nueva desde galería (solo modo nuevo stencil)
    fun loadImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val bmp = loadAndDownsample(ctx, uri) ?: return@launch
            _originalBitmap.value = bmp
            processWithCurrentParams()
        }
    }

    fun updateParams(newParams: StencilParams) {
        _params.value = newParams
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300)
            processWithCurrentParams()
        }
    }

    private suspend fun processWithCurrentParams() {
        val bmp = _originalBitmap.value ?: return
        _isProcessing.value = true
        val result = withContext(Dispatchers.Default) {
            StencilProcessor.processAndConvert(bmp, _params.value)
        }
        _stencilBitmap.value = result
        _isProcessing.value = false
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
                // Si editamos uno existente, reutilizamos los archivos de la imagen original
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
                    invertColors = p.invertColors
                )
                val id = repo.saveStencil(entity)
                _loadedStencil.value = entity.copy(id = id)
                _saveResult.value = SaveResult.Success(id, stencilFile.absolutePath)
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Error al guardar")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun deleteCurrentStencil() {
        val entity = _loadedStencil.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteStencil(entity)
            withContext(Dispatchers.Main) {
                _saveResult.value = SaveResult.Deleted
            }
        }
    }

    fun clearSaveResult() { _saveResult.value = null }

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
        } catch (e: Exception) { null }
    }

    sealed class SaveResult {
        data class Success(val id: Long, val path: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
        object Deleted : SaveResult()
    }
}
