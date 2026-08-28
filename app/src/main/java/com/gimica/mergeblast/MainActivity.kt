package com.gimica.mergeblast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gimica.mergeblast.service.GameAccessibilityService
import com.gimica.mergeblast.ui.theme.AutomaterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        checkOverlayPermission()
    }

    private val openAccessibilitySettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkAccessibilityService()
    }

    private fun openAccessibilityServiceSettings(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                try {
                    putExtra("package", packageName)
                } catch (e: Exception) {
                    // ignore
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutomaterTheme {
                MainScreen(
                    onEnableAccessibility = { openAccessibilitySettings.launch(openAccessibilityServiceSettings()) },
                    onEnableOverlay = { requestOverlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                    onToggleBot = { service?.toggleBot() },
                    isServiceEnabled = isAccessibilityServiceEnabled(),
                    hasOverlayPermission = checkOverlayPermission(),
                    botState = botState
                )
            }
        }

        lifecycleScope.launch {
            val filter = IntentFilter(GameAccessibilityService.ACTION_BOT_STATE_CHANGED)
            LocalBroadcastManager.getInstance(this@MainActivity).registerReceiver(
                botStateReceiver,
                filter
            )
        }
    }

    private var botState by mutableStateOf(BotState())
    private var service: GameAccessibilityService? = null

    private val botStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val running = it.getBooleanExtra(GameAccessibilityService.EXTRA_BOT_RUNNING, false)
                val board = it.getStringExtra(GameAccessibilityService.EXTRA_BOARD_STATE) ?: ""
                val decision = it.getStringExtra(GameAccessibilityService.EXTRA_DECISION) ?: ""
                botState = botState.copy(isRunning = running, lastBoard = board, lastDecision = decision)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, GameAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    private fun checkAccessibilityService(): Boolean {
        return isAccessibilityServiceEnabled()
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(botStateReceiver)
    }

    data class BotState(
        val isRunning: Boolean = false,
        val lastBoard: String = "",
        val lastDecision: String = ""
    )
}

@Composable
fun MainScreen(
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onToggleBot: () -> Unit,
    isServiceEnabled: Boolean,
    hasOverlayPermission: Boolean,
    botState: MainActivity.BotState
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceEnabled) Color.Green.copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = context.getString(R.string.accessibility_service_name),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isServiceEnabled) context.getString(R.string.service_enabled) else context.getString(R.string.service_disabled),
                    fontSize = 14.sp,
                    color = if (isServiceEnabled) Color.Green else Color.Red
                )
                if (!isServiceEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Stap 1: Klik knop → zoek 'Game Auto Player' in lijst → zet aan\nStap 2: Als niet zichtbaar: app verwijderen & opnieuw installeren",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onEnableAccessibility) {
                        Text(context.getString(R.string.enable_service))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasOverlayPermission) Color.Green.copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Overlay Permission",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (hasOverlayPermission) "Toestemming verleend" else "Toestemming vereist",
                    fontSize = 14.sp,
                    color = if (hasOverlayPermission) Color.Green else Color.Red
                )
                if (!hasOverlayPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onEnableOverlay) {
                        Text("Overlay toestaan")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (botState.isRunning) Color.Blue.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Bot Control",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (botState.isRunning) context.getString(R.string.bot_running) else context.getString(R.string.bot_stopped),
                    fontSize = 14.sp,
                    color = if (botState.isRunning) Color.Blue else Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { if (isServiceEnabled && hasOverlayPermission) onToggleBot() },
                    enabled = isServiceEnabled && hasOverlayPermission,
                    colors = if (botState.isRunning)
                        androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                    else
                        androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) {
                    Text(if (botState.isRunning) context.getString(R.string.stop_bot) else context.getString(R.string.start_bot))
                }
            }
        }

        if (botState.isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live Status", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Board: ${botState.lastBoard}", fontSize = 12.sp, maxLines = 3)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Decision: ${botState.lastDecision}", fontSize = 12.sp, maxLines = 2)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(context.getString(R.string.target_package), fontSize = 12.sp, color = Color.Gray)
                Text("com.gimica.mergeblast (Merge Blast)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Blue)
            }
        }
    }
}