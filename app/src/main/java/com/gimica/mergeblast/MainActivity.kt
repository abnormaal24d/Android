package com.gimica.mergeblast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gimica.mergeblast.service.GameAccessibilityService
import com.gimica.mergeblast.ui.theme.AutomaterTheme

class MainActivity : ComponentActivity() {
    private var botState by mutableStateOf(BotState())
    private var isServiceEnabledState by mutableStateOf(false)

    private val openAccessibilitySettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshServiceState()
    }

    private val botStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val update = intent ?: return
            botState = botState.copy(
                isRunning = if (update.hasExtra(GameAccessibilityService.EXTRA_BOT_RUNNING)) {
                    update.getBooleanExtra(GameAccessibilityService.EXTRA_BOT_RUNNING, false)
                } else {
                    botState.isRunning
                },
                lastBoard = update.getStringExtra(GameAccessibilityService.EXTRA_BOARD_STATE)
                    ?: botState.lastBoard,
                lastDecision = update.getStringExtra(GameAccessibilityService.EXTRA_DECISION)
                    ?: botState.lastDecision
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 / targetSdk 36 enforces edge-to-edge. Keep it explicit and consume safe
        // drawing insets in Compose so controls never end up behind status/navigation bars.
        enableEdgeToEdge()
        refreshServiceState()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            botStateReceiver,
            IntentFilter(GameAccessibilityService.ACTION_BOT_STATE_CHANGED)
        )

        setContent {
            AutomaterTheme {
                MainScreen(
                    onEnableAccessibility = {
                        openAccessibilitySettings.launch(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    },
                    onToggleBot = { toggleBotFromUi() },
                    isServiceEnabled = isServiceEnabledState,
                    isServiceReady = GameAccessibilityService.getInstance() != null,
                    botState = botState
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    private fun toggleBotFromUi() {
        val service = GameAccessibilityService.getInstance()
        if (service == null) {
            refreshServiceState()
            botState = botState.copy(
                lastDecision = "Accessibility service is enabled but not connected yet"
            )
            return
        }
        service.toggleBot()
    }

    private fun refreshServiceState() {
        isServiceEnabledState = isAccessibilityServiceEnabled()
        GameAccessibilityService.getInstance()?.let { service ->
            botState = botState.copy(isRunning = service.isRunning())
        } ?: run {
            if (!isServiceEnabledState) {
                botState = botState.copy(isRunning = false)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, GameAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(botStateReceiver)
        super.onDestroy()
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
    onToggleBot: () -> Unit,
    isServiceEnabled: Boolean,
    isServiceReady: Boolean,
    botState: MainActivity.BotState
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceEnabled) {
                    Color.Green.copy(alpha = 0.1f)
                } else {
                    Color.Red.copy(alpha = 0.1f)
                }
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
                    text = when {
                        !isServiceEnabled -> context.getString(R.string.service_disabled)
                        isServiceReady -> context.getString(R.string.service_enabled)
                        else -> "Service enabled, connecting…"
                    },
                    fontSize = 14.sp,
                    color = if (isServiceEnabled) Color.Green else Color.Red
                )

                if (!isServiceEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Open Accessibility settings and enable Game Auto Player.",
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (botState.isRunning) {
                    Color.Blue.copy(alpha = 0.1f)
                } else {
                    Color.Gray.copy(alpha = 0.1f)
                }
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
                    text = if (botState.isRunning) {
                        context.getString(R.string.bot_running)
                    } else {
                        context.getString(R.string.bot_stopped)
                    },
                    fontSize = 14.sp,
                    color = if (botState.isRunning) Color.Blue else Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onToggleBot,
                    enabled = isServiceEnabled && isServiceReady,
                    colors = if (botState.isRunning) {
                        androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                    } else {
                        androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Green)
                    }
                ) {
                    Text(
                        if (botState.isRunning) {
                            context.getString(R.string.stop_bot)
                        } else {
                            context.getString(R.string.start_bot)
                        }
                    )
                }
            }
        }

        if (botState.isRunning || botState.lastDecision.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live Status", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (botState.lastBoard.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Board: ${botState.lastBoard}", fontSize = 12.sp, maxLines = 3)
                    }
                    if (botState.lastDecision.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Decision: ${botState.lastDecision}", fontSize = 12.sp, maxLines = 3)
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    context.getString(R.string.target_package),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    "com.gimica.mergeblast (Merge Blast)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Blue
                )
            }
        }
    }
}
