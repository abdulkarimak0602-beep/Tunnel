package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ConnectionState
import com.example.ui.UiStrings
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.viewmodel.DeshTunnelUiState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TunnelDashboardScreen(
    state: DeshTunnelUiState,
    strings: UiStrings,
    onToggleConnection: () -> Unit,
    onRefreshIp: () -> Unit,
    onSwitchGatewayClick: () -> Unit,
    onAutoFindBdRelay: () -> Unit,
    onEnableBkashSplitTunnel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.connectionState, state.telemetry.connectedSinceMs) {
        while (state.connectionState == ConnectionState.CONNECTED && state.telemetry.connectedSinceMs > 0L) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Connection Banner & Power Shield Orb
        item {
            HeroConnectionCard(
                state = state,
                strings = strings,
                nowMs = nowMs,
                onToggleConnection = onToggleConnection,
                onSwitchGatewayClick = onSwitchGatewayClick
            )
        }

        // 2. Live Public IP & Location Verification Card
        item {
            PublicIpVerificationCard(
                state = state,
                strings = strings,
                onRefreshIp = onRefreshIp
            )
        }

        // 3. Why Foreign IP + One-Tap Live BD IP Finder
        if (state.publicIpInfo?.isBangladesh != true || state.activeSelectedGateway.isBuiltInShield) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.foreignIpExplainTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strings.foreignIpExplainBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!state.autoFindMessage.isNullOrBlank()) {
                            Text(
                                text = state.autoFindMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onAutoFindBdRelay,
                                enabled = !state.isAutoFindingBdRelay,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auto_find_bd_button")
                            ) {
                                if (state.isAutoFindingBdRelay) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.searchingBdRelay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.autoFindBdRelay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            OutlinedButton(
                                onClick = onSwitchGatewayClick,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(strings.addGateway, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // 4. bKash & Amber IT Abroad (Bahrain) Readiness Card
        item {
            BkashAndAmberReadinessCard(
                state = state,
                strings = strings,
                onEnableBkashSplitTunnel = onEnableBkashSplitTunnel,
                onSwitchGatewayClick = onSwitchGatewayClick,
                onOpenAmberPortal = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://myswift.amberit.com.bd"))
                        )
                    }
                }
            )
        }

        // 4. Live Telemetry Grid (Speed, Data, DNS Queries, Cipher)
        item {
            TelemetryGridSection(state = state, strings = strings)
        }

        // 5. Expandable Live Security Logs
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogs = !showLogs }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = strings.liveTunnelLogs,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Icon(
                            imageVector = if (showLogs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = strings.liveTunnelLogs
                        )
                    }
                    AnimatedVisibility(visible = showLogs) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (state.tunnelLogs.isEmpty()) {
                                Text(
                                    text = strings.noLogsYet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.tunnelLogs.take(15).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun HeroConnectionCard(
    state: DeshTunnelUiState,
    strings: UiStrings,
    nowMs: Long,
    onToggleConnection: () -> Unit,
    onSwitchGatewayClick: () -> Unit
) {
    val isConnected = state.connectionState == ConnectionState.CONNECTED
    val isConnecting = state.connectionState == ConnectionState.CONNECTING
    val isError = state.connectionState == ConnectionState.ERROR

    val statusColor by animateColorAsState(
        targetValue = when (state.connectionState) {
            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
            ConnectionState.ERROR -> MaterialTheme.colorScheme.secondary
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
        },
        label = "statusColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected || isConnecting) 1.14f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnecting) 900 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_dhaka_network_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(if (isConnected) 0.28f else 0.14f)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = when (state.connectionState) {
                                ConnectionState.CONNECTED -> strings.statusConnected
                                ConnectionState.CONNECTING -> strings.statusConnecting
                                ConnectionState.ERROR -> strings.statusError
                                ConnectionState.DISCONNECTED -> strings.statusDisconnected
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Interactive Power Shield Orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(156.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = if (isConnected) 0.18f else 0.08f))
                            .border(
                                width = 2.dp,
                                color = statusColor.copy(alpha = if (isConnected) 0.55f else 0.25f),
                                shape = CircleShape
                            )
                    )
                    Surface(
                        onClick = onToggleConnection,
                        shape = CircleShape,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        shadowElevation = if (isConnected) 10.dp else 4.dp,
                        modifier = Modifier
                            .size(116.dp)
                            .testTag("connect_toggle_button")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.PowerSettingsNew,
                                    contentDescription = if (isConnected) strings.tapToDisconnect else strings.tapToConnect,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when (state.connectionState) {
                                    ConnectionState.CONNECTED -> strings.tapToDisconnect
                                    ConnectionState.CONNECTING -> strings.connectingPrompt
                                    else -> strings.tapToConnect
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Session timer or error message
                Spacer(modifier = Modifier.height(14.dp))
                if (isConnected && state.telemetry.connectedSinceMs > 0L) {
                    val elapsedSec = ((nowMs - state.telemetry.connectedSinceMs) / 1000L).coerceAtLeast(0L)
                    Text(
                        text = "${strings.sessionDuration}: ${formatDuration(elapsedSec)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isError && !state.vpnErrorMessage.isNullOrBlank()) {
                    Text(
                        text = state.vpnErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Active Gateway Selector Bar
                Surface(
                    onClick = onSwitchGatewayClick,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("switch_gateway_chip")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = strings.activeGatewayLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${state.activeSelectedGateway.name} • ${state.activeSelectedGateway.city}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = state.activeSelectedGateway.protocol.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = strings.changeGateway,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = strings.changeGateway,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicIpVerificationCard(
    state: DeshTunnelUiState,
    strings: UiStrings,
    onRefreshIp: () -> Unit
) {
    val ipInfo = state.publicIpInfo
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (state.isCheckingIp) {
                            strings.checkingIp
                        } else if (ipInfo != null) {
                            if (ipInfo.isBangladesh) strings.bdIpVerifiedBadge else strings.outsideBdBadge
                        } else {
                            strings.ipUnknownError
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            ipInfo?.isBangladesh == true -> MaterialTheme.colorScheme.primary
                            ipInfo != null -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(
                    onClick = onRefreshIp,
                    enabled = !state.isCheckingIp,
                    modifier = Modifier.testTag("refresh_ip_button")
                ) {
                    if (state.isCheckingIp) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = strings.refreshIp
                        )
                    }
                }
            }

            if (ipInfo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ipInfo.ip,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val locationText = listOf(ipInfo.city, ipInfo.countryName)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        Text(
                            text = "$locationText (${ipInfo.countryCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (ipInfo.isp.isNotBlank()) {
                            Text(
                                text = listOf(ipInfo.isp, ipInfo.asn).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (ipInfo.isBangladesh) {
                            Text(
                                text = if (ipInfo.isLikelyResidentialIsp) {
                                    strings.residentialIspBadge
                                } else {
                                    strings.datacenterIspWarning
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ipInfo.isLikelyResidentialIsp) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            )
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (ipInfo.isBangladesh) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (ipInfo.isBangladesh) {
                                    Icons.Rounded.VerifiedUser
                                } else {
                                    Icons.Rounded.WarningAmber
                                },
                                contentDescription = null,
                                tint = if (ipInfo.isBangladesh) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${ipInfo.countryCode.ifBlank { "--" }} • ${ipInfo.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ipInfo.isBangladesh) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }
            } else if (!state.isCheckingIp) {
                FilledTonalButton(onClick = onRefreshIp) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.refreshIp)
                }
            }
        }
    }
}

@Composable
private fun TelemetryGridSection(
    state: DeshTunnelUiState,
    strings: UiStrings
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                label = strings.downloadSpeed,
                value = formatRate(state.telemetry.rxBytesPerSec),
                icon = Icons.Rounded.ArrowDownward,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                label = strings.uploadSpeed,
                value = formatRate(state.telemetry.txBytesPerSec),
                icon = Icons.Rounded.ArrowUpward,
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                label = strings.encryptedData,
                value = formatBytes(state.telemetry.rxBytes + state.telemetry.txBytes),
                icon = Icons.Rounded.DataUsage,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                label = strings.dnsProtected,
                value = "${state.telemetry.dnsQueriesSecured}",
                icon = Icons.Outlined.Dns,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        // Encryption Suite & Secure DNS Badge Card
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = strings.encryptionCipherLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (state.connectionState == ConnectionState.CONNECTED) {
                            state.telemetry.activeCipherSuite
                        } else {
                            state.activeSelectedGateway.protocol.encryptionLabel
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val dotEnabled = if (state.connectionState == ConnectionState.CONNECTED) {
                        state.telemetry.secureDnsActive
                    } else {
                        state.preferences.secureDnsEnabled || state.activeSelectedGateway.isBuiltInShield
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = if (dotEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "DNS",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (dotEnabled) {
                            "${strings.dotActiveBadge} • ${state.preferences.dnsProvider.tlsHost}"
                        } else {
                            strings.dotDisabledBadge
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dotEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private fun formatRate(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun BkashAndAmberReadinessCard(
    state: DeshTunnelUiState,
    strings: UiStrings,
    onEnableBkashSplitTunnel: () -> Unit,
    onSwitchGatewayClick: () -> Unit,
    onOpenAmberPortal: () -> Unit
) {
    val isBd = state.publicIpInfo?.isBangladesh == true
    val hasBdProxyGateway = !state.activeSelectedGateway.isBuiltInShield
    val hasAntiLeakReady = state.preferences.secureDnsEnabled &&
        state.preferences.killSwitchEnabled &&
        state.preferences.allowedPackages.contains("com.bKash.customerapp")
    var showRequirements by remember { mutableStateOf(true) }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRequirements = !showRequirements },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.bkashAmberTitle,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = strings.bkashAmberSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (showRequirements) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = strings.bkashBahrainReqTitle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Live Bahrain -> bKash checklist badges
            ChecklistStatusRow(
                ok = isBd,
                text = if (isBd) strings.bdIpVerifiedBadge else strings.bdIpRequiredWarn
            )
            ChecklistStatusRow(
                ok = hasBdProxyGateway,
                text = if (hasBdProxyGateway) {
                    "${state.activeSelectedGateway.name} (${state.activeSelectedGateway.protocol.displayName})"
                } else {
                    "${strings.bkashReqProxyLabel} ${strings.bkashReqProxyValue}"
                }
            )
            ChecklistStatusRow(
                ok = hasAntiLeakReady,
                text = "${strings.bkashReqSecurityLabel} ${strings.bkashReqSecurityValue}"
            )

            AnimatedVisibility(visible = showRequirements) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = strings.bkashBahrainReqTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${strings.bkashReqIpLabel} ${strings.bkashReqIpValue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.bkashReqDeviceTips,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = if (state.isBkashInstalled) strings.bkashAppDetected else strings.bkashAppNotInstalled,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onEnableBkashSplitTunnel,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bkash_auto_setup_button")
                ) {
                    Text(strings.bkashAutoSetupButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = if (hasBdProxyGateway) onOpenAmberPortal else onSwitchGatewayClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (hasBdProxyGateway) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (hasBdProxyGateway) strings.openAmberPortalButton else strings.addGateway,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistStatusRow(
    ok: Boolean,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
