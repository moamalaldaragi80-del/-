package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.model.TrustCredentials
import com.example.ui.theme.Amber400
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun HeaderBar(
    connectionState: ConnectionState,
    trustCredentials: TrustCredentials?,
    lastSyncTime: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Slate900,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Slate700, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top Row: App Brand & Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Cyan400.copy(alpha = 0.15f))
                            .border(1.5.dp, Cyan400, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "ALAMER",
                            tint = Cyan400,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "ALAMER Caller",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "مساعد بدالة Taloola POS",
                            fontSize = 12.sp,
                            color = Slate400
                        )
                    }
                }

                // Connection state badge
                StatusPill(connectionState = connectionState)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Info Row: Server Name, Server ID, Host/Port, Sync
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Slate800)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (connectionState == ConnectionState.READY) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = "Wi-Fi LAN",
                        tint = if (connectionState == ConnectionState.READY) Emerald400 else Slate400,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = trustCredentials?.let { "${it.serverName} (${it.host}:${it.port})" } ?: "غير مقترن",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = Slate400,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (lastSyncTime != "-") "آخر مزامنة: $lastSyncTime" else "بانتظار الربط",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }
        }
    }
}

@Composable
fun StatusPill(connectionState: ConnectionState) {
    val (bgColor, textColor, dotColor) = when (connectionState) {
        ConnectionState.READY -> Triple(Emerald400.copy(alpha = 0.15f), Emerald400, Emerald400)
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.PAIRING ->
            Triple(Amber400.copy(alpha = 0.15f), Amber400, Amber400)
        ConnectionState.NEEDS_PAIRING, ConnectionState.UNINITIALIZED ->
            Triple(Cyan400.copy(alpha = 0.15f), Cyan400, Cyan400)
        else -> Triple(Rose500.copy(alpha = 0.15f), Rose500, Rose500)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.testTag("status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (connectionState in listOf(ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.PAIRING))
                            dotColor.copy(alpha = alphaAnim)
                        else dotColor
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = connectionState.arabicLabel,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
