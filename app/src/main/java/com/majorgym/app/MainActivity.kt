package com.majorgym.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
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
import com.majorgym.app.data.AttendanceRetentionWorker
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
        // Change 1: schedules the daily attendance-retention cleanup
        // (deletes attendance older than 4 months). Separate worker, own
        // unique work name — does not touch MembershipCleanupWorker's
        // timing/conditions at all.
        AttendanceRetentionWorker.schedule(applicationContext)
        setContent {
            MajorGymTheme {
                var showSplash by remember { mutableStateOf(true) }
                var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val members by vm.members.collectAsState()

                // Section 24: lightweight reduced-motion support — reads the
                // OS-level "Remove animations" developer/accessibility setting
                // (animator duration scale == 0) once at launch. No in-app
                // toggle is added; this only follows the system preference,
                // per "do not over-engineer this." Only Priority-3 decorative
                // motion (the fingerprint waiting pulse, the kiosk idle
                // indicator) checks this — functional feedback (screen
                // transitions, button press states, status colors) stays on
                // either way, per spec section 24's "core functional feedback
                // should remain understandable."
                val reducedMotion = remember {
                    android.provider.Settings.Global.getFloat(
                        contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                    ) == 0f
                }

                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {

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

                        // Priority 1: purely visual fade + slight horizontal-movement
                        // transition between screens. This is layered on top of the
                        // existing manually-managed `screen` state / `when` block —
                        // no Navigation Compose, no change to what each branch does or
                        // when it does it. AnimatedContent swaps content as soon as
                        // `screen` changes; nothing here delays ViewModel/DB/hardware
                        // work, which all still runs the instant its composable enters
                        // composition (see EnrollFingerprintScreen's own DisposableEffect
                        // for the fingerprint-specific case).
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(GymMotion.Standard, easing = GymMotion.StandardEasing)) +
                                    slideInHorizontally(animationSpec = tween(GymMotion.Standard, easing = GymMotion.StandardEasing)) { w -> w / 16 })
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(GymMotion.Fast, easing = GymMotion.StandardEasing)) +
                                            slideOutHorizontally(animationSpec = tween(GymMotion.Fast, easing = GymMotion.StandardEasing)) { w -> -w / 16 }
                                    )
                            },
                            label = "screenTransition"
                        ) { targetScreen ->
                            when (val s = targetScreen) {
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
                                Screen.Backup -> BackupScreen(vm) { screen = it }
                                Screen.BackupHistory -> BackupHistoryScreen(vm) { screen = it }
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
                                Screen.Attendance -> AttendanceScreen { screen = it }
                                Screen.AttendanceLogs -> AttendanceLogsScreen(members, vm) { screen = it }
                                is Screen.AttendanceHistory -> AttendanceHistoryScreen(s.memberId, members, vm) { screen = it }
                                is Screen.EnrollFingerprint -> {
                                    val m = members.find { it.id == s.id }
                                    if (m != null) EnrollFingerprintScreen(m, vm, s.returnTo) { screen = it }
                                }
                            }
                        }
                        BottomNav(screen, modifier = Modifier.align(Alignment.BottomCenter)) { screen = it }

                        // Drawn last so it sits above the dashboard/bottom nav/every screen.
                        // Unchanged UI — same KioskResultOverlay as before; it now just
                        // reflects results published by the background service instead of
                        // an Activity-owned scan loop, so it works the same whether this
                        // screen was already visible or was just brought to the front.
                        KioskResultOverlay(kioskState.first, kioskState.second)

                        // Priority 3 (optional): a very faint accent indicator that the
                        // kiosk is listening while idle. Purely decorative — respects
                        // reduced motion, and renders nothing while a result is showing.
                        KioskIdleIndicator(
                            visible = kioskState.first == KioskPhase.IDLE,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
                }
                }
            }
        }
    }
}
