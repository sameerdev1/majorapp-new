package com.majorgym.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import com.majorgym.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MembersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MajorGymTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val members by vm.members.collectAsState()

                Surface(modifier = Modifier.fillMaxSize(), color = GymColors.Bg) {
                    Box(Modifier.fillMaxSize()) {
                        when (val s = screen) {
                            Screen.Dashboard -> DashboardScreen(members) { screen = it }
                            Screen.Members -> MembersScreen(members) { screen = it }
                            Screen.Add -> AddEditMemberScreen(vm, null) { screen = it }
                            is Screen.Edit -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) AddEditMemberScreen(vm, m) { screen = it }
                            }
                            is Screen.Profile -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) ProfileScreen(m, vm) { screen = it }
                            }
                            is Screen.Renew -> {
                                val m = members.find { it.id == s.id }
                                if (m != null) RenewScreen(m, vm) { screen = it }
                            }
                            Screen.Backup -> BackupScreen(vm)
                        }
                        BottomNav(screen, modifier = Modifier.align(Alignment.BottomCenter)) { screen = it }
                    }
                }
            }
        }
    }
}
