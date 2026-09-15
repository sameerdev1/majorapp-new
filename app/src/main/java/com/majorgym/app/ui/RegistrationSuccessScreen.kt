package com.majorgym.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.Screen
import com.majorgym.app.data.Member
import com.majorgym.app.data.QrUtils
import com.majorgym.app.data.WhatsAppShare
import com.majorgym.app.data.formatDateTime
import kotlinx.coroutines.delay

/**
 * Shown exactly once, right after a new member is saved (spec sections 2-3):
 * displays the member's QR (ID only, no personal data) and offers to share
 * a welcome message with their temporary passkey over WhatsApp.
 *
 * [passkey] is the plaintext value generated moments ago on the registration
 * screen. It is never re-derivable after this screen closes — only its hash
 * lives in the database from here on.
 */
@Composable
fun RegistrationSuccessScreen(member: Member, passkey: String, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val qrBitmap = remember(member.id, member.qrToken) { QrUtils.memberQrBitmap(member) }

    // Section 14: a short, cheap stagger — checkmark, then message, then QR,
    // then the action buttons. Entirely visual and entirely local state; the
    // member was already saved before this screen was ever navigated to, so
    // there's nothing functional to wait on here.
    var showCheck by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showCheck = true
        delay(80)
        showMessage = true
        delay(80)
        showQr = true
        delay(100)
        showActions = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedVisibility(
            visible = showCheck,
            enter = scaleIn(animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f)) + fadeIn(GymMotion.standardTween())
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(visible = showMessage, enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MEMBERSHIP ACTIVATED", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
                Text(member.name, color = GymColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(
            visible = showQr,
            enter = fadeIn(GymMotion.standardTween()) + scaleIn(initialScale = 0.92f, animationSpec = GymMotion.standardTween())
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(232.dp)
                        .neonGlow(GymColors.AccentBright, alpha = 0.30f, radius = 16.dp, shape = GymShapes.lg)
                        .clip(GymShapes.lg)
                        .background(GymColors.PrimaryGradient)
                        .padding(6.dp)
                        .clip(GymShapes.md)
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Member QR code")
                }
                Spacer(Modifier.height(10.dp))
                Text("Member ID: ${member.id.take(8)}\u2026", color = GymColors.TextFaint, fontSize = 11.sp)
                Text(
                    "QR valid until ${formatDateTime(member.qrTokenExpiryMillis)}",
                    color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(visible = showActions, enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween())) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(text = "Share Welcome Message", icon = Icons.Filled.Share, onClick = { WhatsAppShare.share(context, member, passkey) })
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = "Enroll Fingerprint Now",
                    icon = Icons.Filled.Fingerprint,
                    onClick = { onNavigate(Screen.EnrollFingerprint(member.id, returnTo = Screen.Profile(member.id))) }
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { onNavigate(Screen.Profile(member.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Done", color = GymColors.TextMuted, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

