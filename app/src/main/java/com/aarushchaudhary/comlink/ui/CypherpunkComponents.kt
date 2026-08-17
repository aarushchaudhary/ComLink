package com.aarushchaudhary.comlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CypherpunkBottomBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    accentColor: Color
) {
    val tabs = listOf("CHATS", "CONTACTS", "PROFILE")
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Black)
            .border(1.dp, accentColor),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = currentTab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(index) }
                    .background(if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) accentColor else Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSelected) "> $title <" else title,
                    color = if (isSelected) accentColor else Color.DarkGray,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun CypherpunkColorWheel(
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    var touchPosition by remember { mutableStateOf<Offset?>(null) }
    
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = kotlin.math.min(size.width, size.height) / 2f
                    
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    
                    val distance = kotlin.math.min(Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(), radius)
                    val angle = (kotlin.math.atan2(dy.toDouble(), dx.toDouble()) * 180 / Math.PI + 360) % 360
                    
                    val saturation = distance / radius
                    val hsv = floatArrayOf(angle.toFloat(), saturation, 1f)
                    
                    touchPosition = change.position
                    onColorSelected(Color(android.graphics.Color.HSVToColor(hsv)))
                }
            }
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        val hueColors = listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
        drawCircle(
            brush = Brush.sweepGradient(hueColors, center),
            radius = radius,
            center = center
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        touchPosition?.let { pos ->
            val dx = pos.x - center.x
            val dy = pos.y - center.y
            val distance = kotlin.math.min(Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(), radius)
            val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
            
            val indicatorX = center.x + distance * kotlin.math.cos(angle).toFloat()
            val indicatorY = center.y + distance * kotlin.math.sin(angle).toFloat()
            
            drawLine(
                color = Color.Black,
                start = Offset(indicatorX - 12f, indicatorY),
                end = Offset(indicatorX + 12f, indicatorY),
                strokeWidth = 3f
            )
            drawLine(
                color = Color.Black,
                start = Offset(indicatorX, indicatorY - 12f),
                end = Offset(indicatorX, indicatorY + 12f),
                strokeWidth = 3f
            )
        }
    }
}
