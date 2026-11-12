package com.vanmypq.autoanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

// Giữ quyền chụp màn hình (MediaProjection) để gửi ảnh cho AI đọc công thức/hình vẽ.
class CaptureService : Service() {
    companion object {
        @Volatile private var reader: ImageReader? = null
        @Volatile private var cached: String? = null
        @Volatile private var cachedTs = 0L
        @Volatile private var cachedSide = -1
        @Volatile private var latest: android.media.Image? = null
        @Volatile private var frameTs = 0L
        private val lock = Any()
        @Volatile private var w = 0
        @Volatile private var h = 0
        @Volatile var lastError: String = ""
        // true khi ảnh chụp gần như đen hoàn toàn (ứng dụng chặn chụp màn hình - FLAG_SECURE).
        @Volatile var blackFrame: Boolean = false

        fun isRunning(): Boolean = reader != null

        // Luôn giữ lại KHUNG HÌNH MỚI NHẤT mà hệ thống gửi tới, để lúc cần chụp là có đúng màn hình hiện tại
        // (trước đây nếu màn hình đứng yên có thể trả về ảnh cũ).
        private fun keepLatest(r: ImageReader) {
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
            synchronized(lock) {
                try { latest?.close() } catch (e: Exception) { }
                latest = img
                frameTs = android.os.SystemClock.uptimeMillis()
            }
        }

        private fun isBlack(b: Bitmap): Boolean {
            val sx = maxOf(1, b.width / 24)
            val sy = maxOf(1, b.height / 48)
            var y = (b.height * 0.08f).toInt()
            val yEnd = (b.height * 0.92f).toInt()
            while (y < yEnd) {
                var x = 0
                while (x < b.width) {
                    val c = b.getPixel(x, y)
                    val lum = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3
                    if (lum > 12) return false
                    x += sx
                }
                y += sy
            }
            return true
        }

        // Trả về ảnh màn hình hiện tại dạng JPEG base64, hoặc null nếu chưa cấp quyền / ảnh bị đen (app chặn chụp).
        fun grab(maxSide: Int = 0): String? {
            if (reader == null) return null
            return synchronized(lock) {
                val image = latest
                if (image != null && (frameTs != cachedTs || cachedSide != maxSide)) {
                    try {
                        val plane = image.planes[0]
                        plane.buffer.rewind()
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                        blackFrame = isBlack(cropped)
                        if (blackFrame) {
                            cached = null
                        } else {
                            var out = cropped
                            if (maxSide > 0 && maxOf(w, h) > maxSide) {
                                val sc = maxSide.toFloat() / maxOf(w, h)
                                out = Bitmap.createScaledBitmap(cropped, (w * sc).toInt(), (h * sc).toInt(), true)
                            }
                            val bos = ByteArrayOutputStream()
                            out.compress(Bitmap.CompressFormat.JPEG, if (maxSide in 1..1100) 55 else if (maxSide > 0) 62 else 72, bos)
                            cached = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                        cachedTs = frameTs
                        cachedSide = maxSide
                    } catch (e: Exception) {
                    }
                }
                cached
            }
        }

        // Trạng thái ô tròn/ô vuông tại (xu, yu) (0..1000 theo ảnh), kiểm tra bằng điểm ảnh thật (đáng tin hơn nhờ AI nhận xét):
        // 0 = chưa chọn (viền xám, bên trong trống), 1 = đã chọn (chấm màu xanh/tối), 2 = đã chọn nhưng bị chấm SAI (chấm màu đỏ).
        // Trả null nếu chưa có khung hình.
        fun selectionAt(xu: Int, yu: Int): Int? {
            if (reader == null) return null
            return synchronized(lock) {
                val image = latest ?: return@synchronized null
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    val ps = plane.pixelStride
                    val cx = (xu * w / 1000).coerceIn(2, w - 3)
                    var best = 0
                    for (dyu in intArrayOf(-8, -4, 0, 4, 8)) {
                        val cy = ((yu + dyu) * h / 1000).coerceIn(2, h - 3)
                        var r = 0
                        var g = 0
                        var b = 0
                        var n = 0
                        for (dy in -2..2 step 2) {
                            for (dx in -2..2 step 2) {
                                val o = (cy + dy) * rs + (cx + dx) * ps
                                r += buf.get(o).toInt() and 0xff
                                g += buf.get(o + 1).toInt() and 0xff
                                b += buf.get(o + 2).toInt() and 0xff
                                n++
                            }
                        }
                        r /= n; g /= n; b /= n
                        val lum = (r + g + b) / 3
                        val sat = maxOf(r, g, b) - minOf(r, g, b)
                        if (r > 150 && r - g > 70 && r - b > 70) { best = 2; break }
                        if ((sat > 40 && lum < 225) || lum < 100) best = maxOf(best, 1)
                    }
                    best
                } catch (e: Exception) {
                    null
                }
            }
        }

        fun isSelectedAt(xu: Int, yu: Int): Boolean? = selectionAt(xu, yu)?.let { it != 0 }

        @Volatile private var snapTs = 0L
        @Volatile private var snapCache: IntArray? = null

        // Bản "dấu vân tay" rất nhỏ của màn hình hiện tại (lưới 24x48 ô, độ sáng trung bình), dùng để biết màn hình
        // có thật sự đổi (đã cuộn / sang câu mới) hay chưa ngay cả khi cây giao diện không đổi (trang web).
        fun snapshot(): IntArray? {
            if (reader == null) return null
            return synchronized(lock) {
                val image = latest ?: return@synchronized null
                if (frameTs == snapTs && snapCache != null) return@synchronized snapCache
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    val ps = plane.pixelStride
                    val gx = 24
                    val gy = 48
                    val out = IntArray(gx * gy)
                    for (cy in 2 until gy - 2) {          // bỏ thanh trạng thái (đồng hồ) và thanh điều hướng
                        for (cx in 0 until gx) {
                            var sum = 0
                            for (k in 0 until 4) {
                                val px = ((cx + 0.25f + 0.5f * (k % 2)) * w / gx).toInt().coerceIn(0, w - 1)
                                val py = ((cy + 0.25f + 0.5f * (k / 2)) * h / gy).toInt().coerceIn(0, h - 1)
                                val o = py * rs + px * ps
                                sum += ((buf.get(o).toInt() and 0xff) + (buf.get(o + 1).toInt() and 0xff) + (buf.get(o + 2).toInt() and 0xff)) / 3
                            }
                            out[cy * gx + cx] = sum / 4
                        }
                    }
                    snapCache = out
                    snapTs = frameTs
                    out
                } catch (e: Exception) {
                    null
                }
            }
        }

