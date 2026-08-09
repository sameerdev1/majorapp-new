package com.majorgym.app.ui

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

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(12.dp))
        Text("MEMBER REGISTERED", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp)
        Text(member.name, color = GymColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
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
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { WhatsAppShare.share(context, member, passkey) },
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Share Welcome Message", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onNavigate(Screen.EnrollFingerprint(member.id, returnTo = Screen.Profile(member.id))) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = GymColors.Accent)
            Spacer(Modifier.width(8.dp))
            Text("Enroll Fingerprint Now", color = GymColors.Text, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onNavigate(Screen.Profile(member.id)) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Done", color = GymColors.TextMuted, fontWeight = FontWeight.Medium)
        }
    }
}

