package com.majorgym.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("MEMBER REGISTERED", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(member.name, color = GymColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Member QR code")
        }
        Spacer(Modifier.height(8.dp))
        Text("Member ID: ${member.id.take(8)}\u2026", color = GymColors.TextFaint, fontSize = 11.sp)
        Text(
            "QR valid until ${formatDateTime(member.qrTokenExpiryMillis)}",
            color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { WhatsAppShare.share(context, member, passkey) },
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share Welcome Message", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onNavigate(Screen.Profile(member.id)) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Done")
        }
    }
}
