package com.graviton.halocount

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), OverlayView.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var hud: HudView
    private lateinit var btnTorch: NeonButton
    private lateinit var btnShutter: NeonButton
    private lateinit var btnAuto: NeonButton

    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile
    private var liveCounter: Counter? = null

    @Volatile
    private var snapCounter: Counter? = null

    private val recent = ArrayDeque<Int>()
    private var torchOn = false
    private var pendingBitmap: Bitmap? = null
    private var lastAnalysis = 0L
    private var lastContrast = 255f
    private var lastAuto = true

    @Volatile
    private var frozen = false

    @Volatile
    private var snapRequested = false

    @Volatile
    private var refFrac = 0f

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                startCamera()
            } else {
                hud.setStatus("camera permission needed - tap the button to retry", HudView.Tone.WARN)
            }
        }

    // ------------------------------------------------------------------ UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(Palette.BG)

        previewView = PreviewView(this)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.setBackgroundColor(Palette.BG)

        frozenView = ImageView(this)
        frozenView.scaleType = ImageView.ScaleType.FIT_XY
        frozenView.visibility = View.GONE

        overlay = OverlayView(this)
        overlay.listener = this

        hud = HudView(this)
        hud.setLabel("IN VIEW")
        hud.setHint(HINT_LIVE)
        hud.setStatus("starting camera...", HudView.Tone.INFO)

        btnTorch = NeonButton(this, NeonButton.Kind.TORCH, "TORCH")
        btnShutter = NeonButton(this, NeonButton.Kind.SHUTTER, "SNAP")
        btnShutter.big = true
        btnAuto = NeonButton(this, NeonButton.Kind.AUTO, "AUTO SIZE")

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(btnTorch, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(btnShutter, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(btnAuto, LinearLayout.LayoutParams(0, MATCH, 1f))
        val barLp = FrameLayout.LayoutParams(MATCH, (100 * dp).toInt(), Gravity.BOTTOM)
        barLp.bottomMargin = (30 * dp).toInt()
        barLp.leftMargin = (16 * dp).toInt()
        barLp.rightMargin = (16 * dp).toInt()

        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frozenView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(bar, barLp)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)

        btnTorch.setOnClickListener { toggleTorch() }
        btnShutter.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onShutter()
        }
        btnAuto.setOnClickListener { resetSize() }

        if (hasCameraPermission()) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        exec.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ camera
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio43)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSel)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysisSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec, analyzer)

            provider.unbindAll()
            val cam = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            camera = cam
            btnTorch.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.INVISIBLE
            hud.setStatus("counting...", HudView.Tone.COUNTING)
        }, ContextCompat.getMainExecutor(this))
    }

    private val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    /** Runs on the analysis thread. */
    private fun process(image: ImageProxy) {
        if (snapRequested) {
            snapRequested = false
            val res = analyse(image, SNAP_W, true)
            ui.post { onSnapResult(res) }
            return
        }
        if (frozen) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAnalysis < MIN_INTERVAL_MS) return
        lastAnalysis = now
        val res = analyse(image, LIVE_W, false)
        ui.post { onLiveResult(res) }
    }

    private fun analyse(image: ImageProxy, gw: Int, snap: Boolean): Result {
        val size = Sampler.uprightSize(image)
        val gh = max(8, (gw.toFloat() * size.second / size.first).roundToInt())
        val existing = if (snap) snapCounter else liveCounter
        val counter = if (existing != null && existing.w == gw && existing.h == gh) {
            existing
        } else {
            val fresh = Counter(gw, gh)
            if (snap) snapCounter = fresh else liveCounter = fresh
            fresh
        }
        Sampler.fill(image, counter.gray, gw, gh)
        return counter.analyze(refFrac)
    }

    // ------------------------------------------------------------------ results (main thread)
    private fun onLiveResult(res: Result) {
        if (frozen) return
        overlay.updateLive(res.blobs, res.gw, res.gh)

        recent.addLast(res.blobs.size)
        if (recent.size > 7) recent.removeFirst()
        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]
        var steady = recent.size >= 5
        if (steady) {
            val last = recent.last()
            for (v in recent.takeLast(5)) if (v != last) steady = false
        }
        lastContrast = res.contrast
        lastAuto = res.autoSize
        hud.setCount(median)

        val size = if (res.autoSize) "auto size" else "size set"
        if (res.contrast < Counter.MIN_CONTRAST) {
            hud.setStatus("nothing found - or too little contrast", HudView.Tone.WARN)
        } else if (steady) {
            hud.setStatus("steady  -  $size", HudView.Tone.STEADY)
        } else {
            hud.setStatus("counting...  -  $size", HudView.Tone.COUNTING)
        }
    }

    private fun onSnapResult(res: Result) {
        val bmp = pendingBitmap
        pendingBitmap = null
        frozen = true
        if (bmp != null) {
            frozenView.setImageBitmap(bmp)
            frozenView.visibility = View.VISIBLE
        }
        overlay.showFrozen(res.blobs, res.gw, res.gh)
        btnShutter.alt = true
        btnShutter.caption = "LIVE"
        lastContrast = res.contrast
        lastAuto = res.autoSize
        hud.setLabel("COUNTED")
        hud.setHint(HINT_FROZEN)
        updateFrozenHud()
    }

    private fun updateFrozenHud() {
        hud.setCount(overlay.count())
        val size = if (lastAuto) "auto size" else "size set"
        if (lastContrast < Counter.MIN_CONTRAST) {
            hud.setStatus("nothing found - tap to add objects by hand", HudView.Tone.WARN)
        } else {
            hud.setStatus("photo  -  $size", HudView.Tone.STEADY)
        }
    }

    // ------------------------------------------------------------------ controls
    private fun onShutter() {
        if (frozen) {
            resume()
            return
        }
        if (camera == null) {
            if (!hasCameraPermission()) permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val bmp = previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBitmap = bmp
        snapRequested = true
        hud.setStatus("capturing...", HudView.Tone.COUNTING)
    }

    private fun resume() {
        frozen = false
        frozenView.visibility = View.GONE
        frozenView.setImageDrawable(null)
        overlay.clear()
        recent.clear()
        btnShutter.alt = false
        btnShutter.caption = "SNAP"
        hud.setLabel("IN VIEW")
        hud.setCount(0)
        hud.setHint(HINT_LIVE)
        hud.setStatus("counting...", HudView.Tone.COUNTING)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.active = torchOn
    }

    private fun resetSize() {
        refFrac = 0f
        btnAuto.active = false
        if (frozen) reanalyseFrozen()
    }

    private fun reanalyseFrozen() {
        val frac = refFrac
        exec.execute {
            val c = snapCounter
            if (c != null) {
                val res = c.analyze(frac)
                ui.post {
                    if (frozen) {
                        lastAuto = res.autoSize
                        overlay.showFrozen(res.blobs, res.gw, res.gh)
                        updateFrozenHud()
                    }
                }
            }
        }
    }

    private fun focusAt(vx: Float, vy: Float) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(vx, vy)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
            overlay.pulseFocus(vx, vy)
        } catch (e: Exception) {
            // focus is a nicety; never crash for it
        }
    }

    // ------------------------------------------------------------------ touches on the overlay
    override fun onTap(gx: Float, gy: Float, vx: Float, vy: Float) {
        if (frozen) {
            if (!overlay.removeAt(gx, gy)) overlay.addManual(gx, gy)
            hud.setCount(overlay.count())
        } else {
            focusAt(vx, vy)
        }
    }

    override fun onHold(gx: Float, gy: Float) {
        val area = overlay.areaAt(gx, gy)
        if (area <= 0f) return
        val counter = (if (frozen) snapCounter else liveCounter) ?: return
        refFrac = area / (counter.w * counter.h)
        btnAuto.active = true
        btnAuto.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (frozen) reanalyseFrozen()
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val LIVE_W = 240
        private const val SNAP_W = 480
        private const val MIN_INTERVAL_MS = 60L
        private const val HINT_LIVE = "tap to focus   -   hold an object to set its size"
        private const val HINT_FROZEN = "tap to add or remove   -   hold an object to set its size"
    }
}
