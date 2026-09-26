package com.personal.ledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable fun LedgerGlyph(kind: String, modifier: Modifier=Modifier, color: Color=androidx.compose.material3.LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val u=size.minDimension/24f
        fun line(x: Float,y: Float,a: Float,b: Float)=drawLine(color,Offset(x*u,y*u),Offset(a*u,b*u),2*u,StrokeCap.Round)
        fun rect(x: Float,y: Float,w: Float,h: Float)=drawRoundRect(color,Offset(x*u,y*u),Size(w*u,h*u),androidx.compose.ui.geometry.CornerRadius(2*u),style=Stroke(1.8f*u))
        when(kind) {
            "search" -> {drawCircle(color,7*u,Offset(10*u,10*u),style=Stroke(2*u));line(15f,15f,21f,21f)}
            "calendar" -> {rect(3f,5f,18f,16f);line(3f,10f,21f,10f);line(8f,3f,8f,7f);line(16f,3f,16f,7f);line(8f,14f,10f,14f);line(14f,17f,16f,17f)}
            "person" -> {drawCircle(color,4*u,Offset(12*u,6*u),style=Stroke(2*u));drawArc(color,180f,180f,false,Offset(4*u,12*u),Size(16*u,16*u),style=Stroke(2*u));line(4f,20f,20f,20f)}
            "bars" -> {line(5f,20f,5f,11f);line(12f,20f,12f,4f);line(19f,20f,19f,8f)}
            "餐饮" -> {line(6f,3f,6f,21f);line(3f,3f,3f,9f);line(9f,3f,9f,9f);line(3f,9f,9f,9f);line(18f,3f,18f,21f);line(15f,3f,15f,12f);line(15f,12f,18f,12f)}
            "交通" -> {rect(5f,3f,14f,16f);rect(8f,6f,8f,6f);line(8f,19f,6f,22f);line(16f,19f,18f,22f);drawCircle(color,u,Offset(8*u,16*u));drawCircle(color,u,Offset(16*u,16*u))}
            "日用品" -> {rect(4f,8f,16f,13f);drawArc(color,180f,180f,false,Offset(8*u,2*u),Size(8*u,12*u),style=Stroke(2*u))}
            else -> {rect(4f,3f,16f,18f);line(8f,8f,16f,8f);line(8f,12f,16f,12f);line(8f,16f,13f,16f)}
        }
    }
}
