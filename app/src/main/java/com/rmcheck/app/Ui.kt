package com.rmcheck.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------
@Composable
fun MainScreen(vm: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { InputCard(vm, clipboard) }
            item { PingRow(vm) }
            item { ResultsHeader(vm) }
            if (vm.hosts.isEmpty()) {
                item { EmptyHint(vm) }
            } else {
                items(vm.hosts, key = { it.ip }) { host ->
                    HostCard(
                        host,
                        onOpen = { url -> openUrl(context, url, vm) },
                        onCopy = { url ->
                            clipboard.setText(AnnotatedString(url))
                            vm.setStatus("Copied $url", StatusKind.Ok)
                        }
                    )
                }
            }
        }
        BottomBar(vm)
    }
}

private fun openUrl(context: Context, url: String, vm: ScanViewModel) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        vm.setStatus("Opened $url", StatusKind.Ok)
    } catch (e: Exception) {
        vm.setStatus("No app found to open $url", StatusKind.Error)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = BorderStrong,
    focusedContainerColor = CardColor,
    unfocusedContainerColor = CardColor,
    cursorColor = Accent,
    focusedTextColor = TextColor,
    unfocusedTextColor = TextColor
)

// ---------------------------------------------------------------------------
// Input card: IP / range, ports, Scan button
// ---------------------------------------------------------------------------
@Composable
fun InputCard(vm: ScanViewModel, clipboard: ClipboardManager) {
    val focus = LocalFocusManager.current

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("IP address or range", color = SubText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                IP_EXAMPLES,
                color = MutedColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            OutlinedTextField(
                value = vm.ipText,
                onValueChange = { vm.ipText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = TextColor),
                placeholder = {
                    Text("Enter / Paste IP Address or Range", color = PlaceholderColor, fontSize = 15.sp)
                },
                trailingIcon = {
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrBlank()) {
                            vm.ipText = pasted.trim().replace(Regex("\\s+"), " ")
                        }
                    }) {
                        Text("Paste", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focus.clearFocus()
                    vm.start()
                }),
                shape = RoundedCornerShape(6.dp),
                colors = fieldColors()
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = vm.portsText,
                    onValueChange = { vm.portsText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Ports") },
                    textStyle = TextStyle(fontSize = 14.sp, color = SubText),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    shape = RoundedCornerShape(6.dp),
                    colors = fieldColors()
                )
                TextButton(onClick = { vm.resetPorts() }) {
                    Text("Reset", color = Accent)
                }
            }

            val buttonLabel = when {
                vm.stopping -> "Stopping..."
                vm.scanning -> "Stop"
                else -> "Scan"
            }
            Button(
                onClick = {
                    focus.clearFocus()
                    vm.toggle()
                },
                enabled = !vm.stopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.scanning) RedColor else Accent,
                    contentColor = Color.White,
                    disabledContainerColor = DisabledBg,
                    disabledContentColor = MutedColor
                )
            ) {
                Text(buttonLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ping badge (single IP) / host summary badge (range)
// ---------------------------------------------------------------------------
private fun badgeFor(vm: ScanViewModel): Triple<String, ChipStyle, String> {
    if (vm.total > 1) {
        val style = if (vm.respondingHosts > 0) OkStyle else if (vm.finished) BadStyle else WarnStyle
        return Triple(
            "Hosts: ${vm.respondingHosts} of ${vm.total} responding",
            style,
            "A host counts as responding if it answers ping or has an open port."
        )
    }
    if (vm.total == 1) {
        return when (vm.pingState) {
            PingState.Pending ->
                if (vm.scanning) Triple("Ping: checking...", WarnStyle, "Sending ping...")
                else Triple("Ping: -", IdleStyle, "")
            PingState.Replying -> {
                val ms = vm.pingMs?.let { "  ${it.toInt()} ms" }.orEmpty()
                Triple("Ping: enabled$ms", OkStyle, "The host is replying to ping.")
            }
            PingState.NoReply ->
                Triple("Ping: no reply", BadStyle, "ICMP may be disabled or blocked. Ports can still be open.")
            PingState.NotAvailable ->
                Triple("Ping: not checked", IdleStyle, "Ping is not available on this device.")
        }
    }
    return Triple("Ping: -", IdleStyle, "")
}

@Composable
fun PingRow(vm: ScanViewModel) {
    val (text, style, note) = badgeFor(vm)
    Column {
        Chip(text, style.bg, style.fg)
        if (note.isNotEmpty()) {
            Text(note, color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------
@Composable
fun ResultsHeader(vm: ScanViewModel) {
    val withPorts = vm.hosts.count { it.ports.isNotEmpty() }
    val count = if (vm.total > 1) "${vm.openPorts} open port(s) on $withPorts host(s)" else "${vm.openPorts} found"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Results", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextColor)
        Spacer(Modifier.width(10.dp))
        Chip(count, Color(0xFFE5E5E5), SubText, bold = false)
    }
}

@Composable
fun EmptyHint(vm: ScanViewModel) {
    val text = when {
        vm.scanning -> "Scanning..."
        vm.finished && vm.total == 1 -> "No open ports found"
        vm.finished -> "No responding hosts found"
        else -> "Enter an IP address or range and press Scan"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MutedColor, fontSize = 14.sp)
    }
}

private fun schemeStyle(scheme: String): ChipStyle = when (scheme) {
    "https" -> OkStyle
    "http" -> WarnStyle
    else -> IdleStyle
}

private fun pingChip(h: HostResult): Pair<String, ChipStyle> = when (h.ping) {
    PingState.Replying -> {
        val ms = h.pingMs?.let { " ${it.toInt()} ms" }.orEmpty()
        Pair("Replying$ms", OkStyle)
    }
    PingState.NoReply -> Pair("No ping reply", IdleStyle)
    PingState.Pending -> Pair("Checking...", WarnStyle)
    PingState.NotAvailable -> Pair("Ping n/a", IdleStyle)
}

@Composable
fun HostCard(h: HostResult, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    h.ip,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColor,
                    modifier = Modifier.weight(1f)
                )
                val (label, style) = pingChip(h)
                Chip(label, style.bg, style.fg)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderColor)
            )
            if (h.ports.isEmpty()) {
                Text(
                    "No open management port found",
                    color = MutedColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                h.ports.forEach { r -> PortRow(h.ip, r, onOpen, onCopy) }
            }
        }
    }
}

