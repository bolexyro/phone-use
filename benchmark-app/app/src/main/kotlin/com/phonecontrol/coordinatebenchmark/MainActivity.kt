package com.phonecontrol.coordinatebenchmark

import android.os.Bundle
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.hypot
import kotlin.random.Random
import java.util.UUID

private val Background = Color(0xFF080C16)
private val Panel = Color(0xFF111827)
private val TargetColor = Color(0xFF48E0C2)
private val NoiseColor = Color(0xFF9AA7BD)
private val DecoyColors = listOf(
    Color(0xFF536078), // slate blue
    Color(0xFF7A5F78), // muted plum
    Color(0xFF8A6B4A), // muted amber
    Color(0xFF5F7180), // steel blue
    Color(0xFF7A5962), // muted rose
    Color(0xFF6A6082), // muted violet
    Color(0xFF82645A), // muted clay
    Color(0xFF586B78)  // blue gray
)
private val FailureColor = Color(0xFFFF6B7A)
private val MutedText = Color(0xFF9AA7BD)

private data class PointSet(
    val target: Offset,
    val decoys: List<Offset>
)

private data class Attempt(
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val outcome: String,
    val distance: Float,
    val targetX: Float,
    val targetY: Float,
    val targetRadius: Float,
    val round: Int,
    val noiseDots: Int,
    val decoyCount: Int
)

private data class BenchmarkSession(
    val id: String,
    val startedAt: Long,
    val agentName: String,
    val reasoningLevel: String,
    val targetRadiusDp: Float,
    val noiseDots: Int,
    val decoyCount: Int
)

private class BenchmarkState {
    var hitCount by mutableIntStateOf(0)
    var missCount by mutableIntStateOf(0)
    var round by mutableIntStateOf(0)
    var failureMessage by mutableStateOf<String?>(null)
    var lastTap by mutableStateOf<Offset?>(null)
    var points by mutableStateOf<PointSet?>(null)
    var configuredDecoyCount by mutableIntStateOf(-1)
    val attempts = mutableListOf<Attempt>()

    fun reset(size: IntSize, radius: Float, decoyCount: Int) {
        if (size.width <= 0 || size.height <= 0) return
        points = newPointSet(size, radius, decoyCount)
        configuredDecoyCount = decoyCount
    }

    fun registerTap(
        tap: Offset,
        size: IntSize,
        radius: Float,
        noiseDots: Int,
        decoyCount: Int
    ): Attempt? {
        val current = points ?: return null
        val targetDistance = distance(tap, current.target)
        val decoyIndex = current.decoys.indexOfFirst { distance(tap, it) <= radius }
        val outcome = when {
            targetDistance <= radius -> "hit"
            decoyIndex >= 0 -> "decoy"
            else -> "miss"
        }
        val attempt = Attempt(
            timestamp = System.currentTimeMillis(),
            x = tap.x,
            y = tap.y,
            outcome = outcome,
            distance = targetDistance,
            targetX = current.target.x,
            targetY = current.target.y,
            targetRadius = radius,
            round = round + 1,
            noiseDots = noiseDots,
            decoyCount = decoyCount
        )
        attempts += attempt
        lastTap = tap
        if (outcome == "hit") {
            hitCount += 1
            round += 1
            failureMessage = null
            lastTap = null
            points = newPointSet(size, radius, decoyCount)
        } else {
            missCount += 1
            failureMessage = if (outcome == "decoy") {
                "FAILED — you hit a decoy point"
            } else {
                "FAILED — target missed"
            }
        }
        return attempt
    }

    fun updateDecoyCount(size: IntSize, radius: Float, decoyCount: Int) {
        if (size.width <= 0 || size.height <= 0 || points == null || configuredDecoyCount == decoyCount) return
        points = newPointSet(size, radius, decoyCount, points!!.target)
        configuredDecoyCount = decoyCount
    }

    private fun newPointSet(
        size: IntSize,
        radius: Float,
        decoyCount: Int,
        existingTarget: Offset? = null
    ): PointSet {
        val padding = radius * 1.8f
        val top = padding
        val bottom = size.height - padding
        val left = padding
        val right = size.width - padding
        val chosen = mutableListOf<Offset>()
        existingTarget?.let { chosen += it }
        repeat(decoyCount + if (existingTarget == null) 1 else 0) {
            var candidate: Offset
            var attempts = 0
            do {
                candidate = Offset(
                    Random.nextFloat() * (right - left) + left,
                    Random.nextFloat() * (bottom - top) + top
                )
                attempts += 1
            } while (chosen.any { distance(it, candidate) < radius * 3.2f } && attempts < 100)
            chosen += candidate
        }
        return PointSet(chosen.first(), chosen.drop(1))
    }
}

