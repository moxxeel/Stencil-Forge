package com.piratedog.stencilforge.processor

data class StencilParams(
    // 0..1 — Sobel threshold (high): higher = fewer, cleaner edges
    val edgeThreshold: Float = 0.3f,
    // 0..1 — shadow/shading intensity (legacy, kept for presets)
    val shadowIntensity: Float = 0.5f,
    // 0..1 — stroke width multiplier
    val lineThickness: Float = 0.5f,
    // 0..1 — CLAHE equalization strength
    val contrast: Float = 0.5f,
    // 0..1 — Gaussian blur radius before edge detection
    val blurRadius: Float = 0.3f,
    // Invert output (white lines on black background)
    val invertColors: Boolean = false,
    // 0..1 — unsharp mask strength: enhances fine details before Sobel
    val sharpness: Float = 0.0f,
    // 0..1 — hysteresis low/high ratio: higher = more weak edges connected
    val edgeConnectivity: Float = 0.4f
)
