package com.missminutes.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Pico Camera: a small window over whatever is on screen, showing the back camera (the front one a tap away), with
 * the assistant sitting at its bottom-left corner. Whatever is asked while it's open comes with a photo of what the
 * camera sees.
 */
class CameraActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FRONT = "front"
        private const val ASK_CAMERA = 1
        /** Photos for Claude are kept small: plenty to recognise things, quick to send. */
        private const val PHOTO_PX = 1024
    }

    private lateinit var prefs: Prefs
    private lateinit var preview: PreviewView
    private lateinit var hint: TextView
    private lateinit var ghost: GhostView
    private var provider: ProcessCameraProvider? = null
    private lateinit var starting: TextView
    private lateinit var loading: View
    private var front = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PicoLog.init(this)
        prefs = Prefs(this)
        front = intent.getBooleanExtra(EXTRA_FRONT, false)
        setContentView(buildWindow())
        startCameraOrAsk()
        PicoCamera.ghost = ghost
        PicoCamera.onCaption = { line -> runOnUiThread { hint.text = line } }
        PicoCamera.frame = { photo() }
        PicoCamera.opened()
        // Opened from the button: start listening straight away so "what is this?" can be asked at once.
        if (!intent.getBooleanExtra("from_voice", false)) talk()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("close", false)) { finish(); return }
        val wantFront = intent.getBooleanExtra(EXTRA_FRONT, front)
        if (wantFront != front) { front = wantFront; bindCamera() }
    }

    override fun onDestroy() {
        if (PicoCamera.ghost === ghost) PicoCamera.closed()
        ghost.stop()
        provider?.unbindAll()
        super.onDestroy()
    }

    // ---------- Window ----------

    private fun buildWindow(): View {
        val screen = resources.displayMetrics
        val width = min(screen.widthPixels - dpi(32), dpi(420))
        val ghostSize = dpi(84)
        val root = FrameLayout(this).apply {
            clipChildren = false // the ghost sits over the window's corner
            setBackgroundColor(Palette.alpha(Color.BLACK, 120))
            setOnClickListener { } // a tap beside the window doesn't close it by accident
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(10), dpi(12), dpi(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(Palette.DIAL)
                setStroke(dpi(1), Palette.alpha(Palette.CREAM, 26))
            }
            elevation = dp(12f)
        }
        // Top: the name, flip camera, close.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("${prefs.assistantName} Camera", 16f, Fonts.SEMIBOLD, Palette.CREAM).apply { setPadding(dpi(6), 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton("↻", "Switch between back and front camera") { front = !front; bindCamera() })
            // Close on its own at the far right, a little apart from Flip.
            addView(iconButton("✕", "Close the camera") { finish() },
                LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginStart = dpi(14) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)))
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // so a photo can be taken from it
            scaleType = PreviewView.ScaleType.FILL_CENTER
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(Color.BLACK) }
        }
        // "Starting camera…" over the black box until the first picture arrives.
        starting = text("Starting camera…", 15f, Fonts.MEDIUM, Palette.CREAM).apply { gravity = Gravity.CENTER }
        card.addView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@CameraActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(android.widget.ProgressBar(this@CameraActivity).apply { indeterminateTintList = ColorStateList.valueOf(Palette.AMBER) },
                    LinearLayout.LayoutParams(dpi(40), dpi(40)))
                addView(starting, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
            }.also { loading = it }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (width - dpi(24)) * 4 / 3).apply { topMargin = dpi(6) })
        hint = text("Point at something and ask “What is this?” or “Where can I buy this?”",
            14.5f, Fonts.REGULAR, Palette.CREAM).apply {
            setLineSpacing(0f, 1.2f)
            // Leaves room on the left for the assistant sitting on the corner.
            setPadding(ghostSize - dpi(12), dpi(10), dpi(4), 0)
        }
        card.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        holder.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // The assistant drops onto the window's bottom-left corner.
        ghost = GhostView(this).apply {
            theme = GhostTheme.of(prefs.ghostTheme)
            contentDescription = "${prefs.assistantName}. Tap to talk."
            elevation = dp(16f) // above the window, which has a shadow of its own
            setOnClickListener { talk() }
        }
        holder.addView(ghost, FrameLayout.LayoutParams(ghostSize, ghostSize, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = -dpi(18); bottomMargin = -dpi(22)
        })
        root.addView(holder, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            bottomMargin = dpi(40)
        })
        // A quick, gentle entrance: the window rises in, then the ghost drops into its corner.
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            holder.alpha = 0f; holder.translationY = dp(24f)
            holder.animate().alpha(1f).translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(260).start()
            ghost.translationY = -dp(220f); ghost.alpha = 0f
            ghost.animate().translationY(0f).alpha(1f).setStartDelay(180).setInterpolator(OvershootInterpolator(1.2f)).setDuration(420).start()
        }
        ghost.state = OrbState.IDLE
        ghost.start(withIntro = false)
        return root
    }

    private fun talk() {
        runCatching { startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_TALK)) }
    }

    // ---------- Camera ----------

    private fun startCameraOrAsk() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), ASK_CAMERA)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else hint.text = "${prefs.assistantName} needs the camera permission to see. Allow it in Settings, Permissions."
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            bindCamera()
        }, mainExecutor)
    }

    private fun bindCamera() {
        val p = provider ?: return
        val lens = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        runCatching {
            p.unbindAll()
            val use = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            p.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lens).build(), use)
            PicoCamera.facing = if (front) "front" else "back"
            loading.visibility = View.VISIBLE
            preview.previewStreamState.observe(this) { state ->
                if (state == PreviewView.StreamState.STREAMING) loading.visibility = View.GONE
            }
        }.onFailure {
            PicoLog.w("Camera failed", it)
            hint.text = "The camera didn't start. Close it and try again."
            starting.text = "The camera didn't start"
        }
    }

    /** What the camera sees now, as a small JPEG for Claude. */
    private suspend fun photo(): ByteArray? {
        val shot: Bitmap = withContext(Dispatchers.Main) { preview.bitmap } ?: return null
        return withContext(Dispatchers.Default) {
            val scale = min(1f, PHOTO_PX.toFloat() / maxOf(shot.width, shot.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
            ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
        }
    }

    // ---------- Building blocks ----------

    private fun text(value: String, size: Float, weight: Int, color: Int) = TextView(this).apply {
        text = value; textSize = size; typeface = Fonts.get(this@CameraActivity, weight); setTextColor(color)
    }

    private fun iconButton(icon: String, description: String, onClick: () -> Unit) = text(icon, 20f, Fonts.BOLD, Palette.CREAM).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SURFACE_HI) }, null)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(48), dpi(48))
    }

    private fun pill(label: String, onClick: () -> Unit) = text(label, 13.5f, Fonts.SEMIBOLD, Palette.CREAM).apply {
        gravity = Gravity.CENTER
        setPadding(dpi(14), 0, dpi(14), 0)
        minHeight = dpi(36)
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)),
            GradientDrawable().apply { cornerRadius = dp(99f); setColor(Palette.SURFACE_HI) }, null)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