@Composable
fun PortRow(ip: String, r: PortResult, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    val isWeb = r.scheme == "http" || r.scheme == "https"
    val url = if (isWeb) "${r.scheme}://${urlHost(ip)}:${r.port}" else ""
    val style = schemeStyle(r.scheme)

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (isWeb) Modifier.clickable { onOpen(url) } else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chip(r.scheme.uppercase(), style.bg, style.fg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Port ${r.port}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextColor)
            if (isWeb) {
                Text(url, fontSize = 12.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Not an HTTP/HTTPS service", fontSize = 12.sp, color = MutedColor)
            }
            if (r.info.isNotEmpty()) {
                Text(r.info, fontSize = 12.sp, color = MutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isWeb) {
            TextButton(onClick = { onCopy(url) }) {
                Text("Copy", color = Accent, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------
@Composable
fun Chip(text: String, bg: Color, fg: Color, bold: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** Thin activity bar: a blue segment glides back and forth while scanning, real progress fills behind it. */
@Composable
fun SweepBar(running: Boolean, fraction: Float) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val pos by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweepPos"
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        drawRect(TrackColor)
        if (fraction > 0f) {
            drawRect(AccentSoft, size = Size(size.width * fraction, size.height))
        }
        if (running) {
            val seg = maxOf(size.width * 0.22f, 40.dp.toPx())
            drawRect(Accent, topLeft = Offset((size.width - seg) * pos, 0f), size = Size(seg, size.height))
        }
    }
}

@Composable
fun BottomBar(vm: ScanViewModel) {
    val color = when (vm.statusKind) {
        StatusKind.Info -> SubText
        StatusKind.Ok -> GreenColor
        StatusKind.Warn -> AmberColor
        StatusKind.Error -> RedColor
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardColor)
            .navigationBarsPadding()
    ) {
        SweepBar(vm.scanning, vm.progress)
        Text(
            vm.statusText,
            color = color,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
