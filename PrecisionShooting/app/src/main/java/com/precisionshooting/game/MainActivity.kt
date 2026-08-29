package com.precisionshooting.game

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.isTraceInProgress
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.isActive
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrecisionShootingTheme {
                PrecisionShootingApp(onExit = { finish() })
            }
        }
    }
}

private val GameColors = darkColorScheme(
    primary = Color(0xFF778DA9),
    onPrimary = Color.White,
    secondary = Color(0xFF415A77),
    tertiary = Color(0xFFE63946),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D1B2A),
    onSurface = Color.White
)

@Composable
private fun PrecisionShootingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GameColors, content = content)
}

@Composable
private fun PrecisionShootingApp(onExit: () -> Unit) {
    var inGame by rememberSaveable { mutableStateOf(false) }
    val gameViewModel: GameViewModel = viewModel()
    val context = LocalContext.current

    if (inGame) {
        GameScreen(
            viewModel = gameViewModel,
            onBack = { inGame = false }
        )
    } else {
        MenuScreen(
            onStart = {
                gameViewModel.startRound()
                inGame = true
            },
            onSettings = {
                Toast.makeText(context, context.getString(R.string.settings_coming_soon), Toast.LENGTH_SHORT).show()
            },
            onExit = onExit
        )
    }
}

@Composable
private fun MenuScreen(
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1B2A), Color.Black)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.brand_shooting),
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 5.sp,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.brand_game),
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 9.sp,
                color = Color(0xFFE0E1DD)
            )
            Text(
                text = stringResource(R.string.brand_tagline),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                color = Color(0xFF778DA9)
            )

            Spacer(Modifier.height(56.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(0.78f).height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B263B))
            ) {
                Text(stringResource(R.string.start_game), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth(0.78f).height(54.dp)
            ) {
                Text(stringResource(R.string.settings), letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(0.78f).height(54.dp)
            ) {
                Text(stringResource(R.string.exit), letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.version_name), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GameScreen(viewModel: GameViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val elapsed = ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
                viewModel.update(elapsed)
            }
            lastFrameNanos = now
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF08131F), Color.Black)))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewModel.setPlayArea(size.width / density, size.height / density)
                }
                .pointerInput(density) {
                    detectTapGestures { offset ->
                        viewModel.shoot(offset.x / density, offset.y / density)
                    }
                }
        ) {
            state.targets.forEach { target ->
                val center = Offset(target.xDp * density, target.yDp * density)
                val radius = target.radiusDp * density
                drawCircle(Color(0x22E63946), radius * 1.22f, center)
                drawCircle(Color(0xFFE63946), radius, center, style = Stroke(width = 3f * density))
                drawCircle(Color.White, radius * 0.58f, center, style = Stroke(width = 2f * density))
                drawCircle(Color(0xFFE63946), radius * 0.15f, center)
            }
            state.effects.forEach { effect ->
                val progress = (effect.ageSeconds / 0.22f).coerceIn(0f, 1f)
                drawCircle(
                    color = Color.White.copy(alpha = 1f - progress),
                    radius = (10f + 34f * progress) * density,
                    center = Offset(effect.xDp * density, effect.yDp * density),
                    style = Stroke(width = 2f * density)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.40f))
                .padding(start = 14.dp, end = 14.dp, top = 44.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ ${stringResource(R.string.back)}", color = Color.White)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.score), color = Color(0xFF778DA9), fontSize = 11.sp)
                Text(state.score.toString(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.time), color = Color(0xFF778DA9), fontSize = 11.sp)
                Text(
                    ceil(state.remainingSeconds).toInt().toString(),
                    color = if (state.remainingSeconds <= 5f) Color(0xFFE63946) else Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black
                )
                Text(stringResource(R.string.best_score_short, state.bestScore), color = Color.Gray, fontSize = 10.sp)
            }
        }

        if (state.phase == GamePhase.COUNTDOWN) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ceil(state.countdownSeconds).toInt().coerceAtLeast(1).toString(),
                    color = Color.White,
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        if (state.phase == GamePhase.GAME_OVER) {
            val shots = state.hits + state.misses
            val accuracy = if (shots == 0) 0 else (state.hits * 100 / shots)
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.game_over), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(28.dp))
                    StatLine(stringResource(R.string.final_score), state.score.toString())
                    StatLine(stringResource(R.string.best_score), state.bestScore.toString())
                    StatLine(stringResource(R.string.hits), state.hits.toString())
                    StatLine(stringResource(R.string.shot_accuracy), "$accuracy%")
                    Spacer(Modifier.height(26.dp))
                    Button(onClick = viewModel::startRound, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.play_again), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.back_to_menu))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF778DA9), fontSize = 13.sp)
        Spacer(Modifier.width(24.dp))
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
