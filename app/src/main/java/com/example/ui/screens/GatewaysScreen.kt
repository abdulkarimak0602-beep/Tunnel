package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.GatewayProfile
import com.example.model.TunnelProtocol
import com.example.ui.UiStrings
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.viewmodel.DeshTunnelUiState
import com.example.viewmodel.ParsedProxyUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewaysScreen(
    state: DeshTunnelUiState,
    strings: UiStrings,
    onSelectGateway: (GatewayProfile) -> Unit,
    onTestLatency: (GatewayProfile) -> Unit,
    onVerifyExitIp: (GatewayProfile) -> Unit,
    onPingAll: () -> Unit,
    onDeleteGateway: (String) -> Unit,
    onFetchCommunityRelays: () -> Unit,
    onSaveGateway: (name: String, city: String, host: String, port: Int, protocol: TunnelProtocol, user: String, pass: String) -> Unit,
    onParseUri: (String) -> ParsedProxyUri?,
    modifier: Modifier = Modifier
) {
    var selectedSegment by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Segmented Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedSegment == 0,
                    onClick = { selectedSegment = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("segment_my_gateways")
                ) {
                    Text(strings.myGatewaysTab)
                }
                SegmentedButton(
                    selected = selectedSegment == 1,
                    onClick = { selectedSegment = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("segment_public_relays")
                ) {
                    Text(strings.publicDirectoryTab)
                }
            }

            if (selectedSegment == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showAddSheet = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_gateway_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.addGateway, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onPingAll,
                        modifier = Modifier.testTag("ping_all_button")
                    ) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.pingAll)
                    }
                }
            } else {
                Button(
                    onClick = onFetchCommunityRelays,
                    enabled = !state.isFetchingRelays,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fetch_bd_relays_button")
                ) {
                    if (state.isFetchingRelays) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.fetchingRelays)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.fetchBdRelays)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedSegment == 0) {
                // Built-in DNS Shield
                item {
                    GatewayCard(
                        gateway = state.builtInShield,
                        isSelected = state.activeSelectedGateway.id == state.builtInShield.id,
                        isVerifyingExit = state.verifyingExitGatewayId == state.builtInShield.id,
                        verifiedExit = state.verifiedExitByGatewayId[state.builtInShield.id],
                        strings = strings,
                        onSelect = { onSelectGateway(state.builtInShield) },
                        onTest = { onTestLatency(state.builtInShield) },
                        onVerifyExit = null,
                        onDelete = null
                    )
                }

                // bKash from Bahrain IP & Proxy Spec Banner
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = strings.bkashBahrainReqTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "${strings.bkashReqIpLabel} ${strings.bkashReqIpValue}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${strings.bkashReqProxyLabel} ${strings.bkashReqProxyValue}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (state.customGateways.isEmpty()) {
                    item {
                        EmptyHintCard(
                            icon = Icons.Outlined.Shield,
                            text = strings.emptyCustomGateways
                        )
                    }
                } else {
                    items(state.customGateways, key = { it.id }) { gw ->
                        GatewayCard(
                            gateway = gw,
                            isSelected = state.activeSelectedGateway.id == gw.id,
                            isVerifyingExit = state.verifyingExitGatewayId == gw.id,
                            verifiedExit = state.verifiedExitByGatewayId[gw.id],
                            strings = strings,
                            onSelect = { onSelectGateway(gw) },
                            onTest = { onTestLatency(gw) },
                            onVerifyExit = { onVerifyExitIp(gw) },
                            onDelete = { onDeleteGateway(gw.id) }
                        )
                    }
                }
            } else {
                // Community Security Warning
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = strings.communityWarningTitle,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = strings.communityWarningBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (!state.relaysFetchError.isNullOrBlank()) {
                    item {
                        Text(
                            text = state.relaysFetchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (state.communityRelays.isEmpty() && !state.isFetchingRelays) {
                    item {
                        EmptyHintCard(
                            icon = Icons.Outlined.Public,
                            text = strings.emptyPublicRelays
                        )
                    }
                } else {
                    items(state.communityRelays, key = { it.id }) { relay ->
                        GatewayCard(
                            gateway = relay,
                            isSelected = state.activeSelectedGateway.id == relay.id,
                            isVerifyingExit = state.verifyingExitGatewayId == relay.id,
                            verifiedExit = state.verifiedExitByGatewayId[relay.id],
                            strings = strings,
                            onSelect = { onSelectGateway(relay) },
                            onTest = { onTestLatency(relay) },
                            onVerifyExit = { onVerifyExitIp(relay) },
                            onDelete = null
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState
        ) {
            AddGatewayForm(
                strings = strings,
                onParseUri = onParseUri,
                onDismiss = { showAddSheet = false },
                onSave = { name, city, host, port, proto, user, pass ->
                    onSaveGateway(name, city, host, port, proto, user, pass)
                    showAddSheet = false
                }
            )
        }
    }
}

@Composable
private fun GatewayCard(
    gateway: GatewayProfile,
    isSelected: Boolean,
    isVerifyingExit: Boolean,
    verifiedExit: com.example.model.PublicIpInfo?,
    strings: UiStrings,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onVerifyExit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val border = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = border
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = gateway.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = strings.selectedGateway,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "${gateway.city} • ${gateway.host}:${gateway.port}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Latency Pill
                val latency = gateway.latencyMs
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        gateway.isCheckingLatency -> MaterialTheme.colorScheme.surfaceContainerHighest
                        latency != null && latency < 150 -> MaterialTheme.colorScheme.primaryContainer
                        latency != null -> MaterialTheme.colorScheme.tertiaryContainer
                        gateway.lastCheckedAt != null -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    if (gateway.isCheckingLatency) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = when {
                                latency != null -> strings.reachableMs(latency)
                                gateway.lastCheckedAt != null -> strings.unreachable
                                else -> strings.notTested
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = when {
                                latency != null && latency < 150 -> MaterialTheme.colorScheme.onPrimaryContainer
                                latency != null -> MaterialTheme.colorScheme.onTertiaryContainer
                                gateway.lastCheckedAt != null -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = gateway.protocol.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            gateway.isBuiltInShield -> MaterialTheme.colorScheme.tertiaryContainer
                            gateway.isCommunityRelay -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Text(
                            text = when {
                                gateway.isBuiltInShield -> strings.builtInShieldBadge
                                gateway.isCommunityRelay -> strings.communityRelayBadge
                                else -> strings.privateRecommendedBadge
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onVerifyExit != null) {
                        TextButton(onClick = onVerifyExit, enabled = !isVerifyingExit) {
                            Text(if (isVerifyingExit) strings.verifyingExitIp else strings.verifyExitIpButton)
                        }
                    }
                    TextButton(onClick = onTest, enabled = !gateway.isCheckingLatency) {
                        Text(strings.testLatency)
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = strings.deleteGateway,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            if (verifiedExit != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val asnSuffix = if (verifiedExit.asn.isNotBlank()) " • ${verifiedExit.asn}" else ""
                    Text(
                        text = "${verifiedExit.ip} • ${verifiedExit.countryCode} (${verifiedExit.isp.ifBlank { verifiedExit.countryName }}$asnSuffix)",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = if (verifiedExit.isBangladesh) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                    if (verifiedExit.isBangladesh) {
                        Text(
                            text = if (verifiedExit.isLikelyResidentialIsp) {
                                strings.residentialIspBadge
                            } else {
                                strings.datacenterIspWarning
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (verifiedExit.isLikelyResidentialIsp) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHintCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddGatewayForm(
    strings: UiStrings,
    onParseUri: (String) -> ParsedProxyUri?,
    onDismiss: () -> Unit,
    onSave: (name: String, city: String, host: String, port: Int, protocol: TunnelProtocol, user: String, pass: String) -> Unit
) {
    var uriInput by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("Dhaka") }
    var host by remember { mutableStateOf("") }
    var portStr by remember { mutableStateOf("443") }
    var protocol by remember { mutableStateOf(TunnelProtocol.SOCKS5_TLS) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.addGatewayTitle,
            style = MaterialTheme.typography.headlineSmall
        )

        val clipboard = LocalClipboardManager.current

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = false,
                onClick = {
                    name = "bKash Amber IT (BD)"
                    city = "Dhaka (Amber IT)"
                    portStr = "1080"
                    protocol = TunnelProtocol.SOCKS5
                },
                label = { Text(strings.amberPresetChip) }
            )
            FilterChip(
                selected = false,
                onClick = {
                    name = "bKash TLS 1.3 (BD)"
                    city = "Dhaka (TLS 1.3)"
                    portStr = "443"
                    protocol = TunnelProtocol.SOCKS5_TLS
                },
                label = { Text(strings.vpsPresetChip) }
            )
            FilterChip(
                selected = false,
                onClick = {
                    name = "BD HTTPS Proxy"
                    city = "Dhaka"
                    portStr = "443"
                    protocol = TunnelProtocol.HTTPS_TLS
                },
                label = { Text(strings.httpsPresetChip) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uriInput,
                onValueChange = { uriInput = it },
                label = { Text(strings.quickImportUriLabel) },
                placeholder = { Text("ip:port:user:pass") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val clipText = clipboard.getText()?.text?.trim().orEmpty()
                    if (clipText.isNotEmpty()) {
                        uriInput = clipText
                        val parsed = onParseUri(clipText)
                        if (parsed != null) {
                            host = parsed.host
                            portStr = parsed.port.toString()
                            protocol = parsed.protocol
                            username = parsed.username
                            password = parsed.password
                            if (name.isBlank()) name = "bKash BD (${parsed.host})"
                            errorText = null
                        }
                    }
                }
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = strings.pasteFromClipboard)
            }
            OutlinedButton(
                onClick = {
                    val parsed = onParseUri(uriInput)
                    if (parsed != null) {
                        host = parsed.host
                        portStr = parsed.port.toString()
                        protocol = parsed.protocol
                        username = parsed.username
                        password = parsed.password
                        if (name.isBlank()) name = "bKash BD (${parsed.host})"
                        errorText = null
                    } else {
                        errorText = strings.invalidGatewayInput
                    }
                }
            ) {
                Text(strings.parseUriButton)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(strings.gatewayNameLabel) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gateway_name_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; errorText = null },
                label = { Text(strings.gatewayHostLabel) },
                singleLine = true,
                modifier = Modifier
                    .weight(2f)
                    .testTag("gateway_host_input")
            )
            OutlinedTextField(
                value = portStr,
                onValueChange = { portStr = it.filter { ch -> ch.isDigit() }; errorText = null },
                label = { Text(strings.gatewayPortLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("gateway_port_input")
            )
        }

        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text(strings.gatewayCityLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = strings.gatewayProtocolLabel,
            style = MaterialTheme.typography.titleSmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(TunnelProtocol.SOCKS5_TLS, TunnelProtocol.HTTPS_TLS, TunnelProtocol.SOCKS5).forEach { proto ->
                FilterChip(
                    selected = protocol == proto,
                    onClick = { protocol = proto },
                    label = { Text(proto.displayName) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(strings.gatewayUsernameLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(strings.gatewayPasswordLabel) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        if (errorText != null) {
            Text(
                text = errorText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val port = portStr.toIntOrNull()
                    if (host.isBlank() || port == null || port !in 1..65535) {
                        errorText = strings.invalidGatewayInput
                    } else {
                        onSave(name, city, host, port, protocol, username, password)
                    }
                },
                modifier = Modifier.testTag("save_gateway_button")
            ) {
                Text(strings.saveGateway)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
