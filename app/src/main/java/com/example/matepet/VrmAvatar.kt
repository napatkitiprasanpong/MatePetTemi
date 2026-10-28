package com.example.matepet

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceView
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
 */
class VrmAvatar(ctx: Context, file: File) : SurfaceView(ctx), Choreographer.FrameCallback, Talker {
    @Volatile override var talking = false
    private class Bind(val key: String, val entity: Int, val idx: Int, val w: Float)
    private val mv = ModelViewer(this)
    private var byEntity: Map<Int, List<Bind>> = emptyMap()
    private var running = false

    init {
        val bytes = file.readBytes()
        mv.loadModelGlb(ByteBuffer.wrap(bytes))
        mv.transformToUnitCube()
        val json = glbJson(ByteBuffer.wrap(bytes))
        val binds = parseExpressions(json)
        byEntity = binds.groupBy { it.entity }
        if (json.optJSONObject("extensions")?.has("VRM") == true) faceForward()  // VRM 0.x faces -Z
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

    private fun faceForward() {   // rotate 180 deg about Y: negate the X and Z basis columns
        val tm = mv.engine.transformManager; val root = mv.asset?.root ?: return
        val inst = tm.getInstance(root); val m = FloatArray(16)
        tm.getTransform(inst, m)
        for (i in intArrayOf(0, 1, 2, 3, 8, 9, 10, 11)) m[i] = -m[i]
        tm.setTransform(inst, m)
    }

    private fun animate(t: Long) {
        val blink = if (t % 4000 < 150) 1f else 0f
        val mouth = if (talking) abs(sin(t / 90.0)).toFloat() * 0.8f else 0f
        val rm = mv.engine.renderableManager
        for ((e, bs) in byEntity) {
            val ri = rm.getInstance(e); if (ri == 0) continue
            val w = FloatArray(rm.getMorphTargetCount(ri))
            for (b in bs) if (b.idx < w.size) w[b.idx] = maxOf(w[b.idx], (if (b.key == "blink") blink else mouth) * b.w)
            rm.setMorphWeights(ri, w, 0)
        }
    }

    override fun doFrame(ns: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        animate(System.currentTimeMillis()); mv.render(ns)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; Choreographer.getInstance().postFrameCallback(this) }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
}
