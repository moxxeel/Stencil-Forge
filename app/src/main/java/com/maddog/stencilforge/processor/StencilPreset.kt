package com.maddog.stencilforge.processor

enum class StencilPreset(val label: String, val params: StencilParams) {
    SKETCH(
        label = "Sketch",
        params = StencilParams(
            edgeThreshold = 0.25f,
            shadowIntensity = 0.3f,
            lineThickness = 0.3f,
            contrast = 0.4f,
            blurRadius = 0.25f,
            invertColors = false
        )
    ),
    BOLD(
        label = "Bold",
        params = StencilParams(
            edgeThreshold = 0.15f,
            shadowIntensity = 0.6f,
            lineThickness = 0.8f,
            contrast = 0.7f,
            blurRadius = 0.2f,
            invertColors = false
        )
    ),
    FINE_ART(
        label = "Fine Art",
        params = StencilParams(
            edgeThreshold = 0.35f,
            shadowIntensity = 0.7f,
            lineThickness = 0.2f,
            contrast = 0.55f,
            blurRadius = 0.4f,
            invertColors = false
        )
    ),
    MINIMAL(
        label = "Minimal",
        params = StencilParams(
            edgeThreshold = 0.5f,
            shadowIntensity = 0.0f,
            lineThickness = 0.1f,
            contrast = 0.3f,
            blurRadius = 0.5f,
            invertColors = false
        )
    ),
    TRANSFER(
        label = "Transfer",
        params = StencilParams(
            edgeThreshold = 0.2f,
            shadowIntensity = 0.4f,
            lineThickness = 0.6f,
            contrast = 0.6f,
            blurRadius = 0.3f,
            invertColors = true
        )
    )
}
