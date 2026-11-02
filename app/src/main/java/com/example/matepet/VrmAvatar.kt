package com.example.matepet

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.View as FView
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders a .vrm (VRM 0.x or 1.0 = glTF binary) with Filament, blinks, and moves the mouth while temi talks.
 * MToon shading is not supported: materials render as standard glTF PBR/unlit.
 *
 * Built to float over other apps: transparent background, fixed low-resolution surface (the compositor scales it up),
 * Filament's costly default effects switched off, and rendering only when something actually changed.
 */
class VrmAvatar(ctx: Context, file: File, sizePx: Int, renderScale: Float, transparent: Boolean) :
    SurfaceView(ctx), Choreographer.FrameCallback, Talker {
    @Volatile override var talking = false
    private class Bind(val key: String, val entity: Int, val idx: Int, val w: Float)
    private class Slot(val entity: Int, val binds: List<Bind>) { var weights: FloatArray? = null }
    private val mv = ModelViewer(this)
    private var slots: List<Slot> = emptyList()
    private var running = false
    private var lastBlink = -1f
    private var lastMouth = -1f
    private var lastRender = 0L
    private var frames = 0
    private var fpsSince = 0L

    init {
        // memory-mapped: a big .vrm never has to fit in the Java heap
        val buf = java.io.RandomAccessFile(file, "r").use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val jsonBuf = buf.duplicate()

        // Surface: lower resolution than the window = far fewer pixels to shade. Must be set before the surface is created.
        val px = (sizePx * renderScale).toInt().coerceAtLeast(96)
        holder.setFixedSize(px, px)
        if (transparent) {
            holder.setFormat(PixelFormat.TRANSLUCENT)   // UiHelper set OPAQUE during ModelViewer(); override it
            setZOrderOnTop(true)
            // ModelViewer keeps its UiHelper private; tell it the swap chain is not opaque. Best effort, harmless if the field is not found.
            runCatching {
                for (f in mv.javaClass.declaredFields) if (UiHelper::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true; (f.get(mv) as UiHelper).isOpaque = false
                }
            }
            mv.scene.skybox = null
            mv.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = floatArrayOf(0f, 0f, 0f, 0f) }
        }
        mv.view.apply {
            blendMode = if (transparent) FView.BlendMode.TRANSLUCENT else FView.BlendMode.OPAQUE
            // ModelViewer turns all of these on by default; on temi they cost far more than they add for a small pet
            antiAliasing = FView.AntiAliasing.NONE
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = false }
            ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = false }
            bloomOptions = bloomOptions.apply { enabled = false }
            dynamicResolutionOptions = dynamicResolutionOptions.apply { enabled = false }
        }
        // shadow mapping off (looked up by name so a missing method can never break the build)
        runCatching { FView::class.java.getMethod("setShadowingEnabled", Boolean::class.javaPrimitiveType).invoke(mv.view, false) }

        mv.loadModelGlb(buf)
        mv.transformToUnitCube()
        frameUpperBody()
        addFrontLight()
        val json = glbJson(jsonBuf)
        slots = parseExpressions(json).groupBy { it.entity }.map { (e, b) -> Slot(e, b) }
        if (json.optJSONObject("extensions")?.has("VRM") == true) faceForward()  // VRM 0.x faces -Z
        Log.i("MatePet", "VRM loaded: ${file.length() / 1_000_000} MB, ${slots.size} morph meshes, render ${px}px transparent=$transparent")
    }

    private fun glbJson(b: ByteBuffer): JSONObject {
        b.order(ByteOrder.LITTLE_ENDIAN)
        val len = b.getInt(12); val a = ByteArray(len); b.position(20); b.get(a)
        return JSONObject(String(a, Charsets.UTF_8))
    }

    private fun parseExpressions(j: JSONObject): List<Bind> {
        val out = ArrayList<Bind>(); val nodes = j.optJSONArray("nodes") ?: return out
        val asset = mv.asset ?: return out
        fun ent(n: Int) = asset.getFirstEntityByName(nodes.getJSONObject(n).optString("name"))
        fun add(k: String, n: Int, i: Int, w: Float) { val e = ent(n); if (e != 0) out.add(Bind(k, e, i, w)) }
        val ext = j.optJSONObject("extensions")
        ext?.optJSONObject("VRM")?.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")?.let { g ->
            for (i in 0 until g.length()) {
                val o = g.getJSONObject(i)
                val key = when (o.optString("presetName").lowercase()) { "blink" -> "blink"; "a" -> "mouth"; else -> continue }
                val bs = o.optJSONArray("binds") ?: continue
                for (k in 0 until bs.length()) {
                    val bd = bs.getJSONObject(k)
                    for (n in 0 until nodes.length())
                        if (nodes.getJSONObject(n).optInt("mesh", -1) == bd.getInt("mesh"))
                            add(key, n, bd.getInt("index"), bd.optDouble("weight", 100.0).toFloat() / 100f)
                }
            }
        }
        ext?.optJSONObject("VRMC_vrm")?.optJSONObject("expressions")?.optJSONObject("preset")?.let { p ->
            for ((key, name) in listOf("blink" to "blink", "mouth" to "aa")) {
                val bs = p.optJSONObject(name)?.optJSONArray("morphTargetBinds") ?: continue
                for (k in 0 until bs.length()) {
                    val bd = bs.getJSONObject(k)
                    add(key, bd.getInt("node"), bd.getInt("index"), bd.optDouble("weight", 1.0).toFloat())
                }
            }
        }
        return out
    }

    /** The unit-cube fit shows the whole body small; zoom in on head and chest. Tune ZOOM / DOWN on the robot. */
    private fun frameUpperBody() {
        val ZOOM = 2.0f; val DOWN = 0.9f
        val tm = mv.engine.transformManager; val root = mv.asset?.root ?: return
        val inst = tm.getInstance(root); val m = FloatArray(16); tm.getTransform(inst, m)
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 14)) m[i] *= ZOOM
        m[13] -= DOWN; tm.setTransform(inst, m)
    }

    private fun faceForward() {   // rotate 180 deg about Y: negate the X and Z basis columns
        val tm = mv.engine.transformManager; val root = mv.asset?.root ?: return
        val inst = tm.getInstance(root); val m = FloatArray(16)
        tm.getTransform(inst, m)
        for (i in intArrayOf(0, 1, 2, 3, 8, 9, 10, 11)) m[i] = -m[i]
        tm.setTransform(inst, m)
    }

    /**
     * ModelViewer's only light shines straight down and there is no ambient light, so a model facing the camera
     * is lit almost edge-on and can come out nearly black. A second light from the camera side fixes that.
     * Raise or lower FRONT_LUX if the avatar looks too dark or washed out.
     */
    private fun addFrontLight() {
        val FRONT_LUX = 70_000f
        val e = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(FRONT_LUX).direction(0f, -0.25f, -1f).castShadows(false)
            .build(mv.engine, e)
        mv.scene.addEntity(e)
    }

    /** Returns true if a morph weight actually changed (so the frame has to be redrawn). Allocation-free after the first call. */
    private fun animate(t: Long): Boolean {
        val blink = if (t % 4000 < 150) 1f else 0f
        val mouth = if (talking) abs(sin(t / 90.0)).toFloat() * 0.8f else 0f
        if (blink == lastBlink && mouth == lastMouth) return false
        lastBlink = blink; lastMouth = mouth
        val rm = mv.engine.renderableManager
        for (s in slots) {
            val ri = rm.getInstance(s.entity); if (ri == 0) continue
            val w = s.weights ?: FloatArray(rm.getMorphTargetCount(ri)).also { s.weights = it }
            java.util.Arrays.fill(w, 0f)
            for (b in s.binds) if (b.idx < w.size) w[b.idx] = maxOf(w[b.idx], (if (b.key == "blink") blink else mouth) * b.w)
            rm.setMorphWeights(ri, w, 0)
        }
        return true
    }

    override fun doFrame(ns: Long) {
        if (!running) return
        val changed = animate(System.currentTimeMillis())
        val tick = SystemClock.uptimeMillis()
        // Idle pet = nothing to redraw. The SurfaceView keeps showing the last frame; refresh twice a second just in case.
        if (changed || tick - lastRender > 500) {
            mv.render(ns); lastRender = tick; frames++
            if (tick - fpsSince >= 1000) { PetStatus.fps = frames * 1000 / (tick - fpsSince).toInt().coerceAtLeast(1); frames = 0; fpsSince = tick }
        }
        Choreographer.getInstance().postFrameCallbackDelayed(this, if (talking) 30L else 60L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true; fpsSince = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false; PetStatus.fps = 0
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }
}
