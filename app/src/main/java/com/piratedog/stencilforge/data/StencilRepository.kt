package com.piratedog.stencilforge.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File

class StencilRepository(context: Context) {
    private val dao = StencilDatabase.getInstance(context).stencilDao()

    fun getAllStencils(): Flow<List<StencilEntity>> = dao.getAllStencils()

    suspend fun saveStencil(stencil: StencilEntity): Long = dao.insertStencil(stencil)

    suspend fun updateStencil(stencil: StencilEntity) = dao.updateStencil(stencil)

    suspend fun deleteStencil(stencil: StencilEntity) {
        File(stencil.stencilImagePath).takeIf { it.exists() }?.delete()
        dao.deleteStencil(stencil)
    }

    suspend fun getById(id: Long): StencilEntity? = dao.getStencilById(id)
}
