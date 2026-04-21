package com.maddog.stencilforge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stencils")
data class StencilEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val originalImagePath: String,
    val stencilImagePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val edgeThreshold: Float = 0.3f,
    val shadowIntensity: Float = 0.5f,
    val lineThickness: Float = 0.5f,
    val contrast: Float = 0.5f,
    val invertColors: Boolean = false,
    val blurRadius: Float = 0.3f,
    val sharpness: Float = 0.0f,
    val edgeConnectivity: Float = 0.4f
)
