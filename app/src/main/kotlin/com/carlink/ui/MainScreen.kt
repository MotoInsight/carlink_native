package com.carlink.ui

import android.view.MotionEvent
import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.navigationBarsPadding
/**
 * Main Screen - Primary Projection Display Interface
 *
 * Updated to properly handle:
 * - GM pane constraints (systemBars insets)
 * - Curved/contoured display edges (displayCutout insets)
 * - H.264 resolution alignment (even width/height)
 */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    displayMode: DisplayMode,
    onNavigateToSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CarlinkManager.State.DISCONNECTED) }
    var isResetting by remember { mutableStateOf(false) }
    val surfaceState = rememberVideoSurfaceState()

    // Track connection start to avoid restarting on surface recreation
    var hasStartedConnection by remember { mutableStateOf(false) }

    // Touch tracking
    var lastTouchTime by remember { mutableLongStateOf(0L) }
    val activeTouches = remember { mutableStateMapOf<Int, TouchPoint>() }

    // Initialize adapter when surface is ready
    LaunchedEffect(surfaceState.surface, surfaceState.width, surfaceState.height) {
        surfaceState.surface?.let { surface ->
            if (surfaceState.width <= 0 || surfaceState.height <= 0) return@let

            // Force even dimensions – required for H.264 macroblock alignment
            val adapterWidth = surfaceState.width and 1.inv()
            val adapterHeight = surfaceState.height and 1.inv()

            logInfo(
                "[CARLINK_RESOLUTION] Sending to adapter: ${adapterWidth}x${adapterHeight} " +
                        "(raw: ${surfaceState.width}x${surfaceState.height}, mode=$displayMode)",
                tag = "UI"
            )

            carlinkManager.initialize(
                surface = surface,
                surfaceWidth = adapterWidth,
                surfaceHeight = adapterHeight,
                callback = object : CarlinkManager.Callback {
                    override fun onStateChanged(newState: CarlinkManager.State) {
                        state = newState
                    }

                    override fun onMediaInfoChanged(mediaInfo: CarlinkManager.MediaInfo) {}

                    override fun onLogMessage(message: String) {}

                    override fun onHostUIPressed() {
                        onNavigateToSettings()
                    }
                },
            )

            if (!hasStartedConnection) {
                hasStartedConnection = true
                carlinkManager.start()
            }
        }
    }

    val isLoading = state != CarlinkManager.State.STREAMING
    val colorScheme = MaterialTheme.colorScheme

    val baseModifier = Modifier
        .fillMaxSize()
        .background(Color.Black)

    // 3 distinct display modes with different insets
    val boxModifier = when (displayMode) {
        DisplayMode.FULLSCREEN_IMMERSIVE -> {
            baseModifier // No insets
        }
        DisplayMode.STATUS_BAR_HIDDEN -> {
            baseModifier
                .navigationBarsPadding()               // Nav only
                .windowInsetsPadding(WindowInsets.displayCutout) // Right safe
        }
        DisplayMode.SYSTEM_UI_VISIBLE -> {
            baseModifier
                .windowInsetsPadding(WindowInsets.systemBars) // Status + nav
                .windowInsetsPadding(WindowInsets.displayCutout)
        }
    }

    Box(modifier = boxModifier) {
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
            onSurfaceAvailable = { surface, width, height ->
                logInfo("[UI_SURFACE] Available: ${width}x$height", tag = "UI")
                surfaceState.onSurfaceAvailable(surface, width, height)
            },
            onSurfaceDestroyed = {
                logInfo("[UI_SURFACE] Destroyed", tag = "UI")
                surfaceState.onSurfaceDestroyed()
                carlinkManager.onSurfaceDestroyed()
            },
            onSurfaceSizeChanged = { width, height ->
                logInfo("[UI_SURFACE] Size changed: ${width}x$height", tag = "UI")
                surfaceState.onSurfaceSizeChanged(width, height)
            },
            onTouchEvent = { event ->
                if (BuildConfig.DEBUG && state == CarlinkManager.State.STREAMING) {
                    val now = System.currentTimeMillis()
                    if (now - lastTouchTime > 1000) {
                        logDebug(
                            "[UI_TOUCH] action=${event.actionMasked}, pointers=${event.pointerCount}",
                            tag = "UI"
                        )
                        lastTouchTime = now
                    }
                }
                handleTouchEvent(event, activeTouches, carlinkManager, surfaceState.width, surfaceState.height)
                true
            },
        )
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.scrim.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_phone_projection),
                        contentDescription = "Carlink",
                        modifier = Modifier.height(220.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    LoadingSpinner(color = colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (state) {
                            CarlinkManager.State.DISCONNECTED -> "[ Connect Adapter ]"
                            CarlinkManager.State.CONNECTING -> "[ Connecting... ]"
                            CarlinkManager.State.DEVICE_CONNECTED -> "[ Waiting for Phone ]"
                            CarlinkManager.State.STREAMING -> "[ Streaming ]"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurface
                    )
                }
            }

            // Control buttons – also respect insets
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FilledTonalButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    contentPadding = PaddingValues(
                        horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                        vertical = AutomotiveDimens.ButtonPaddingVertical
                    )
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(AutomotiveDimens.IconSize))
                    Spacer(Modifier.width(8.dp))
                    Text("Settings", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }

                FilledTonalButton(
                    onClick = {
                        if (!isResetting) {
                            isResetting = true
                            scope.launch {
                                try {
                                    carlinkManager.restart()
                                } finally {
                                    isResetting = false
                                }
                            }
                        }
                    },
                    enabled = !isResetting,
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(
                        horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                        vertical = AutomotiveDimens.ButtonPaddingVertical
                    )
                ) {
                    AnimatedContent(
                        targetState = isResetting,
                        transitionSpec = { (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut()) },
                        label = "resetIconTransition"
                    ) { resetting ->
                        if (resetting) {
                            LoadingSpinner(size = AutomotiveDimens.IconSize, color = colorScheme.onErrorContainer)
                        } else {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset Device", modifier = Modifier.size(AutomotiveDimens.IconSize))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Device", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }
            }
        }
    }
}

private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0) return

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)
    val action = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
        MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
        else -> return
    }

    val x = event.getX(pointerIndex) / surfaceWidth
    val y = event.getY(pointerIndex) / surfaceHeight

    when (action) {
        MultiTouchAction.DOWN -> activeTouches[pointerId] = TouchPoint(x, y, action)
        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = event.getX(i) / surfaceWidth
                val py = event.getY(i) / surfaceHeight
                activeTouches[id]?.let { existing ->
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) {
                        activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                    }
                }
            }
        }
        MultiTouchAction.UP -> activeTouches[pointerId] = TouchPoint(x, y, action)
        else -> {}
    }

    val touchList = activeTouches.entries.mapIndexed { index, entry ->
        MessageSerializer.TouchPoint(
            x = entry.value.x,
            y = entry.value.y,
            action = entry.value.action,
            id = index
        )
    }

    carlinkManager.sendMultiTouch(touchList)
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
}