        // true nếu hai màn hình khác nhau rõ rệt (hơn ~6% số ô đổi độ sáng đáng kể); thay đổi nhỏ (con trỏ, ô tích) không tính.
        fun differs(a: IntArray, b: IntArray): Boolean {
            if (a.size != b.size) return true
            var n = 0
            for (i in a.indices) if (Math.abs(a[i] - b[i]) > 20) n++
            return n > 63
        }

        internal fun clearFrames() {
            synchronized(lock) {
                try { latest?.close() } catch (e: Exception) { }
                latest = null
                cached = null
                cachedTs = 0L
                cachedSide = -1
                snapTs = 0L
                snapCache = null
                blackFrame = false
            }
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            lastError = ""
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("cap", "Chụp màn hình", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, "cap")
                .setContentTitle("Auto Trắc Nghiệm")
                .setContentText("Đang cho phép chụp màn hình để AI đọc công thức")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val code = intent?.getIntExtra("code", 0) ?: 0
            val data: Intent? = intent?.getParcelableExtra("data")
            if (data == null) {
                lastError = "Không nhận được dữ liệu cấp quyền từ hệ thống"
                release()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, Handler(Looper.getMainLooper()))

            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(real)
            val scale = minOf(1f, 1600f / maxOf(real.widthPixels, real.heightPixels))
            w = (real.widthPixels * scale).toInt()
            h = (real.heightPixels * scale).toInt()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ rd -> keepLatest(rd) }, Handler(Looper.getMainLooper()))
            display = mp.createVirtualDisplay(
                "autoanswer-cap", w, h, real.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            reader = r
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            release()
        }
        return START_NOT_STICKY
    }

    private fun release() {
        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        clearFrames()
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
        stopSelf()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
