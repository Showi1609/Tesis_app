package com.example.tesis.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.tesis.R

/**
 * Proporción real de logo_horizontal.png: 1800 × 520 px.
 *
 * Fijarla explícitamente evita el problema de dimensionar solo por altura: sin
 * ella el nodo ocupa todo el ancho disponible y la imagen queda dibujada pequeña
 * dentro de una caja mucho más grande, con espacio muerto a los lados.
 */
private const val LOGO_ASPECT_RATIO = 1800f / 520f

/**
 * Logotipo con texto, dimensionado por altura y con el ancho derivado de su
 * proporción real. Se usa en las pantallas de trabajo, Detección e Historial.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    height: Dp = 65.dp
) {
    Image(
        painter = painterResource(id = R.drawable.logo_horizontal),
        contentDescription = "BioCount MIP",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(height)
            .aspectRatio(LOGO_ASPECT_RATIO)
    )
}

/**
 * Logotipo sin texto, cuadrado. Para las pantallas donde el rótulo completo
 * competiría con el título y basta la marca: Reportes y Ajustes.
 */
@Composable
fun AppLogoIcon(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Image(
        painter = painterResource(id = R.drawable.logo_icon_only),
        contentDescription = "BioCount MIP",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}
