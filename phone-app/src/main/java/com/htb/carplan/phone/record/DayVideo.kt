package com.htb.carplan.phone.record

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import android.util.Log

/**
 * 14장: the day as a short vertical video — a title card, then every photo of every stop as a slide
 * with place · time · one line underneath (2.5 s each), H.264 mp4 built with MediaCodec + MediaMuxer,
 * handed to the share sheet. Pure CPU path (bitmap → YUV420 input buffers) so it runs on any device.
 */
object DayVideo {
    private const val TAG = "DayVideo"
    private const val W = 720
    private const val H = 1280
    private const val FPS = 30
    private const val SLIDE_S = 2.5
    private const val TITLE_S = 2.0
    private const val PHOTO_H = 900

    suspend fun share(context: Context, title: String, subtitle: String, items: List<ExportItem>, onProgress: (String) -> Unit = {}) {
        val loader = ImageLoader(context)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "carplan_${System.currentTimeMillis()}.mp4")
        withContext(Dispatchers.Default) {
            val slides = ArrayList<Pair<Bitmap, Double>>()
            slides += titleCard(title, subtitle) to TITLE_S
            val total = items.sumOf { maxOf(1, it.photoUrls.size) }
            var n = 0
            items.forEach { item ->
                val urls = item.photoUrls.ifEmpty { listOf(null) }
                urls.forEach { url ->
                    n++; onProgress("사진 ${n}/${total} 준비 중")
                    val photo = url?.let { runCatching { load(loader, context, it) }.getOrNull() }
                    slides += slide(photo, item) to SLIDE_S
                }
            }
            onProgress("영상 만드는 중")
            encode(file, slides, onProgress)
        }
        val uri = FileProvider.getUriForFile(context, "com.htb.carplan.phone.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "하루 영상 보내기"))
    }

    // ---------- drawing ----------

    private fun paint(color: String, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor(color); textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun titleCard(title: String, subtitle: String): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        val ink = paint("#111111", 64f, true); val soft = paint("#8A9096", 34f)
        drawWrapped(c, title, 60f, H / 2f - 60, W - 120, ink, 76f)
        c.drawText(subtitle, 60f, H / 2f + 60, soft)
        c.drawText("carplan · 캘린더가 차를 움직인다", 60f, H - 80f, paint("#8A9096", 26f))
        return b
    }

    private fun slide(photo: Bitmap?, item: ExportItem): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        if (photo != null) {
            val s = minOf(photo.width / W.toFloat(), photo.height / PHOTO_H.toFloat())
            val cw = (W * s).toInt(); val ch = (PHOTO_H * s).toInt()
            val l = (photo.width - cw) / 2; val t = (photo.height - ch) / 2
            c.drawBitmap(photo, Rect(l, t, l + cw, t + ch), RectF(0f, 0f, W.toFloat(), PHOTO_H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawRect(0f, 0f, W.toFloat(), PHOTO_H.toFloat(), Paint().apply { color = Color.parseColor("#F0F2F5") })
            c.drawText("사진 없음", 60f, PHOTO_H / 2f, paint("#8A9096", 36f))
        }
        val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(if (item.unplanned) "#8A9096" else "#0066B3") }
        c.drawCircle(60f, PHOTO_H + 70f, 12f, node)
        c.drawText(item.title.take(18), 96f, PHOTO_H + 84f, paint("#111111", 44f, true))
        val meta = listOfNotNull(item.time, if (item.unplanned) "계획 밖" else null).joinToString("  ·  ")
        if (meta.isNotEmpty()) c.drawText(meta, 96f, PHOTO_H + 138f, paint("#0066B3", 30f))
        drawWrapped(c, item.line, 96f, PHOTO_H + 196f, W - 156, paint("#8A9096", 30f), 40f, maxLines = 3)
        return b
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, y: Float, width: Int, p: Paint, lineH: Float, maxLines: Int = 4) {
        var line = ""; var yy = y; var lines = 0
        for (ch in text) {
            if (p.measureText(line + ch) > width) {
                c.drawText(line, x, yy, p); yy += lineH; line = ""; lines++
                if (lines >= maxLines) return
            }
            line += ch
        }
        if (line.isNotEmpty()) c.drawText(line, x, yy, p)
    }

    private suspend fun load(loader: ImageLoader, context: Context, url: String): Bitmap? =
        (loader.execute(ImageRequest.Builder(context).data(url).build()) as? SuccessResult)?.image?.toBitmap()

    // ---------- encoding ----------

    private fun encode(file: File, slides: List<Pair<Bitmap, Double>>, onProgress: (String) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val frameUs = 1_000_000L / FPS
        var frame = 0L
        val totalFrames = slides.sumOf { (it.second * FPS).toLong() }

        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); Log.d(TAG, "muxer started") }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    !end -> return
                }
            }
        }

        try {
            slides.forEachIndexed { si, (bmp, seconds) ->
                val yuv = toYuv(bmp)
                repeat((seconds * FPS).toInt()) {
                    var idx: Int
                    var waits = 0
                    while (true) { idx = codec.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false); if (++waits % 100 == 0) Log.w(TAG, "frame $frame: still waiting for an input buffer ($waits)") }
                    val img = codec.getInputImage(idx) ?: error("encoder gave no input image")
                    fill(img.planes[0], yuv.y, W, H); fill(img.planes[1], yuv.u, W / 2, H / 2); fill(img.planes[2], yuv.v, W / 2, H / 2)
                    img.close()
                    codec.queueInputBuffer(idx, 0, W * H * 3 / 2, frame * frameUs, 0)
                    frame++
                    if (frame <= 3 || frame % 30 == 0L) Log.d(TAG, "queued frame $frame/$totalFrames (slide $si)")
                    if (frame % 15 == 0L) onProgress("영상 만드는 중 ${frame * 100 / totalFrames}%")
                    drain(false)
                }
                bmp.recycle()
            }
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, frame * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { muxer.stop() }; muxer.release()
        }
    }

    private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    /** ARGB bitmap → I420 planes (BT.601 limited range), chroma averaged over 2×2. */
    private fun toYuv(b: Bitmap): Yuv {
        val px = IntArray(W * H); b.getPixels(px, 0, W, 0, 0, W, H)
        val y = ByteArray(W * H); val u = ByteArray(W * H / 4); val v = ByteArray(W * H / 4)
        for (j in 0 until H) for (i in 0 until W) {
            val p = px[j * W + i]; val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val bl = p and 0xFF
            y[j * W + i] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).coerceIn(16, 235).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (W / 2) + i / 2
                u[k] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                v[k] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            }
        }
        return Yuv(y, u, v)
    }

    private fun fill(plane: android.media.Image.Plane, src: ByteArray, w: Int, h: Int) {
        val buf: ByteBuffer = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        if (ps == 1) {
            for (row in 0 until h) { buf.position(row * rs); buf.put(src, row * w, w) }
        } else {
            for (row in 0 until h) for (col in 0 until w) buf.put(row * rs + col * ps, src[row * w + col])
        }
    }
}
