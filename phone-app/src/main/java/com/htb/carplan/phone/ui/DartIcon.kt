package com.htb.carplan.phone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A target (two rings and a bull), for "다트" next to the pin of "장소" — the two used to share the pin icon
 * (디자인 평가 2026-11-04). icons-core has no target, so it is drawn here.
 */
val DartIcon: ImageVector by lazy {
    ImageVector.Builder("Dart", 24.dp, 24.dp, 24f, 24f).apply {
        fun ring(r: Float, w: Float) = path(stroke = SolidColor(Color.Black), strokeLineWidth = w) {
            moveTo(12f - r, 12f)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f + r, y1 = 12f)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f - r, y1 = 12f)
            close()
        }
        ring(9f, 2f)
        ring(5f, 2f)
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 12f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 14f, y1 = 12f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10f, y1 = 12f)
            close()
        }
    }.build()
}
