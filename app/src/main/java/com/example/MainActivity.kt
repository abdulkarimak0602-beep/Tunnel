package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.model.AppLanguage
import com.example.model.ConnectionState
import com.example.model.ThemeMode
import com.example.ui.AppStrings
import com.example.ui.screens.AppsAndSitesScreen
import com.example.ui.screens.GatewaysScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.TunnelDashboardScreen
import com.example.ui.theme.DeshTunnelTheme
import com.example.viewmodel.DeshTunnelViewModel
import kotlinx.coroutines.launch

object NavRoutes {
    const val TUNNEL = "tunnel"
    const val GATEWAYS = "gateways"
    const val APPS = "apps"
    const val SECURITY = "security"
}

private data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeshTunnelApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeshTunnelApp(viewModel: DeshTunnelViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = AppStrings.forLanguage(state.preferences.language)
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val isCurrentlyDark = when (state.preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnConnection()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(strings.vpnPermissionDenied)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Proceed to VPN consent regardless of notification choice
        val prepareIntent = viewModel.needsVpnConsent()
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnConnection()
        }
    }

    val initiateVpnConnect: () -> Unit = {
        if (state.connectionState == ConnectionState.CONNECTED ||
            state.connectionState == ConnectionState.CONNECTING
        ) {
            viewModel.stopVpnConnection()
        } else {
            val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

            if (needsNotifPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val prepareIntent = viewModel.needsVpnConsent()
                if (prepareIntent != null) {
                    vpnConsentLauncher.launch(prepareIntent)
                } else {
                    viewModel.startVpnConnection()
                }
            }
        }
    }

    DeshTunnelTheme(themeMode = state.preferences.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: NavRoutes.TUNNEL

        val tabs = listOf(
            NavTabItem(
                route = NavRoutes.TUNNEL,
                label = strings.tabTunnel,
                selectedIcon = Icons.Filled.Shield,
                unselectedIcon = Icons.Outlined.Shield,
                testTag = "nav_tab_tunnel"
            ),
            NavTabItem(
                route = NavRoutes.GATEWAYS,
                label = strings.tabGateways,
                selectedIcon = Icons.Filled.Public,
                unselectedIcon = Icons.Outlined.Public,
                testTag = "nav_tab_gateways"
            ),
            NavTabItem(
                route = NavRoutes.APPS,
                label = strings.tabApps,
                selectedIcon = Icons.Filled.Apps,
                unselectedIcon = Icons.Outlined.Apps,
                testTag = "nav_tab_apps"
            ),
            NavTabItem(
                route = NavRoutes.SECURITY,
                label = strings.tabSecurity,
                selectedIcon = Icons.Filled.Security,
                unselectedIcon = Icons.Outlined.Security,
                testTag = "nav_tab_security"
            )
        )

        val navigateToTab: (String) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_deshtunnel_logo),
                                    contentDescription = strings.appTitle,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column {
                                    Text(
                                        text = strings.appTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = strings.subtitleTagline,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        actions = {
                            // Instant Language Toggle (বাংলা / EN)
                            Surface(
                                onClick = {
                                    val nextLang = if (state.preferences.language == AppLanguage.BANGLA) {
                                        AppLanguage.ENGLISH
                                    } else {
                                        AppLanguage.BANGLA
                                    }
                                    viewModel.setLanguage(nextLang)
                                },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.testTag("quick_lang_toggle")
                            ) {
                                Text(
                                    text = if (state.preferences.language == AppLanguage.BANGLA) "EN" else "বাং",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            // Instant Dark / Light Toggle
                            IconButton(
                                onClick = {
                                    viewModel.setThemeMode(
                                        if (isCurrentlyDark) ThemeMode.LIGHT else ThemeMode.DARK
                                    )
                                },
                                modifier = Modifier.testTag("quick_theme_toggle")
                            ) {
                                Icon(
                                    imageVector = if (isCurrentlyDark) {
                                        Icons.Outlined.LightMode
                                    } else {
                                        Icons.Outlined.DarkMode
                                    },
                                    contentDescription = strings.themeModeLabel
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail) {
                        NavigationRail(modifier = Modifier.fillMaxHeight()) {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = NavRoutes.TUNNEL,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 680.dp)
                        ) {
                            composable(NavRoutes.TUNNEL) {
                                TunnelDashboardScreen(
                                    state = state,
                                    strings = strings,
                                    onToggleConnection = initiateVpnConnect,
                                    onRefreshIp = { viewModel.refreshPublicIp() },
                                    onSwitchGatewayClick = { navigateToTab(NavRoutes.GATEWAYS) },
                                    onAutoFindBdRelay = {
                                        viewModel.autoFindAndSelectBdRelay(strings.noLiveBdRelayFound) {
                                            initiateVpnConnect()
                                        }
                                    },
                                    onEnableBkashSplitTunnel = {
                                        viewModel.enableBkashSplitTunnel()
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(strings.bkashAutoSetupToast)
                                        }
                                    },
                                    onSetTunnelMode = { mode -> viewModel.setTunnelMode(mode) }
                                )
                            }
                            composable(NavRoutes.GATEWAYS) {
                                GatewaysScreen(
                                    state = state,
                                    strings = strings,
                                    onSelectGateway = { gw ->
                                        viewModel.selectGateway(gw)
                                        navigateToTab(NavRoutes.TUNNEL)
                                    },
                                    onTestLatency = { gw -> viewModel.testGatewayLatency(gw) },
                                    onVerifyExitIp = { gw -> viewModel.verifyGatewayExitIp(gw) },
                                    onPingAll = { viewModel.pingAllMyGateways() },
                                    onDeleteGateway = { id -> viewModel.deleteGateway(id) },
                                    onFetchCommunityRelays = { viewModel.fetchCommunityBdRelays() },
                                    onSaveGateway = { name, city, host, port, proto, user, pass ->
                                        viewModel.saveCustomGateway(name, city, host, port, proto, user, pass)
                                    },
                                    onParseUri = { raw -> viewModel.parseProxyUri(raw) }
                                )
                            }
                            composable(NavRoutes.APPS) {
                                AppsAndSitesScreen(
                                    state = state,
                                    strings = strings,
                                    onSetSplitMode = { mode -> viewModel.setSplitTunnelMode(mode) },
                                    onToggleApp = { pkg -> viewModel.toggleAllowedApp(pkg) },
                                    onCheckPortal = { id -> viewModel.checkBdPortal(id) },
                                    onCheckAllPortals = { viewModel.checkAllBdPortals() }
                                )
                            }
                            composable(NavRoutes.SECURITY) {
                                SecuritySettingsScreen(
                                    state = state,
                                    strings = strings,
                                    onSetThemeMode = { mode -> viewModel.setThemeMode(mode) },
                                    onSetLanguage = { lang -> viewModel.setLanguage(lang) },
                                    onSetSecureDnsEnabled = { enabled -> viewModel.setSecureDnsEnabled(enabled) },
                                    onSetDnsProvider = { dns -> viewModel.setDnsProvider(dns) },
                                    onVerifySecureDns = { viewModel.verifySecureDns() },
                                    onSetKillSwitch = { enabled -> viewModel.setKillSwitch(enabled) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
