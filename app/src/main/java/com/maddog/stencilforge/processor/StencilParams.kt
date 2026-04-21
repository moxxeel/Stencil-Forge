package com.maddog.stencilforge.processor

data class StencilParams(
    // 0..1 — sensitivity for edge detection (Sobel threshold)
    val edgeThreshold: Float = 0.3f,
    // 0..1 — how strong the crosshatch/gradient shadow effect is
    val shadowIntensity: Float = 0.5f,
    // 0..1 — stroke width multiplier for edge lines
    val lineThickness: Float = 0.5f,
    // 0..1 — global contrast boost before processing
    val contrast: Float = 0.5f,
    // Invert output (white lines on black — traditional stencil transfer look)
    val invertColors: Boolean = false,
    // 0..1 — Gaussian blur radius before edge detection (noise reduction)
    val blurRadius: Float = 0.3f
)
