package local.clashfarmer.desktop.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * The app icon, res/drawable/ic_farmer.xml path for path: a gold shield with a dark "F" on a
 * navy square, 108 units across.
 */
val FarmerIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ic_farmer",
        defaultWidth = 108.dp,
        defaultHeight = 108.dp,
        viewportWidth = 108f,
        viewportHeight = 108f
    ).apply {
        // M0,0h108v108h-108z
        path(fill = SolidColor(Color(0xFF121B2A))) {
            moveTo(0f, 0f)
            horizontalLineToRelative(108f)
            verticalLineToRelative(108f)
            horizontalLineToRelative(-108f)
            close()
        }
        // M29,30h50v30c0,15 -25,26 -25,26s-25,-11 -25,-26z
        path(fill = SolidColor(Color(0xFFF2C66D))) {
            moveTo(29f, 30f)
            horizontalLineToRelative(50f)
            verticalLineToRelative(30f)
            curveToRelative(0f, 15f, -25f, 26f, -25f, 26f)
            reflectiveCurveToRelative(-25f, -11f, -25f, -26f)
            close()
        }
        // M38,40h32v9h-23v8h18v9h-18v9h-9z
        path(fill = SolidColor(Color(0xFF121B2A))) {
            moveTo(38f, 40f)
            horizontalLineToRelative(32f)
            verticalLineToRelative(9f)
            horizontalLineToRelative(-23f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(18f)
            verticalLineToRelative(9f)
            horizontalLineToRelative(-18f)
            verticalLineToRelative(9f)
            horizontalLineToRelative(-9f)
            close()
        }
    }.build()
}

/** [FarmerIcon] as a painter, for `Window(icon = ...)` and `Tray(icon = ...)`. */
@Composable
fun farmerIconPainter(): Painter = rememberVectorPainter(FarmerIcon)
