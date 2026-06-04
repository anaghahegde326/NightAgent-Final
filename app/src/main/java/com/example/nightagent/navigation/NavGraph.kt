package com.example.nightagent.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.*
import com.example.nightagent.evidence.EvidenceCaptureService
import com.example.nightagent.evidence.EvidenceScreen
import com.example.nightagent.sos.SOSManager
import com.example.nightagent.streaming.GuardianListenScreen
import com.example.nightagent.streaming.VictimStreamScreen
import com.example.nightagent.ui.activities.FakeCallActivity
import com.example.nightagent.ui.components.BottomNavBar
import com.example.nightagent.ui.screens.*
import com.example.nightagent.ui.stealth.StealthSettingsScreen
import com.example.nightagent.voicemessage.ui.UserRegistrationScreen
import com.example.nightagent.voicemessage.ui.VoiceMessageScreen
import com.example.nightagent.voicemessage.repository.VoiceMessageRepository
import kotlinx.coroutines.launch
@Composable
fun NavGraph(
    onShareLocationClick: () -> Unit,
    initialRoute: String? = null   // deep-link from FCM notification
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "splash"
    val coroutineScope = rememberCoroutineScope()

    // Navigate to deep-link once the nav graph is ready
    LaunchedEffect(initialRoute) {
        if (!initialRoute.isNullOrBlank()) {
            navController.navigate(initialRoute) {
                popUpTo("home") { inclusive = false }
            }
        }
    }

    val fullScreenRoutes = listOf("splash", "fakecall", "sosactive", "safewalk", "voicechat", "register", "victimstream", "guardianstream")
    val showBottomBar = fullScreenRoutes.none { currentRoute.startsWith(it) }

    Scaffold(
        // Fix 5: Tell Scaffold not to add its own window insets — the NavigationBar
        //         already consumes the bottom inset via NavigationBarDefaults.windowInsets,
        //         and the status bar is handled per-screen. Without this, insets are
        //         applied twice and the bottom bar gets double-padded / compressed.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    selected = currentRoute,
                    onNavigate = { route ->
                        coroutineScope.launch {
                            navController.navigate(route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        // Fix 6: Apply innerPadding so content never renders behind the bottom bar.
        //         consumeWindowInsets() prevents child layouts from re-applying the
        //         same insets a second time.
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable("splash") {
                SplashScreen(onTimeout = {
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                    }
                })
            }

            composable("home") {
                HomeScreen(
                    onSOSClick = {
                        val sosId = System.currentTimeMillis().toString()
                        // Start mic+location foreground service first (allowed from foreground Activity)
                        EvidenceCaptureService.start(context, sosId)
                        // Trigger SOS (SMS, alarm, Firestore session)
                        SOSManager.triggerSOS(context) {
                            EvidenceCaptureService.stop(context)
                        }
                        // Navigate to Evidence screen — CameraX starts there in the UI layer
                        navController.navigate("evidence") {
                            launchSingleTop = true
                        }
                    },

                    onFakeCallClick = {
                        context.startActivity(
                            android.content.Intent(context, FakeCallActivity::class.java)
                        )
                    },
                    onSafeWalkClick = { navController.navigate("safewalk") },
                    onShareLocationClick = onShareLocationClick
                )
            }

            composable("safewalk") { SafeWalkScreen() }

            composable("map") { MapScreen() }

            composable("contacts") { ContactsScreen(onVoiceChat = { uid -> navController.navigate("voicechat/$uid") }) }

            composable("safety") {
                SafetyScreen(onFakeCallClick = {
                    context.startActivity(
                        android.content.Intent(context, FakeCallActivity::class.java)
                    )
                })
            }

            composable("evidence") { EvidenceScreen() }

            composable("victimstream/{guardianUid}") { back ->
                val guardianUid = back.arguments?.getString("guardianUid") ?: ""
                VictimStreamScreen(
                    guardianUid = guardianUid,
                    onStopSOS   = { navController.popBackStack() }
                )
            }

            composable("guardianstream/{callId}") { back ->
                val callId = back.arguments?.getString("callId") ?: ""
                GuardianListenScreen(
                    callId  = callId,
                    onLeave = { navController.popBackStack() }
                )
            }

            composable("settings") {
                SettingsScreen(
                    onStealthClick   = { navController.navigate("stealth") },
                    onRegisterClick  = { navController.navigate("register") }
                )
            }

            composable("stealth") { StealthSettingsScreen() }

            // Phone number registration — required for app-to-app voice messaging
            composable("register") {
                val scope = rememberCoroutineScope()
                UserRegistrationScreen(
                    onRegister = { phone, name ->
                        scope.launch {
                            VoiceMessageRepository(context).registerCurrentUser(phone, name)
                        }
                        navController.popBackStack()
                    },
                    onSkip = { navController.popBackStack() }
                )
            }

            // Voice messaging — otherUserId passed as nav argument
            composable("voicechat/{otherUserId}") { backStackEntry ->
                val otherUserId = backStackEntry.arguments?.getString("otherUserId") ?: ""
                VoiceMessageScreen(
                    otherUserId = otherUserId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("sosactive") { SOSActivatedScreen() }
        }
    }
}

@Composable
fun SOSActivatedScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(32.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "SOS ACTIVATED",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("SMS sent, alarm active", fontSize = 16.sp)
            }
        }
    }
}