private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        CoordinateBenchmarkApp()
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateBenchmarkApp() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val logFile = remember { File(context.filesDir, "coordinate-benchmark.ndjson") }
    var session by remember { mutableStateOf<BenchmarkSession?>(null) }
    val activeSession = session

    if (activeSession == null) {
        SessionSetup { agentName, reasoningLevel, targetRadiusDp, noiseDots, decoyCount ->
            val newSession = BenchmarkSession(
                id = UUID.randomUUID().toString(),
                startedAt = System.currentTimeMillis(),
                agentName = agentName.ifBlank { "unspecified" },
                reasoningLevel = reasoningLevel.ifBlank { "unspecified" },
                targetRadiusDp = targetRadiusDp,
                noiseDots = noiseDots,
                decoyCount = decoyCount
            )
            session = newSession
            scope.launch(Dispatchers.IO) { appendSessionStart(logFile, newSession) }
        }
    } else {
        CoordinateBenchmark(
            session = activeSession,
            logFile = logFile,
            onEnd = { state ->
                scope.launch(Dispatchers.IO) { appendSessionEnd(logFile, activeSession, state) }
                session = null
            }
        )
    }
}

@Composable
private fun SessionSetup(onStart: (String, String, Float, Int, Int) -> Unit) {
    var agentName by remember { mutableStateOf("") }
    var reasoningLevel by remember { mutableStateOf("") }
    var targetRadiusDp by remember { mutableStateOf(10f) }
    var noiseDots by remember { mutableIntStateOf(24) }
    var decoyCount by remember { mutableIntStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "COORDINATE BENCHMARK",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )
        Text(
            text = "Start a named session before the first tap.",
            color = MutedText,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp)
        )
        OutlinedTextField(
            value = agentName,
            onValueChange = { agentName = it },
            label = { Text("Coding agent") },
            placeholder = { Text("e.g. Luna") },
            singleLine = true,
            colors = sessionFieldColors(),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = reasoningLevel,
            onValueChange = { reasoningLevel = it },
            label = { Text("Reasoning level") },
            placeholder = { Text("e.g. high, medium, low") },
            singleLine = true,
            colors = sessionFieldColors(),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Target radius: ${targetRadiusDp.toInt()} dp (${(targetRadiusDp * 2).toInt()} dp diameter)",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Slider(
            value = targetRadiusDp,
            onValueChange = { targetRadiusDp = it },
            valueRange = 6f..24f,
            steps = 17,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Text(
            text = "Background noise: $noiseDots gray dots",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Slider(
            value = noiseDots.toFloat(),
            onValueChange = { noiseDots = it.toInt() },
            valueRange = 0f..80f,
            steps = 15,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Text(
            text = "Decoys: $decoyCount gray points",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Slider(
            value = decoyCount.toFloat(),
            onValueChange = { decoyCount = it.toInt() },
            valueRange = 0f..50f,
            steps = 49,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onStart(agentName, reasoningLevel, targetRadiusDp, noiseDots, decoyCount) },
            colors = ButtonDefaults.buttonColors(containerColor = TargetColor),
            modifier = Modifier.widthIn(min = 180.dp)
        ) {
            Text("Start session", color = Background, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "A session records its ID, timestamps, agent, reasoning level, and every tap.",
            color = MutedText,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun sessionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = TargetColor,
    unfocusedLabelColor = MutedText,
    focusedPlaceholderColor = MutedText,
    unfocusedPlaceholderColor = MutedText,
    focusedBorderColor = TargetColor,
    unfocusedBorderColor = Color(0xFF64748B),
    cursorColor = TargetColor,
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel
)

@Composable
private fun CoordinateBenchmark(
    session: BenchmarkSession,
    logFile: File,
    onEnd: (BenchmarkState) -> Unit
) {
    val state = remember(session.id) { BenchmarkState() }
    val scope = rememberCoroutineScope()
    var radiusDp by remember(session.id) { mutableFloatStateOf(session.targetRadiusDp) }
    var noiseDots by remember(session.id) { mutableIntStateOf(session.noiseDots) }
    var decoyCount by remember(session.id) { mutableIntStateOf(session.decoyCount) }
    // Deliberately smaller than the usual 44–48 dp touch target: this benchmark
    // is intended to measure visual-coordinate precision on compact controls.
    val radiusPx = with(LocalDensity.current) { radiusDp.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Header(
            state = state,
            session = session,
            radiusDp = radiusDp,
            onRadiusChange = { radiusDp = it },
            onRadiusChangeFinished = {
                scope.launch(Dispatchers.IO) {
                    appendRadiusChange(logFile, session, radiusDp)
                }
            },
            noiseDots = noiseDots,
            onNoiseChange = { noiseDots = it },
            onNoiseChangeFinished = {
                scope.launch(Dispatchers.IO) {
                    appendNoiseChange(logFile, session, noiseDots)
                }
            },
            decoyCount = decoyCount,
            onDecoyChange = { decoyCount = it },
            onDecoyChangeFinished = {
                scope.launch(Dispatchers.IO) {
                    appendDecoyChange(logFile, session, decoyCount)
                }
            },
            onEnd = { onEnd(state) }
        )
        Spacer(Modifier.height(8.dp))
        Arena(
            state = state,
            radiusPx = radiusPx,
            noiseDots = noiseDots,
            decoyCount = decoyCount,
            modifier = Modifier.weight(1f),
            onAttempt = { attempt ->
                scope.launch(Dispatchers.IO) { appendAttempt(logFile, session, attempt) }
            }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap the green point. Gray points are decoys.",
            color = MutedText,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun Header(
    state: BenchmarkState,
    session: BenchmarkSession,
    radiusDp: Float,
    onRadiusChange: (Float) -> Unit,
    onRadiusChangeFinished: () -> Unit,
    noiseDots: Int,
    onNoiseChange: (Int) -> Unit,
    onNoiseChangeFinished: () -> Unit,
    decoyCount: Int,
    onDecoyChange: (Int) -> Unit,
    onDecoyChangeFinished: () -> Unit,
    onEnd: () -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "COORDINATE BENCHMARK",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
                Text(
                    text = "${session.agentName} · ${session.reasoningLevel} · ${radiusDp.toInt()}dp · $noiseDots noise · $decoyCount decoys · ${session.id.take(8)}",
                    color = MutedText,
                    fontSize = 9.sp
                )
            }
            TextButton(onClick = onEnd) { Text("End", color = FailureColor, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard("Hit", state.hitCount, TargetColor, Modifier.weight(1f))
            StatCard("Miss", state.missCount, FailureColor, Modifier.weight(1f))
            StatCard("Round", state.round + 1, Color(0xFFB6C3FF), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactSlider(
                label = "Radius ${radiusDp.toInt()}dp",
                value = radiusDp,
                onValueChange = onRadiusChange,
                onValueChangeFinished = onRadiusChangeFinished,
                valueRange = 6f..24f,
                steps = 17,
                modifier = Modifier.weight(1f)
            )
            CompactSlider(
                label = "Noise $noiseDots",
                value = noiseDots.toFloat(),
                onValueChange = { onNoiseChange(it.toInt()) },
                onValueChangeFinished = onNoiseChangeFinished,
                valueRange = 0f..80f,
                steps = 15,
                modifier = Modifier.weight(1f)
            )
            CompactSlider(
                label = "Decoys $decoyCount",
                value = decoyCount.toFloat(),
                onValueChange = { onDecoyChange(it.toInt()) },
                onValueChangeFinished = onDecoyChangeFinished,
                valueRange = 0f..50f,
                steps = 49,
                modifier = Modifier.weight(1f)
            )
        }
        // Keep this slot in the layout even when empty. A failure message must
        // never move the arena, otherwise the next screenshot has new geometry.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(top = 10.dp)
        ) {
            state.failureMessage?.let { message ->
                Text(
                    text = message,
                    color = FailureColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF321A27), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, color = Color.White, fontSize = 10.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(30.dp)
        )
    }
}

@Composable
private fun StatCard(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, color = MutedText, fontSize = 10.sp)
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Arena(
    state: BenchmarkState,
    radiusPx: Float,
    noiseDots: Int,
    decoyCount: Int,
    modifier: Modifier = Modifier,
    onAttempt: (Attempt) -> Unit
) {
    var arenaSize by remember { mutableStateOf(IntSize.Zero) }
    val noise = remember(arenaSize, noiseDots) {
        generateNoise(arenaSize, noiseDots)
    }
    LaunchedEffect(arenaSize, radiusPx, decoyCount) {
        if (arenaSize != IntSize.Zero) {
            if (state.points == null) {
                state.reset(arenaSize, radiusPx, decoyCount)
            } else {
                state.updateDecoyCount(arenaSize, radiusPx, decoyCount)
            }
        }
    }
    Box(
        modifier = modifier
            .background(Color(0xFF0D1422), RoundedCornerShape(16.dp))
            .onSizeChanged { arenaSize = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.points, arenaSize, radiusPx, noiseDots, decoyCount) {
                    detectTapGestures { tap ->
                        state.registerTap(tap, arenaSize, radiusPx, noiseDots, decoyCount)?.let(onAttempt)
                    }
                }
        ) {
            val points = state.points ?: return@Canvas
            val radius = radiusPx
            noise.forEach { dot ->
                drawCircle(NoiseColor, dot.radius, dot.center)
            }
            points.decoys.forEachIndexed { index, point ->
                drawCircle(DecoyColors[index % DecoyColors.size], radius, point)
            }
            drawCircle(TargetColor, radius, points.target)
            drawCircle(Color.White.copy(alpha = 0.8f), radius * 0.72f, points.target, style = Stroke(width = 2f))
            state.lastTap?.let { tap ->
                drawCircle(FailureColor, radius * 0.23f, tap)
                drawCircle(FailureColor.copy(alpha = 0.55f), radius * 0.55f, tap, style = Stroke(width = 2f))
            }
        }
        if (state.points == null) {
            Text("Preparing target…", color = MutedText, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private data class NoiseDot(val center: Offset, val radius: Float)

private fun generateNoise(size: IntSize, count: Int): List<NoiseDot> {
    if (size.width <= 0 || size.height <= 0 || count <= 0) return emptyList()
    val random = Random(size.width * 31 + size.height * 17 + count)
    return List(count) {
        NoiseDot(
            center = Offset(
                random.nextFloat() * size.width,
                random.nextFloat() * size.height
            ),
            radius = 2.5f + random.nextFloat() * 4.5f
        )
    }
}

private fun appendSessionStart(file: File, session: BenchmarkSession) {
    val json = JSONObject()
        .put("event", "session_start")
        .put("sessionId", session.id)
        .put("timestamp", session.startedAt)
        .put("timestampIso", Instant.ofEpochMilli(session.startedAt).toString())
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("targetRadiusDp", session.targetRadiusDp.toDouble())
        .put("noiseDots", session.noiseDots)
        .put("decoyCount", session.decoyCount)
    file.appendText(json.toString() + "\n")
}

private fun appendSessionEnd(file: File, session: BenchmarkSession, state: BenchmarkState) {
    val endedAt = System.currentTimeMillis()
    val json = JSONObject()
        .put("event", "session_end")
        .put("sessionId", session.id)
        .put("timestamp", endedAt)
        .put("timestampIso", Instant.ofEpochMilli(endedAt).toString())
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("hits", state.hitCount)
        .put("misses", state.missCount)
        .put("attempts", state.attempts.size)
    file.appendText(json.toString() + "\n")
}

private fun appendRadiusChange(file: File, session: BenchmarkSession, radiusDp: Float) {
    val changedAt = System.currentTimeMillis()
    val json = JSONObject()
        .put("event", "radius_change")
        .put("sessionId", session.id)
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("timestamp", changedAt)
        .put("timestampIso", Instant.ofEpochMilli(changedAt).toString())
        .put("targetRadiusDp", radiusDp.toDouble())
    file.appendText(json.toString() + "\n")
}

private fun appendNoiseChange(file: File, session: BenchmarkSession, noiseDots: Int) {
    val changedAt = System.currentTimeMillis()
    val json = JSONObject()
        .put("event", "noise_change")
        .put("sessionId", session.id)
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("timestamp", changedAt)
        .put("timestampIso", Instant.ofEpochMilli(changedAt).toString())
        .put("noiseDots", noiseDots)
    file.appendText(json.toString() + "\n")
}

private fun appendDecoyChange(file: File, session: BenchmarkSession, decoyCount: Int) {
    val changedAt = System.currentTimeMillis()
    val json = JSONObject()
        .put("event", "decoy_change")
        .put("sessionId", session.id)
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("timestamp", changedAt)
        .put("timestampIso", Instant.ofEpochMilli(changedAt).toString())
        .put("decoyCount", decoyCount)
    file.appendText(json.toString() + "\n")
}

private fun appendAttempt(file: File, session: BenchmarkSession, attempt: Attempt) {
    val json = JSONObject()
        .put("event", "attempt")
        .put("sessionId", session.id)
        .put("agent", session.agentName)
        .put("reasoningLevel", session.reasoningLevel)
        .put("timestamp", attempt.timestamp)
        .put("timestampIso", Instant.ofEpochMilli(attempt.timestamp).toString())
        .put("x", attempt.x.toDouble())
        .put("y", attempt.y.toDouble())
        .put("outcome", attempt.outcome)
        .put("targetDistancePx", attempt.distance.toDouble())
        .put("targetX", attempt.targetX.toDouble())
        .put("targetY", attempt.targetY.toDouble())
        .put("targetRadiusPx", attempt.targetRadius.toDouble())
        .put("round", attempt.round)
        .put("noiseDots", attempt.noiseDots)
        .put("decoyCount", attempt.decoyCount)
    file.appendText(json.toString() + "\n")
}
