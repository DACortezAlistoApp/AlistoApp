package ph.alisto.kernel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

object AlistoMediaCompressor {

    data class CompressedAsset(
        val dataUrl: String,
        val width: Int,
        val height: Int,
        val sizeKb: Int,
        val standardLabel: String
    )

    /**
     * Enforces Section 4.2 Client-Side Image & Document Compression Policy:
     * - PROFILE_VGA: 640x480 px, JPEG 68% quality (< 80 KB)
     * - COMPLIANCE_1024: Max 1024 px long edge, JPEG 65% quality (120 KB - 250 KB)
     */
    fun compressBitmapToDataUrl(source: Bitmap, isProfilePhoto: Boolean): CompressedAsset {
        val maxW = if (isProfilePhoto) 640 else 1024
        val maxH = if (isProfilePhoto) 480 else 1024
        val quality = if (isProfilePhoto) 68 else 65

        val scale = min(1f, min(maxW.toFloat() / source.width, maxH.toFloat() / source.height))
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        val bos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val bytes = bos.toByteArray()
        val kb = (bytes.size / 1024).coerceAtLeast(1)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val stdLabel = if (isProfilePhoto) {
            "VGA ${targetW}x${targetH} (${kb} KB • JPEG 68%)"
        } else {
            "Doc ${targetW}x${targetH} (${kb} KB • JPEG 65%)"
        }

        return CompressedAsset(
            dataUrl = "data:image/jpeg;base64,$b64",
            width = targetW,
            height = targetH,
            sizeKb = kb,
            standardLabel = stdLabel
        )
    }

    /**
     * Generates a bandwidth-optimized test credential or profile snapshot bitmap and compresses it
     * via the exact VGA (640x480) or 1024px pipeline for immediate device testing.
     */
    fun generateCompressedCredentialStamp(
        title: String,
        holderName: String,
        isProfilePhoto: Boolean
    ): CompressedAsset {
        val w = if (isProfilePhoto) 640 else 1024
        val h = if (isProfilePhoto) 480 else 640
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F172A"))

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isProfilePhoto) "#00F2FE" else "#34D399")
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRect(12f, 12f, w - 12f, h - 12f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBBF24")
            textSize = if (isProfilePhoto) 28f else 36f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (isProfilePhoto) 22f else 28f
        }

        canvas.drawText("ALISTO NETWORK PH • VERIFIED ASSET", 48f, 80f, titlePaint)
        canvas.drawText("Asset: $title", 48f, 150f, subPaint)
        canvas.drawText("Holder: $holderName", 48f, 210f, subPaint)
        canvas.drawText(
            if (isProfilePhoto) "Standard: VGA 640x480 (<80 KB)" else "Standard: 1024px Compliance Scan",
            48f,
            270f,
            subPaint
        )

        return compressBitmapToDataUrl(bmp, isProfilePhoto)
    }
}
