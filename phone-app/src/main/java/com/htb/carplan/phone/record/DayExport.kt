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
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.io.File

/** One row of the exported day image: a planned stop or an unplanned record. */
data class ExportItem(val time: String?, val title: String, val line: String, val photoUrl: String?, val unplanned: Boolean, val photoUrls: List<String> = listOfNotNull(photoUrl))

/**
 * Q39: the whole day as one tall image — title, then each stop with its first photo, place,
 * time and one line — saved to the cache and handed to the share sheet.
 */
object DayExport {
    private const val W = 1080
    private const val PAD = 56
    private const val PHOTO = 220

    suspend fun share(context: Context, title: String, subtitle: String, items: List<ExportItem>) {
        val loader = ImageLoader(context)
        val photos = items.map { it.photoUrl?.let { url -> load(loader, context, url) } }
        val rowH = PHOTO + 40
        val h = 260 + items.size * rowH + 80
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A9096") }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0066B3") }
        val line = Paint().apply { color = Color.parseColor("#E4E6E9"); strokeWidth = 3f }
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B9BEC4"); strokeWidth = 6f }

        ink.textSize = 56f; ink.typeface = Typeface.DEFAULT_BOLD
        c.drawText(title, PAD.toFloat(), 120f, ink)
        soft.textSize = 32f
        c.drawText(subtitle, PAD.toFloat(), 172f, soft)
        c.drawLine(PAD.toFloat(), 210f, (W - PAD).toFloat(), 210f, line)

        var y = 260
        val railX = PAD + 20f
        items.forEachIndexed { i, item ->
            val top = y.toFloat()
            if (i < items.lastIndex) c.drawLine(railX, top + 30, railX, top + rowH, rail)
            val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (item.unplanned) Color.parseColor("#8A9096") else Color.parseColor("#0066B3") }
            c.drawCircle(railX, top + 30, 14f, node)
            val textX = railX + 50f
            val photoX = W - PAD - PHOTO
            photos[i]?.let { p ->
                val src = centerCrop(p)
                c.drawBitmap(p, src, RectF(photoX.toFloat(), top, (photoX + PHOTO).toFloat(), top + PHOTO), null)
            } ?: run {
                val ph = Paint().apply { color = Color.parseColor("#F0F2F5") }
                c.drawRect(photoX.toFloat(), top, (photoX + PHOTO).toFloat(), top + PHOTO, ph)
            }
            ink.textSize = 40f; ink.typeface = Typeface.DEFAULT_BOLD
            c.drawText(item.title, textX, top + 44, ink)
            accent.textSize = 30f
            val timeText = listOfNotNull(item.time, if (item.unplanned) "계획 밖" else null).joinToString("  ·  ")
            if (timeText.isNotEmpty()) c.drawText(timeText, textX, top + 92, accent)
            soft.textSize = 30f
            c.drawText(item.line.take(28), textX, top + 140, soft)
            y += rowH
        }
        soft.textSize = 26f
        c.drawText("carplan · 캘린더가 차를 움직인다", PAD.toFloat(), (h - 30).toFloat(), soft)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "carplan_${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "com.htb.carplan.phone.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "하루 기록 보내기"))
    }

    private suspend fun load(loader: ImageLoader, context: Context, url: String): Bitmap? =
        (loader.execute(ImageRequest.Builder(context).data(url).build()) as? SuccessResult)?.image?.toBitmap()

    private fun centerCrop(b: Bitmap): Rect {
        val s = minOf(b.width, b.height)
        val l = (b.width - s) / 2
        val t = (b.height - s) / 2
        return Rect(l, t, l + s, t + s)
    }
}
