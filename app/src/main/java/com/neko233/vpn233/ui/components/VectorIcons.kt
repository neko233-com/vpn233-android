package com.neko233.vpn233.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    private val black = SolidColor(Color.Black)

    val ShieldBolt: ImageVector by lazy {
        icon("ShieldBolt") {
            path(fill = black) {
                moveTo(12f, 2f)
                lineTo(4f, 5.5f)
                verticalLineTo(11.5f)
                curveTo(4f, 16.85f, 7.42f, 21.74f, 12f, 23f)
                curveTo(16.58f, 21.74f, 20f, 16.85f, 20f, 11.5f)
                verticalLineTo(5.5f)
                close()
                moveTo(11.2f, 17.2f)
                lineTo(12.1f, 12.8f)
                horizontalLineTo(9.2f)
                lineTo(12.8f, 6.8f)
                lineTo(11.9f, 11.2f)
                horizontalLineTo(14.8f)
                close()
            }
        }
    }

    val Nodes: ImageVector by lazy {
        icon("Nodes") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 5f); curveTo(13.66f, 5f, 15f, 6.34f, 15f, 8f)
                curveTo(15f, 9.66f, 13.66f, 11f, 12f, 11f); curveTo(10.34f, 11f, 9f, 9.66f, 9f, 8f)
                curveTo(9f, 6.34f, 10.34f, 5f, 12f, 5f); close()
                moveTo(5f, 16f); curveTo(6.66f, 16f, 8f, 17.34f, 8f, 19f)
                moveTo(19f, 16f); curveTo(17.34f, 16f, 16f, 17.34f, 16f, 19f)
                moveTo(12f, 11f); lineTo(12f, 15f)
                moveTo(6.5f, 16.5f); lineTo(12f, 15f); lineTo(17.5f, 16.5f)
            }
        }
    }

    val ConfigDoc: ImageVector by lazy {
        icon("ConfigDoc") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 3f); horizontalLineTo(7f); curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                verticalLineTo(19f); curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f)
                horizontalLineTo(17f); curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f)
                verticalLineTo(8f); close()
                moveTo(14f, 3f); verticalLineTo(8f); horizontalLineTo(19f)
                moveTo(9f, 13f); horizontalLineTo(15f)
                moveTo(9f, 17f); horizontalLineTo(13f)
            }
        }
    }

    val SettingsGear: ImageVector by lazy {
        icon("SettingsGear") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 15f); curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
                curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f); curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f); close()
                moveTo(19.4f, 15f); lineTo(21f, 13.5f); verticalLineTo(10.5f); lineTo(19.4f, 9f)
                lineTo(18.6f, 7f); lineTo(19.5f, 5.1f); lineTo(17.4f, 3f); lineTo(15.5f, 3.9f)
                lineTo(13.5f, 3.1f); lineTo(12f, 1.5f); horizontalLineTo(9f)
                moveTo(12f, 3f); verticalLineTo(5f); moveTo(12f, 19f); verticalLineTo(21f)
                moveTo(3f, 12f); horizontalLineTo(5f); moveTo(19f, 12f); horizontalLineTo(21f)
                moveTo(5.6f, 5.6f); lineTo(7.1f, 7.1f); moveTo(16.9f, 16.9f); lineTo(18.4f, 18.4f)
                moveTo(18.4f, 5.6f); lineTo(16.9f, 7.1f); moveTo(7.1f, 16.9f); lineTo(5.6f, 18.4f)
            }
        }
    }

    val Power: ImageVector by lazy {
        icon("Power") {
            path(stroke = black, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 3f); verticalLineTo(11f)
                moveTo(16.95f, 6.05f)
                curveTo(18.21f, 7.31f, 19f, 9.06f, 19f, 11f)
                curveTo(19f, 14.87f, 15.87f, 18f, 12f, 18f)
                curveTo(8.13f, 18f, 5f, 14.87f, 5f, 11f)
                curveTo(5f, 9.06f, 5.79f, 7.31f, 7.05f, 6.05f)
            }
        }
    }

    val SpeedBolt: ImageVector by lazy {
        icon("SpeedBolt") {
            path(fill = black) {
                moveTo(13f, 2f); lineTo(4f, 14f); horizontalLineTo(11f); lineTo(10f, 22f)
                lineTo(20f, 10f); horizontalLineTo(13f); close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 11f); curveTo(19.5f, 7f, 16.1f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.3f, 20f, 18.14f, 18f, 19.36f, 15.14f)
                moveTo(20f, 5f); verticalLineTo(11f); horizontalLineTo(14f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            path(stroke = black, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(5f, 12f); horizontalLineTo(19f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(10.5f, 4f); curveTo(14.09f, 4f, 17f, 6.91f, 17f, 10.5f)
                curveTo(17f, 14.09f, 14.09f, 17f, 10.5f, 17f)
                curveTo(6.91f, 17f, 4f, 14.09f, 4f, 10.5f)
                curveTo(4f, 6.91f, 6.91f, 4f, 10.5f, 4f); close()
                moveTo(15.5f, 15.5f); lineTo(20f, 20f)
            }
        }
    }

    val Monitor: ImageVector by lazy {
        icon("Monitor") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 12f); horizontalLineTo(7f); lineTo(10f, 5f)
                lineTo(14f, 19f); lineTo(17f, 12f); horizontalLineTo(21f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            path(stroke = black, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            path(stroke = black, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(9f, 7f); verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(7f)
                moveTo(6f, 7f); lineTo(7f, 20f); horizontalLineTo(17f); lineTo(18f, 7f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        icon("Edit") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.5f, 4.5f); lineTo(19.5f, 8.5f); lineTo(8f, 20f)
                horizontalLineTo(4f); verticalLineTo(16f); close()
            }
        }
    }

    val ExportShare: ImageVector by lazy {
        icon("ExportShare") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 15f); verticalLineTo(4f)
                moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f)
                moveTo(4f, 14f); verticalLineTo(19f); horizontalLineTo(20f); verticalLineTo(14f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 9f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(9f); close()
                moveTo(15f, 9f); verticalLineTo(5f); horizontalLineTo(5f); verticalLineTo(15f); horizontalLineTo(9f)
            }
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
}
