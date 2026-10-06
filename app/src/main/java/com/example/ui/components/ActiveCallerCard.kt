package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import com.example.model.CallHistoryItem
import com.example.ui.theme.Amber400
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun ActiveCallerCard(
    call: CallHistoryItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = call.customerContext

    val infiniteTransition = rememberInfiniteTransition(label = "pulseRing")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )

    Surface(
        color = Slate900,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, Amber400.copy(alpha = borderAlpha), RoundedCornerShape(22.dp))
            .testTag("active_caller_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header: Ringing Indicator + Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Amber400.copy(alpha = 0.2f))
                            .border(1.dp, Amber400, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneInTalk,
                            contentDescription = "Calling",
                            tint = Amber400,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "مكالمة واردة الآن",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Amber400
                        )
                        Text(
                            text = "تم إرسالها إلى شاشة الكاشير",
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = Slate400,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Phone Number (Prominent display)
            Text(
                text = call.phone,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Customer Profile Section
            Surface(
                color = Slate800,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Customer Name
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Cyan400,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "اسم العميل:",
                            fontSize = 13.sp,
                            color = Slate400
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context?.name?.ifBlank { "—" } ?: "—",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (!context?.name.isNullOrBlank()) Color.White else Slate400
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Area & Address
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Cyan400,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "المنطقة / العنوان:",
                            fontSize = 13.sp,
                            color = Slate400
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val locationText = when {
                            !context?.area.isNullOrBlank() && !context?.address.isNullOrBlank() ->
                                "${context.area} - ${context.address}"
                            !context?.area.isNullOrBlank() -> context.area
                            !context?.address.isNullOrBlank() -> context.address
                            else -> "—"
                        }
                        Text(
                            text = locationText ?: "—",
                            fontSize = 13.sp,
                            color = if (locationText != "—") Color.White else Slate400,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 21. LAST CALL in current POS session (Rule 21 strictly obeyed)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Amber400,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "آخر مكالمة في الجلسة:",
                            fontSize = 13.sp,
                            color = Slate400
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val lastCallText = when {
                            context?.minutesSinceLastCall != null ->
                                "منذ ${context.minutesSinceLastCall} دقيقة"
                            !context?.lastCall.isNullOrBlank() ->
                                context?.lastCall ?: ""
                            else -> "لا توجد مكالمة سابقة في الجلسة الحالية"
                        }
                        Text(
                            text = lastCallText,
                            fontSize = 13.sp,
                            color = if (context?.minutesSinceLastCall != null) Amber400 else Slate400,
                            fontWeight = if (context?.minutesSinceLastCall != null) FontWeight.Medium else FontWeight.Normal
                        )
                    }

                    // Order count / Recent Notes if available
                    if (context?.orderCount != null && context.orderCount > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = null,
                                tint = Emerald400,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "عدد الطلبات السابقة:",
                                fontSize = 13.sp,
                                color = Slate400
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${context.orderCount} طلب",
                                fontSize = 13.sp,
                                color = Emerald400,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Footer: Transmission Status to Taloola
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (call.isDispatchedToTaloola) Icons.Default.CheckCircle else Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (call.isDispatchedToTaloola) Emerald400 else Amber400,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (call.isDispatchedToTaloola) "تم الإرسال لـ Taloola" else "في انتظار الاتصال (محفوظ محلياً)",
                        fontSize = 12.sp,
                        color = if (call.isDispatchedToTaloola) Emerald400 else Amber400
                    )
                }

                Text(
                    text = "ID: ${call.callId.take(8)}",
                    fontSize = 11.sp,
                    color = Slate400
                )
            }
        }
    }
}
