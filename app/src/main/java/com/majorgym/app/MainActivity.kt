package com.majorgym.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.majorgym.app.data.MembershipCleanupWorker
import com.majorgym.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MembersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Feature 4: schedules (or confirms already-scheduled) the daily
        // long-expired-account cleanup check. Cheap/safe to call on every
        // launch — WorkManager's KEEP policy no-ops if it's already scheduled,
        // so this never creates duplicate jobs or resets the run cadence.
        MembershipCleanupWorker.schedule(applicationContext)
        setContent {
            MajorGymTheme {
                var showSplash by remember { mutableStateOf(true) }
                var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val members by vm.members.collectAsState()

                // Android 13+ requires this permission for the kiosk service's
                // notification to actually display. Harmless to request even if the
                // scanner turns out not to be connected yet — asked once, like any
                // other runtime permission.
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                // Kiosk mode: as soon as the app is past the splash screen, this asks
                // the background FingerprintKioskService to start listening — but only
                // once a scanner is actually detected (service handles that check), and
                // only for as long as the app process is alive (Home button doesn't stop
                // it; swiping the app out of Recent Apps does — see the service for why).
                // Paused for the entire add-member-through-enrollment flow, not just
                // while EnrollFingerprint itself is on screen: the background loop can
                // still grab the USB device the instant Add is opened, well before the
                // user ever reaches the fingerprint step, causing the same "scanner
                // unavailable" race. So this stays paused from Add through Registered
                // through EnrollFingerprint, and only resumes once the user lands
                // somewhere outside that flow (Dashboard, Profile, Members, etc.).
                val kioskPaused = when (screen) {
                    Screen.Add, is Screen.Edit, is Screen.Registered, is Screen.EnrollFingerprint -> true
                    else -> false
                }
                val kioskState by rememberKioskCoordinator(
                    context = this@MainActivity,
                    members = members,
                    paused = kioskPaused
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
                            Screen.TotalMembers -> FilteredMembersScreen(
                                "Total Members", members, showSearch = true, emptyText = "No members yet."
                            ) { screen = it }
                            Screen.ActiveMembers -> FilteredMembersScreen(
                                "Active Members",
                                members.filter { com.majorgym.app.data.statusOf(it.expiryMillis) == com.majorgym.app.data.MemberStatus.ACTIVE },
                                showSearch = false, emptyText = "No active members."
                            ) { screen = it }
                            Screen.ExpiringMembers -> FilteredMembersScreen(
                                "Expiring Soon",
                                members.filter { com.majorgym.app.data.statusOf(it.expiryMillis) == com.majorgym.app.data.MemberStatus.EXPIRING },
                                showSearch = false, emptyText = "No members expiring soon."
                            ) { screen = it }
                            Screen.ExpiredMembers -> FilteredMembersScreen(
                                "Expired Members",
                                members.filter { com.majorgym.app.data.statusOf(it.expiryMillis) == com.majorgym.app.data.MemberStatus.EXPIRED },
                                showSearch = false, emptyText = "No expired members."
                            ) { screen = it }
                            is Screen.EnrollFingerprint -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) EnrollFingerprintScreen(m, vm, s.returnTo) { screen = it }
                            }
                        }
                        BottomNav(screen, modifier = Modifier.align(Alignment.BottomCenter)) { screen = it }

                        // Drawn last so it sits above the dashboard/bottom nav/every screen.
                        // Unchanged UI — same KioskResultOverlay as before; it now just
                        // reflects results published by the background service instead of
                        // an Activity-owned scan loop, so it works the same whether this
                        // screen was already visible or was just brought to the front.
                        KioskResultOverlay(kioskState.first, kioskState.second)
                    }
                }
                }
            }
        }
    }
}
