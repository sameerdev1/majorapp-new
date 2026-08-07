package com.majorgym.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.majorgym.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MembersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MajorGymTheme {
                var showSplash by remember { mutableStateOf(true) }
                var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val members by vm.members.collectAsState()

                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                // Kiosk mode: connects to the USB fingerprint scanner as soon as the
                // app is past the splash screen, and keeps listening continuously for
                // the rest of the time the app is open — no button press required.
                // Paused only while EnrollFingerprint has its own exclusive scanner
                // session open (the USB device can only be held by one connection).
                val kioskPaused = screen is Screen.EnrollFingerprint
                val kioskState by rememberKioskState(
                    activity = this@MainActivity,
                    members = members,
                    paused = kioskPaused,
                    onResolved = { screen = Screen.Dashboard }
                )
                // Surface is transparent so the background image behind it shows through
                // on every screen; a dark scrim keeps text/cards readable over the photo.
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            painter = painterResource(R.drawable.bg_gym),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

                        when (val s = screen) {
                            Screen.Dashboard -> DashboardScreen(members) { screen = it }
                            Screen.Members -> MembersScreen(members) { screen = it }
                            Screen.Add -> AddEditMemberScreen(vm, null) { screen = it }
                            is Screen.Edit -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) AddEditMemberScreen(vm, m) { screen = it }
                            }
                            is Screen.Registered -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) RegistrationSuccessScreen(m, s.passkey) { screen = it }
                            }
                            is Screen.Profile -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) ProfileScreen(m, vm) { screen = it }
                            }
                            is Screen.Renew -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) RenewScreen(m, vm) { screen = it }
                            }
                            is Screen.Renewed -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) RenewalSuccessScreen(m, s.justRenewed) { screen = it }
                            }
                            Screen.Backup -> BackupScreen(vm)
                            Screen.Sync -> SyncScreen(vm)
                            is Screen.EnrollFingerprint -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) EnrollFingerprintScreen(m, vm, s.returnTo) { screen = it }
                            }
                        }
                        BottomNav(screen, modifier = Modifier.align(Alignment.BottomCenter)) { screen = it }

                        // Drawn last so it sits above the dashboard/bottom nav/every screen.
                        KioskResultOverlay(kioskState.first, kioskState.second)
                    }
                }
                }
            }
        }
    }
}
