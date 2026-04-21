package com.maddog.stencilforge.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maddog.stencilforge.data.StencilEntity
import com.maddog.stencilforge.data.StencilRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StencilRepository(app)

    val stencils: StateFlow<List<StencilEntity>> = repo.getAllStencils()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteStencil(stencil: StencilEntity) {
        viewModelScope.launch { repo.deleteStencil(stencil) }
    }
}
