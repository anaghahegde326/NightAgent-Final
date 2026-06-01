package com.example.nightagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.ui.theme.*

@Composable
fun BottomNavBar(selected: String, onNavigate: (String) -> Unit) {
    // Fix 1: Remove hardcoded height(72.dp) — NavigationBar sizes itself and
    //         Material3 Scaffold already accounts for windowInsets, so the bar
    //         sits above the system navigation area automatically.
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        // Fix 2: Let the bar consume the navigation-bar inset itself so content
        //         never renders underneath the gesture/button bar.
        windowInsets = NavigationBarDefaults.windowInsets
    ) {
        val items = listOf(
            NavItem("Home",     "home",     Icons.Outlined.Home,     Icons.Filled.Home),
            NavItem("Map",      "map",      Icons.Outlined.Map,      Icons.Filled.Map),
            NavItem("Contacts", "contacts", Icons.Outlined.People,   Icons.Filled.People),
            NavItem("Safety",   "safety",   Icons.Outlined.Shield,   Icons.Filled.Shield),
            NavItem("Settings", "settings", Icons.Outlined.Settings, Icons.Filled.Settings)
        )

        items.forEach { item ->
            val isSelected = selected == item.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                // Fix 3: alwaysShowLabel keeps labels visible and evenly spaced
                //         on all screen sizes; no squishing.
                alwaysShowLabel = true,
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        // Fix 4: Responsive icon size — use dp not a fixed pixel value
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        color = if (isSelected) Lavender else TextSecondary
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Lavender,
                    unselectedIconColor = TextSecondary,
                    indicatorColor = BlushPink.copy(alpha = 0.2f)
                )
            )
        }
    }
}

private data class NavItem(
    val label: String,
    val route: String,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector
)
