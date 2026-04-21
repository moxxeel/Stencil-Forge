package com.piratedog.stencilforge.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StencilDao {
    @Query("SELECT * FROM stencils ORDER BY createdAt DESC")
    fun getAllStencils(): Flow<List<StencilEntity>>

    @Query("SELECT * FROM stencils WHERE id = :id")
    suspend fun getStencilById(id: Long): StencilEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStencil(stencil: StencilEntity): Long

    @Update
    suspend fun updateStencil(stencil: StencilEntity)

    @Delete
    suspend fun deleteStencil(stencil: StencilEntity)

    @Query("DELETE FROM stencils WHERE id = :id")
    suspend fun deleteById(id: Long)
}
