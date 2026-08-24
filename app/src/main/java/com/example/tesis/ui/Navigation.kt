package com.example.tesis.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Detection : Screen("detection", "Detección", Icons.Default.Science)
    object History : Screen("history", "Historial", Icons.Default.History)
    object Reports : Screen("reports", "Reportes", Icons.Default.QueryStats)
    object Map : Screen("map", "Mapa", Icons.Default.Map)
    object Settings : Screen("settings", "Ajustes", Icons.Default.Settings)

    /** Se abre desde Ajustes, no desde la barra inferior: es configuración, no una vista de trabajo. */
    object Traps : Screen("traps", "Trampas", Icons.Default.GridOn)
    object Sessions : Screen("sessions", "Sesiones", Icons.Default.Assignment)
}
