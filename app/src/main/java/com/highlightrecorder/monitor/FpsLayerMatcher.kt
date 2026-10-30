package com.highlightrecorder.monitor

/** 保存窗口类型与组件,不保存每次重开都会变化的日期/句柄/图层编号。 */
object FpsLayerMatcher {
    private val surfaceWindow = Regex("SurfaceView\\[([^]]+)]", RegexOption.IGNORE_CASE)
    private val component = Regex("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")
    private val blast = Regex("\\bBLAST\\b", RegexOption.IGNORE_CASE)

    fun key(layer: String): String {
        val kind = when {
            isSurface(layer) && blast.containsMatchIn(layer) -> "BLAST"
            isSurface(layer) -> "SURFACE"
            else -> "WINDOW"
        }
        val window = surfaceWindow.find(layer)?.groupValues?.get(1)
            ?: component.find(layer)?.value ?: ""
        return "$kind|$window"
    }

    private fun isSurface(layer: String) = layer.contains("SurfaceView", ignoreCase = true)

    fun match(candidates: List<String>, preferredKey: String): String? =
        candidates.filter { key(it) == preferredKey }.singleOrNull()

    fun automatic(candidates: List<String>, preferredKey: String?): String? {
        if (preferredKey != null) {
            val remembered = candidates.filter { key(it) == preferredKey }
            if (remembered.isNotEmpty()) return remembered.singleOrNull()
        }
        val blasts = candidates.filter { isSurface(it) && blast.containsMatchIn(it) }
        if (blasts.isNotEmpty()) return blasts.singleOrNull()
        val surfaces = candidates.filter(::isSurface)
        return if (surfaces.isNotEmpty()) surfaces.singleOrNull() else candidates.singleOrNull()
    }
}
