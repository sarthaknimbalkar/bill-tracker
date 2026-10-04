package com.local.billtracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.luminance
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow

/** Session unlock flag; Settings sets it when the user turns the lock on so they aren't locked out mid-session. */
object LockState { val unlocked = MutableStateFlow(false) }

data class OpenRequest(val billId: String, val pay: Boolean)

class MainActivity : FragmentActivity() {
    private val openReq = MutableStateFlow<OpenRequest?>(null)
    private val unlocked get() = LockState.unlocked
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Reminders.createChannel(this)
        Reminders.rescheduleAll(this)
        AutoBackup.schedule(this)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent {
            val s by Store.get(this).settings.collectAsState()
            val isUnlocked by unlocked.collectAsState()
            AppTheme(s.theme) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars
                    }
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (s.appLock && !isUnlocked) LockScreen(::authenticate) else App(openReq)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock after 60 s in the background.
        if (backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt > 60_000) unlocked.value = false
        backgroundedAt = 0L
        if (Store.get(this).settings.value.appLock && !unlocked.value) authenticate()
        Widget.updateAll(this)
    }

    override fun onStop() { super.onStop(); backgroundedAt = System.currentTimeMillis() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        i?.getStringExtra("billId")?.let { openReq.value = OpenRequest(it, i.getBooleanExtra("pay", false)) }
    }

    fun authenticate() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked.value = true }
        })
        runCatching {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Bill Tracker")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
        }.onFailure { unlocked.value = true } // No lock configured on the phone — don't trap the user out.
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Bill Tracker is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock") }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
private val tabs = listOf(
    Tab("home", "Bills", Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong),
    Tab("calendar", "Calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab("stats", "Insights", Icons.Outlined.Insights, Icons.Filled.Insights),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun App(openReq: MutableStateFlow<OpenRequest?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val req by openReq.collectAsState()
    LaunchedEffect(req) { req?.let { nav.navigate("bill/${it.billId}?pay=${it.pay}"); openReq.value = null } }
    val open: (String) -> Unit = { nav.navigate("bill/$it?pay=false") }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(route in tabs.map { it.route }, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val sel = route == t.route
                        NavigationBarItem(sel, onClick = {
                            nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                        }, icon = { Icon(if (sel) t.selectedIcon else t.icon, null) }, label = { Text(t.label) })
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        NavHost(nav, "home", Modifier.padding(pad),
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            composable("home") { HomeScreen(onOpen = open, onAdd = { nav.navigate("edit/new") }, onSearch = { nav.navigate("search") }) }
            composable("calendar") { CalendarScreen(onOpen = open) }
            composable("stats") { StatsScreen() }
            composable("settings") { SettingsScreen() }
            composable("search") { SearchScreen(onBack = { nav.popBackStack() }, onOpen = open) }
            composable("bill/{id}?pay={pay}") { e ->
                BillDetailScreen(e.arguments?.getString("id")!!, e.arguments?.getString("pay") == "true",
                    onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/$it") })
            }
            composable("edit/{id}") { e ->
                val id = e.arguments?.getString("id")?.takeIf { it != "new" }
                EditBillScreen(id, onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack("home", false) })
            }
        }
    }
}
