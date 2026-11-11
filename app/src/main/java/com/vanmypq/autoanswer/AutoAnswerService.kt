package com.vanmypq.autoanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs

class AutoAnswerService : AccessibilityService() {
    data class Item(
        val text: String,
        val rect: Rect,
        val node: AccessibilityNodeInfo?,
        val ctrl: Boolean = false,
        val checked: Boolean = false,
        val input: Boolean = false,
        val maxLen: Int = 0
    )

    // Một hành động: chạm vào đáp án (text == null) hoặc gõ chữ vào ô nhập (text != null).
    data class Act(val item: Item, val occ: Int, val text: String? = null)

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var button: TextView? = null
    private var auto = false
    private var busy = false
    private var lastHash = 0
    private var currentHash = 0
    private var layoutHash = 0
    private var retries = 0
    private var cooldownUntil = 0L
    private var status = ""
    private var warnedNoOcr = false
    private var lastSent = ""
    private val answerCache = HashMap<Int, List<Pair<String, Int>>>()
    private val slowHashes = HashSet<Int>()

    // Nhớ đáp án lâu dài trên máy (còn sau khi đóng app), để lần ôn lại cùng đề không cần hỏi AI nữa.
    private fun cachePrefs() = getSharedPreferences("answer-cache", MODE_PRIVATE)

    private fun loadCache(h: Int): List<Pair<String, Int>>? {
        val raw = cachePrefs().getString(h.toString(), null) ?: return null
        return try {
            val ja = JSONArray(raw)
            (0 until ja.length()).map { i ->
                val o = ja.getJSONObject(i)
                Pair(o.getString("t"), o.getInt("o"))
            }
        } catch (e: Exception) { null }
    }

    private fun saveCache(h: Int, list: List<Pair<String, Int>>) {
        val ja = JSONArray()
        for ((t, o) in list) ja.put(org.json.JSONObject().put("t", t).put("o", o))
        val p = cachePrefs()
        if (p.all.size > 500) p.edit().clear().apply()  // tránh phình to vô hạn qua nhiều đề khác nhau
        p.edit().putString(h.toString(), ja.toString()).apply()
    }
    private var tapping = false
    private var tapStart = 0L
    private var staleCount = 0
    private val clockRe = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")


    // ===== CUỘN TRANG / QUA TRANG (v1.9) =====
    private val settleMs = 600L
    private var lastActivity = 0L
    private var navCooldown = 0L
    private var navBusy = false
    private var navStart = 0L
    private var bottomReached = false
    private var scrollStuck = 0
    private var navFromSig = 0
    private var navSame = 0
    private var swipeFromSig = 0
    private var finished = false
    private val navLog = ArrayList<String>()
    private fun navNote(m: String) { navLog.add(m); if (navLog.size > 12) navLog.removeAt(0) }

    private val nextRe = Regex(
        "(?i)^\\s*(›|»|>|>>|→|tiếp( theo| tục)?|câu (tiếp( theo)?|sau)|trang (tiếp( theo)?|sau)|bài (tiếp( theo)?|sau)|" +
            "next( page| question)?|continue|sau|chuyển câu|qua câu)\\s*(›|»|>|→)?\\s*$"
    )
    private val prevRe = Regex(
        "(?i)^\\s*(‹|«|<|<<|←|trước|câu trước|trang trước|bài trước|previous( page| question)?|prev|back|quay lại)\\s*$"
    )
    private val submitRe = Regex(
        "(?i)^\\s*(nộp bài|hoàn thành|hoàn tất|kết thúc|gửi bài|submit|finish|done)\\s*$"
    )

    private fun cfg() = getSharedPreferences("cfg", MODE_PRIVATE)
    private fun markActive() { lastActivity = SystemClock.uptimeMillis() }
    private fun shortToast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // 1 = đã cuộn; 0 = có vùng cuộn nhưng không cuộn thêm được (cuối/đầu trang); -1 = không thấy vùng cuộn nào.
    private fun scrollByAction(down: Boolean): Int {
        val root = rootInActiveWindow ?: return -1
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val cands = ArrayList<Pair<Long, AccessibilityNodeInfo>>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || !n.isVisibleToUser) return
            if (n.isScrollable) {
                val r = Rect(); n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height()
                if (area >= screenArea * 0.12) cands.add(Pair(area, n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        cands.sortByDescending { it.first }
        var tried = false
        val wantId = if (down) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                     else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        val fwdId = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for ((_, n) in cands) {
            val cn = n.className?.toString() ?: ""
            if (cn.contains("Horizontal") || cn.contains("ViewPager")) continue
            tried = true
            n.refresh()
            val ids = n.actionList.map { it.id }
            if (wantId in ids && n.performAction(wantId)) return 1
            if (fwdId in ids && n.performAction(fwdId)) return 1
        }
        return if (tried) 0 else -1
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, onDone: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    private fun swipeScroll(down: Boolean, xFrac: Float, onDone: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * xFrac
        val a = dm.heightPixels * 0.75f
        val b = dm.heightPixels * 0.30f
        if (down) swipe(x, a, x, b, 300, onDone) else swipe(x, b, x, a, 300, onDone)
    }

    private fun swipePage(forward: Boolean, onDone: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.5f
        val a = dm.widthPixels * 0.85f
        val b = dm.widthPixels * 0.15f
        if (forward) swipe(a, y, b, y, 250, onDone) else swipe(b, y, a, y, 250, onDone)
    }

    // Cuộn một đoạn rồi báo kết quả: 1 = nội dung đã đổi (cuộn được), 0 = cả 3 cách đều không đổi.
    // Thử lần lượt: (1) lệnh cuộn của Trợ năng, (2) vuốt giữa màn hình, (3) vuốt lệch bên.
    // Chỉ coi là "đã hết" khi KHÔNG cách nào làm nội dung đổi (không tin vào việc lệnh trả về true).
    private fun doScroll(down: Boolean, onResult: (Int) -> Unit) {
        val before = layoutOf(collect())
        fun changed() = layoutOf(collect()) != before
        fun step3() {
            swipeScroll(down, 0.25f) { ok ->
                handler.postDelayed({
                    val c = changed()
                    navNote("vuốt lệch: gesture=$ok đổi=$c")
                    onResult(if (c) 1 else 0)
                }, 500)
            }
        }
        fun step2() {
            swipeScroll(down, 0.5f) { ok ->
                handler.postDelayed({
                    val c = changed()
                    navNote("vuốt giữa: gesture=$ok đổi=$c")
                    if (c) onResult(1) else step3()
                }, 500)
            }
        }
        val r = scrollByAction(down)
        if (r == 1) {
            handler.postDelayed({
                val c = changed()
                navNote("lệnh cuộn: đổi=$c")
                if (c) onResult(1) else step2()
            }, 400)
        } else {
            navNote("lệnh cuộn: không dùng được (mã $r)")
            step2()
        }
    }

    private fun navEnabled(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        var cur: AccessibilityNodeInfo? = n
        var d = 0
        while (cur != null && d < 5) {
            if (cur.isClickable) return cur.isEnabled
            cur = cur.parent
            d++
        }
        return n.isEnabled
    }

    private fun findNavButton(items: List<Item>, re: Regex): Item? =
        items.filter { !it.input && !it.ctrl && it.text.length <= 25 && re.containsMatchIn(it.text.trim()) }
            .filter { it.node == null || navEnabled(it.node) }
            .maxByOrNull { it.rect.top }

    private fun clickAncestor(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        var cur: AccessibilityNodeInfo? = n
        var d = 0
        while (cur != null && d < 5) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            d++
        }
        return false
    }

    private fun pressItem(it: Item, onDone: () -> Unit) {
        val n = it.node
        if (n != null && clickAncestor(n)) { onDone(); return }
        tap(it.rect) { ok ->
            if (!ok) clickNode(it.node)
            onDone()
        }
    }

    private fun afterNav() {
        handler.postDelayed({
            navBusy = false
            bottomReached = false
            scrollStuck = 0
            markActive()
            navCooldown = SystemClock.uptimeMillis() + 400
        }, 350)
    }

    private fun finishRun(msg: String) {
        navNote("KẾT THÚC: $msg")
        finished = true
        status = "✔ $msg"
        refresh()
        toast(msg)
    }

    // Không chạy ở màn hình của chính app hoặc thanh hệ thống (tránh cuộn/bấm nhầm khi đã thoát khỏi bài).
    private fun navAllowed(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return false
        return pkg != packageName && pkg != "com.android.systemui"
    }

    // Gọi khi màn hình đã xử lý xong và đứng yên: cuộn xuống tìm câu mới; hết trang thì bấm nút Tiếp theo.
    private fun maybeAdvance(items: List<Item>, h: Int) {
        if (navBusy || finished) return
        val p = cfg()
        val optScroll = p.getBoolean("scroll", true)
        val optNext = p.getBoolean("next", true)
        if (!optScroll && !optNext) return
        val now = SystemClock.uptimeMillis()
        if (now - lastActivity < settleMs || now < navCooldown) return
        if (!navAllowed()) return
        if (optScroll && !bottomReached) {
            navBusy = true; navStart = now
            setStatus("cuộn xuống tìm câu mới…")
            doScroll(true) { r ->
                navBusy = false
                markActive()
                if (r == 1) scrollStuck = 0
                else {
                    scrollStuck++
                    if (scrollStuck >= 2) bottomReached = true
                }
            }
            return
        }
        if (optNext) goNext(items, h) else finishRun("đã hết trang")
    }

    private fun goNext(items: List<Item>, h: Int) {
        val allowSubmit = cfg().getBoolean("submit", false)
        val btn = findNavButton(items, nextRe) ?: (if (allowSubmit) findNavButton(items, submitRe) else null)
        if (btn == null) {
            if (cfg().getBoolean("swipe", false) && h != swipeFromSig) {
                swipeFromSig = h
                navBusy = true; navStart = SystemClock.uptimeMillis()
                setStatus("vuốt sang trang…")
                swipePage(true) { afterNav() }
                return
            }
            finishRun("đã làm hết, không thấy nút Tiếp theo")
            return
        }
        if (h == navFromSig) navSame++ else { navFromSig = h; navSame = 0 }
        if (navSame >= 2) { finishRun("không qua được trang tiếp"); return }
        navBusy = true; navStart = SystemClock.uptimeMillis()
        setStatus("qua trang: " + btn.text.take(20))
        pressItem(btn) { afterNav() }
    }

    private val idleTicker = object : Runnable {
        override fun run() {
            if (!auto) refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!auto) return
            tick()
            handler.postDelayed(this, 150)
        }
    }

    private val eventTick = Runnable {
        tick()
        handler.postDelayed({ tick() }, 60)
    }

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            maxWidth = 700
            setOnClickListener { toggle() }
            setOnLongClickListener {
                val cm = this@AutoAnswerService.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-input", ClaudeClient.lastInfo + "\n\n" + lastSent + "\n\nNHẬT KÝ CUỘN:\n" + navLog.joinToString("\n")))
                toast("Đã sao chép dữ liệu app đọc được (dán vào chat để xem)")
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }
        wm.addView(tv, lp)
        button = tv
        refresh()
        handler.post(idleTicker)
    }

    private fun toggle() {
        auto = !auto
        lastHash = 0
        currentHash = 0
        layoutHash = 0
        retries = 0
        cooldownUntil = 0L
        tapping = false
        staleCount = 0
        finished = false
        bottomReached = false
        scrollStuck = 0
        navSame = 0
        navFromSig = 0
        swipeFromSig = 0
        navBusy = false
        navCooldown = 0L
        markActive()
        slowHashes.clear()
        status = if (auto) "đang quét" else ""
        refresh()
        handler.removeCallbacks(loop)
        if (auto) {
            handler.post(loop)
            val k = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
            if (k.isNotBlank()) exec.execute { ClaudeClient.warmUp(k) }
        }
    }

    private fun refresh() {
        button?.apply {
            text = if (auto) "■ AI: $status"
                   else if (CaptureService.isRunning()) "▶ AI 📷" else "▶ AI ⚠ chưa bật chụp màn hình"
            setBackgroundColor(if (auto) Color.parseColor("#CC2E7D32") else Color.parseColor("#CC424242"))
        }
    }

    private fun setStatus(s: String) {
        handler.post { status = s; refresh() }
    }

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun tick() {
        if (tapping && SystemClock.uptimeMillis() - tapStart > 4000) tapping = false  // chuỗi chạm bị kẹt
        if (navBusy && SystemClock.uptimeMillis() - navStart > 4000) navBusy = false  // cuộn/qua trang bị kẹt
        if (busy || tapping || navBusy || SystemClock.uptimeMillis() < cooldownUntil) return
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            toast("Chưa nhập API key"); auto = false; refresh(); return
        }
        val items = collect()
        if (items.sumOf { it.text.length } >= 40) {
            process(items, key)
        } else if (Build.VERSION.SDK_INT >= 30) {
            ocrThenProcess(key)
        } else if (!warnedNoOcr) {
            warnedNoOcr = true
            setStatus("không đọc được chữ trên màn hình này")
        }
    }

    // Gom toàn bộ chữ của một nút và các nút con (dùng cho công thức toán bị ẩn khỏi màn hình).
    private fun gather(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 12) return ""
        val sb = StringBuilder()
        val t = (n.text ?: n.contentDescription)?.toString()
        if (!t.isNullOrEmpty()) sb.append(t)
        for (i in 0 until n.childCount) n.getChild(i)?.let { sb.append(gather(it, depth + 1)) }
        return sb.toString()
    }

    private fun isChoiceNode(n: AccessibilityNodeInfo): Boolean {
        val cn = n.className?.toString() ?: ""
        return n.isCheckable || cn.contains("RadioButton") || cn.contains("CheckBox") || cn.contains("CheckedTextView")
    }

    // Icon trang trí (ghim, sao, menu, chia sẻ...) không phải đáp án: loại khỏi danh sách ô tích chọn
    // để khỏi bị gắn nhầm thành đáp án và làm AI rối, không nhận ra câu hỏi.
    private val decorIconRe = Regex(
        "(?i)ghim|pin|bookmark|đánh dấu|yêu thích|favorite|star|sao|cờ|flag|chia sẻ|share|" +
            "sao chép|copy|menu|cài đặt|setting|tìm kiếm|search|quay lại|back|đóng|close|thêm|add|xóa|delete|more"
    )

    // Nút tròn/ô vuông tự vẽ (ImageView bấm được, không có chữ) dùng làm ô tích chọn.
    private fun looksLikeIconButton(n: AccessibilityNodeInfo, r: Rect): Boolean {
        if (!n.isClickable || !n.isEnabled || n.childCount != 0) return false
        val desc = n.contentDescription?.toString() ?: ""
        if (desc.isNotEmpty() && decorIconRe.containsMatchIn(desc)) return false
        val d = resources.displayMetrics.density
        val w = r.width()
        val h = r.height()
        if (w < 18 * d || h < 18 * d || w > 90 * d || h > 90 * d) return false
        return abs(w - h) <= 0.35f * maxOf(w, h)
    }

    private val plainRe = Regex("\\p{L}{2,}\\s+\\p{L}{2,}\\s+\\p{L}{2,}")

    // Chữ thường bị trình duyệt ẩn (câu hỏi, tiêu đề) khác với công thức toán: không có ký hiệu toán, ít chữ số.
    private fun isPlainText(t: String): Boolean {
        if (!plainRe.containsMatchIn(t)) return false
        if (t.any { it in "^=+*/√²³≤≥<>|{}\\∑π∞" }) return false
        return t.count { it.isDigit() } <= 4
    }

    private val questionStartRe = Regex("(?i)^câu\\s*\\d+")

    private fun isQuestionLine(text: String): Boolean {
        val t = text.removePrefix("(chữ ẩn) ").removePrefix("(công thức) ").trim()
        return questionStartRe.containsMatchIn(t) || t.endsWith("?") || t.endsWith(":")
    }

    private fun countChoices(n: AccessibilityNodeInfo, depth: Int): Int {
        if (depth > 6) return 0
        var c = if (isChoiceNode(n)) 1 else 0
        for (i in 0 until n.childCount) n.getChild(i)?.let { c += countChoices(it, depth + 1) }
        return c
    }

    // Lấy chữ của phương án từ chính cây giao diện: nút "gán nhãn" (labeledBy) hoặc nút cha chỉ chứa DUY NHẤT ô này.
    private fun nodeLabel(n: AccessibilityNodeInfo): String {
        try {
            n.labeledBy?.let { l ->
                val t = gather(l, 0).trim()
                if (t.isNotEmpty()) return t.take(150)
            }
            val p = n.parent ?: return ""
            if (countChoices(p, 0) != 1) return ""
            val t = gather(p, 0).trim()
            return if (t.length in 1..300) t.take(150) else ""
        } catch (e: Exception) {
            return ""
        }
    }

    // Ô tích chọn không có chữ: lấy nhãn từ cây giao diện, nếu không có thì chữ cùng hàng (ưu tiên bên phải),
    // nếu vẫn không có thì đặt tên theo THỨ TỰ trong câu (A, B, C, D...) để AI nhìn ảnh rồi chọn đúng ô.
    private fun labelControls(list: ArrayList<Item>): List<Item> {
        var k = 0
        var prev: Item? = null
        for (i in list.indices) {
            val c = list[i]
            if (!c.ctrl) {
                if (!c.input && isQuestionLine(c.text)) k = 0
                continue
            }
            val pv = prev
            if (pv != null && c.rect.top - pv.rect.bottom > 2.5f * maxOf(c.rect.height(), 1)) k = 0
            k++
            prev = c
            if (c.text.isNotEmpty()) continue
            var label = c.node?.let { nodeLabel(it) } ?: ""
            if (label.isEmpty()) {
                var best: Item? = null
                var bestGap = Int.MAX_VALUE
                for (o in list) {
                    if (o === c || o.ctrl || o.text.isEmpty()) continue
                    // Bỏ khối chữ to bằng cả khung (tiêu đề/nút cha), chỉ nhận chữ cỡ một dòng.
                    if (o.rect.height() > 4 * maxOf(c.rect.height(), 60)) continue
                    val top = maxOf(o.rect.top, c.rect.top)
                    val bot = minOf(o.rect.bottom, c.rect.bottom)
                    if ((bot - top) < 0.5f * minOf(o.rect.height(), c.rect.height())) continue
                    val gap = if (o.rect.left >= c.rect.right - 10) o.rect.left - c.rect.right
                              else 100000 + maxOf(0, c.rect.left - o.rect.right)
                    if (gap < bestGap) { bestGap = gap; best = o }
                }
                label = best?.text?.take(150) ?: ""
            }
            val letter = if (k in 1..26) ('A' + (k - 1)).toString() else k.toString()
            list[i] = c.copy(text = if (label.isNotEmpty()) "(ô chọn) $label" else "(ô chọn $letter - chưa có chữ, xem ảnh)")
        }
        return list
    }

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int, parentVisible: Boolean, parentRect: Rect) {
            if (out.size >= 200 || depth > 40) return
            if (n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                val choice = isChoiceNode(n)
                val editable = n.isEditable || (n.className?.toString() ?: "").contains("EditText")
                if (!r.isEmpty) {
                    if (editable) {
                        // Chữ gợi ý (placeholder) giữ cố định để mã băm màn hình không đổi khi đã gõ.
                        val hint = n.hintText?.toString()?.trim() ?: ""
                        out.add(Item("(ô nhập)" + (if (hint.isNotEmpty()) " " + hint.take(40) else ""), r, n, false, false, true))
                    } else if (!t.isNullOrEmpty()) {
                        out.add(Item(t.take(1200), r, n, choice, choice && n.isChecked))
                    } else if (choice || looksLikeIconButton(n, r)) {
                        out.add(Item("", r, n, true, choice && n.isChecked))
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, true, r) }
            } else if (parentVisible) {
                // Nút ẩn nằm trong nút đang hiện (ví dụ công thức KaTeX/MathML): lấy chữ, chạm vào vùng của nút cha.
                val t = gather(n, 0).trim()
                if (t.isNotEmpty() && !parentRect.isEmpty) {
                    val own = Rect(); n.getBoundsInScreen(own)
                    val dm = resources.displayMetrics
                    var rect = Rect(parentRect)
                    if (!own.isEmpty) {
                        // Trình duyệt web liệt kê cả chữ ở phần chưa cuộn tới: bỏ qua, kẻo AI tưởng đó là câu đang hiện.
                        if (own.bottom <= 0 || own.top >= dm.heightPixels || own.right <= 0 || own.left >= dm.widthPixels) return
                        if (own.height() < dm.heightPixels * 0.6f) rect = own
                    }
                    out.add(Item((if (isPlainText(t)) "(chữ ẩn) " else "(công thức) ") + t.take(800), rect, n))
                }
            }
        }
        walk(root, 0, false, Rect())
        return attachInputLimits(labelControls(out))
    }

    // Tìm số đếm ký tự kiểu "0/100" nằm cùng hàng với ô nhập, để TỰ CẮT chữ đúng giới hạn
    // thay vì chỉ trông chờ AI tuân thủ theo lời dặn trong prompt.
    private val limitRe = Regex("^\\s*\\d+\\s*/\\s*(\\d+)\\s*$")

    private fun attachInputLimits(list: List<Item>): List<Item> {
        val out = ArrayList(list)
        for (i in out.indices) {
            val it = out[i]
            if (!it.input) continue
            var found = 0
            for (o in out) {
                if (o === it || o.input || o.ctrl) continue
                val m = limitRe.find(o.text) ?: continue
                val top = maxOf(o.rect.top, it.rect.top)
                val bot = minOf(o.rect.bottom, it.rect.bottom)
                if ((bot - top) < 0.3f * minOf(o.rect.height(), it.rect.height())) continue
                found = m.groupValues[1].toIntOrNull() ?: 0
                if (found > 0) break
            }
            if (found > 0) out[i] = it.copy(maxLen = found)
        }
        return out
    }

    @TargetApi(30)
    private fun ocrThenProcess(key: String) {
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (bmp == null) { busy = false; return }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { res ->
                        val list = res.textBlocks.flatMap { it.lines }
                            .mapNotNull { l -> l.boundingBox?.let { Item(l.text, it, null) } }
                        busy = false
                        process(list, key)
                    }
                    .addOnFailureListener { busy = false }
            }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun process(items: List<Item>, key: String) {
        if (items.isEmpty()) return
        val h = sig(items)
        if (h != currentHash) { currentHash = h; retries = 0; finished = false; markActive() }
        if (h == lastHash) { maybeAdvance(items, h); return }
        val lh = layoutOf(items)
        if (lh != layoutHash) {
            layoutHash = lh
            setStatus("chờ trang ổn định…")
            return
        }
        val cached = answerCache[h] ?: loadCache(h)?.also { answerCache[h] = it }
        if (cached != null) {
            val targets = ArrayList<Pair<Item, Int>>()
            for ((txt, occ) in cached) {
                val cand = items.filter { it.text == txt }
                if (cand.size > occ) targets.add(Pair(cand[occ], occ))
            }
            if (targets.isNotEmpty() && targets.size == cached.size) {
                lastHash = h
                status = "nhớ: ${targets.size} đáp án"
                refresh()
                chooseAll(targets.map { Act(it.first, it.second) }, 0)
                return
            }
        }
        val hasImg = CaptureService.isRunning()
        val text = buildPrompt(items)
        if (!hasImg && (text.contains("(công thức)") || text.contains("xem ảnh"))) {
            status = "⚠ CHƯA BẬT CHỤP MÀN HÌNH: mở app Auto Trắc Nghiệm > Cấp quyền chụp màn hình"
            refresh()
            cooldownUntil = SystemClock.uptimeMillis() + 3000
            return
        }
        lastHash = h
        busy = true
        val fast = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true)
        val hasInput = items.any { it.input }
        val longText0 = text.length > 900
        setStatus(if (hasInput) "AI đang điền…" else if (longText0) "câu dài, đọc kỹ…" else if (fast) "AI nhanh…" else "AI đang suy nghĩ…")
        lastSent = text
        exec.execute {
            var got = ClaudeClient.Answer(emptyList(), emptyMap())
            var gotErr: String? = null
            // Câu hỏi dài (đọc hiểu, tình huống dài...) cần đọc kỹ: tự chuyển chế độ chính xác dù đang bật NHANH.
            val effFast = fast && !slowHashes.contains(h) && !longText0
            try {
                got = ClaudeClient.ask(key, text, effFast, hasInput)
            } catch (e: Exception) {
                gotErr = e.message ?: "lỗi mạng"
            }
            val ans: ClaudeClient.Answer = got
            val err: String? = gotErr
            handler.post {
                busy = false
                markActive()
                if (err != null) {
                    lastHash = 0
                    val wait = if (err.contains("429")) 30000L else 6000L
                    cooldownUntil = SystemClock.uptimeMillis() + wait
                    status = "lỗi: " + err.take(80)
                    refresh()
                    toast(err)
                    return@post
                }
                // Trong lúc chờ AI, màn hình có thể đã sang câu khác: bỏ kết quả cũ và hỏi lại, tránh chạm nhầm.
                if (sig(collect()) != h && staleCount < 2) {
                    staleCount++
                    lastHash = 0
                    layoutHash = 0
                    status = "màn hình đã đổi, hỏi lại…"
                    refresh()
                    return@post
                }
                staleCount = 0
                val typed = ans.inputs.filter { (id, v) -> id >= 1 && id <= items.size && items[id - 1].input && v.isNotBlank() }
                // Câu điền chỗ trống: chỉ gõ chữ, không chạm lại đáp án của câu trắc nghiệm cũ còn hiện trên màn hình.
                val valid = if (typed.isNotEmpty()) emptyList()
                            else ans.ids.filter { it >= 1 && it <= items.size }.distinct().sorted()
                if (valid.isNotEmpty() || typed.isNotEmpty()) {
                    retries = 0
                    val acts = ArrayList<Act>()
                    for (id in (valid + typed.keys).distinct().sorted()) {
                        val t = items[id - 1].text
                        val occ = items.subList(0, id - 1).count { x -> x.text == t }
                        acts.add(Act(items[id - 1], occ, typed[id]?.take(100)))
                    }
                    status = if (typed.isNotEmpty()) "nhập: " + typed.values.first().take(25)
                             else "chọn ${valid.size}: " + items[valid[0] - 1].text.take(25)
                    refresh()
                    slowHashes.remove(h)
                    if (!hasInput && (hasImg || !text.contains("(công thức)"))) {
                        val list = acts.map { p -> Pair(p.item.text, p.occ) }
                        answerCache[h] = list
                        saveCache(h, list)
                    }
                    chooseAll(acts, 0)
                } else if (retries < 1) {
                    retries++
                    lastHash = 0
                    // Nhanh không ra kết quả: lần sau TỰ chuyển sang chế độ chính xác (có ảnh, suy nghĩ kỹ)
                    // cho đúng câu này, thay vì lặp lại y hệt lần thử trước.
                    if (effFast) slowHashes.add(h)
                    status = "thử lại, chế độ chính xác ($retries)"
                    refresh()
                } else {
                    slowHashes.remove(h)
                    status = "không thấy câu hỏi"
                    refresh()
                }
            }
        }
    }

    // Ghép các mảnh công thức rời rạc (mỗi ký tự một nút) thành một dòng "(công thức)" cho AI dễ đọc.
    // Ô tích chọn được đánh dấu ☐ (chưa chọn) / ☑ (đã chọn). Số thứ tự gốc được giữ để AI trả đúng số cần chạm.
    // Câu đánh số kiểu "Câu 8" / "8." mở đầu một câu hỏi mới: chèn vạch phân cách để AI không
    // lẫn phương án của câu này sang câu khác khi nhiều câu hiện cùng lúc trên một màn hình.
    private val headerRe = Regex("(?i)^(câu\\s*\\d+\\b|\\d{1,3}[.)])")

    private fun buildPrompt(items: List<Item>): String {
        val sb = StringBuilder()
        val run = ArrayList<Int>()
        val hasPlainTrueFalse = items.any { !it.ctrl && (it.text.trim() == "Đúng" || it.text.trim() == "Sai") }
        if (hasPlainTrueFalse) sb.append("(cần xem ảnh để biết Đúng/Sai nào đang được chọn)\n")
        fun flush() {
            if (run.isEmpty()) return
            val joined = run.joinToString("") { items[it].text }
            val formulaLike = joined.any { !it.isLetterOrDigit() || Character.isSurrogate(it) }
            if (run.size >= 3 && formulaLike) {
                sb.append("(công thức) ").append(joined).append('\n')
            } else {
                for (k in run) sb.append("[${k + 1}] ").append(items[k].text).append('\n')
            }
            run.clear()
        }
        for ((i, item) in items.withIndex()) {
            val t = item.text
            val isChoiceLetter = t.length == 1 && t[0] in 'A'..'D'
            val short = !item.ctrl && !item.input && t.length <= 2 && !isChoiceLetter && !t.startsWith("(")
            if (short) {
                run.add(i)
            } else {
                flush()
                // Chỉ chèn vạch khi đây là một dòng tiêu đề NGẮN đứng riêng (vd "8. Chọn đáp án đúng nhất"),
                // không chèn giữa đoạn hướng dẫn/giải thích dài lỡ bắt đầu bằng số thứ tự.
                val tt = t.removePrefix("(chữ ẩn) ").removePrefix("(công thức) ").trim()
                if (!item.ctrl && !item.input && sb.isNotEmpty() &&
                    ((tt.length <= 60 && headerRe.containsMatchIn(tt)) || questionStartRe.containsMatchIn(tt))) sb.append("---\n")
                if (item.ctrl) sb.append("[${i + 1}] ").append(if (item.checked) "☑ " else "☐ ").append(t).append('\n')
                else if (item.input) sb.append("[${i + 1}] ✎ ")
                    .append(if (item.maxLen > 0) "(tối đa ${item.maxLen} ký tự) " else "").append(t).append('\n')
                else sb.append("[${i + 1}] ").append(t).append('\n')
            }
        }
        flush()
        return sb.toString().trimEnd()
    }

    // Bỏ giờ dạng 08:58 khỏi mã băm, nếu không đồng hồ đếm ngược làm màn hình "luôn thay đổi".
    private fun key(t: String): String = t.replace(clockRe, "#")

    private fun sig(list: List<Item>): Int = list.joinToString("|") { key(it.text) }.hashCode()

    private fun layoutOf(list: List<Item>): Int =
        list.joinToString("|") { key(it.text) + it.rect.flattenToString() }.hashCode()

    // Lấy lại tọa độ mới nhất của đáp án (trang có thể đã dịch chuyển trong lúc chờ AI).
    // Lấy lại đáp án đúng ô: ưu tiên node cũ nếu nội dung còn khớp (tránh trường hợp view bị tái dùng
    // cho câu khác làm chạm nhầm); nếu không, tìm theo chữ TRÙNG VÀ GẦN VỊ TRÍ CŨ NHẤT thay vì đếm theo
    // thứ tự xuất hiện — vì màn hình có câu đã trả lời trước đó chèn thêm chữ (vd "Đáp án đúng là B")
    // làm lệch thứ tự đếm, dễ chạm sai sang đáp án của câu khác có cùng chữ (A/B/C/D...).
    private fun resolve(item: Item, occurrence: Int): Item {
        val n = item.node
        if (n != null && n.refresh()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val stillMatches = item.text.isEmpty() || (n.text ?: n.contentDescription)?.toString()?.trim() == item.text ||
                (item.ctrl && item.text.startsWith("(ô chọn") && abs(r.top - item.rect.top) < 200)
            if (!r.isEmpty && n.isVisibleToUser && stillMatches) return item.copy(rect = r)
        }
        val fresh = collect().filter { it.text == item.text }
        if (fresh.isEmpty()) return item
        val nearest = fresh.minByOrNull { abs(it.rect.top - item.rect.top) + abs(it.rect.left - item.rect.left) }
        return nearest ?: (if (fresh.size > occurrence) fresh[occurrence] else item)
    }

    // Gõ chữ vào ô nhập: SET_TEXT trước, nếu app/web không nhận thì dán qua clipboard.
    private fun typeInto(n: AccessibilityNodeInfo?, text: String): Boolean {
        if (n == null) return false
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ans", text))
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // Thực hiện lần lượt các hành động (chạm đáp án / gõ chữ). Bỏ qua ô đã được tích để khỏi bị bỏ tích.
    private fun chooseAll(acts: List<Act>, index: Int) {
        try {
            chooseStep(acts, index)
        } catch (e: Exception) {
            tapping = false
            cooldownUntil = SystemClock.uptimeMillis() + 1000
            toast("Lỗi khi chạm: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun chooseStep(acts: List<Act>, index: Int) {
        tapping = true
        tapStart = SystemClock.uptimeMillis()
        if (index >= acts.size) {
            tapping = false
            cooldownUntil = SystemClock.uptimeMillis() + 350
            afterTap()
            return
        }
        val act = acts[index]
        val cur = resolve(act.item, act.occ)
        val next = { handler.postDelayed({ chooseAll(acts, index + 1) }, 20) }
        if (act.text != null) {
            val limited = if (cur.maxLen > 0) act.text.take(cur.maxLen) else act.text
            if (!typeInto(cur.node, limited)) toast("Không gõ được vào ô nhập (ứng dụng chặn)")
            next()
            return
        }
        val n = cur.node
        var already = false
        if (cur.ctrl && n != null) {
            n.refresh()
            already = n.isCheckable && n.isChecked
        }
        if (already) {
            next()
            return
        }
        tap(cur.rect) { ok ->
            if (!ok) clickNode(cur.node)
            next()
        }
    }

    // Sau khi chạm, coi màn hình hiện tại (có thể có lời giải/đáp án đúng) là "đã xem" để không hỏi lại.
    private fun afterTap() {
        handler.postDelayed({
            val items2 = collect()
            val h2 = sig(items2)
            lastHash = h2
            currentHash = h2
            layoutHash = layoutOf(items2)
            markActive()
        }, 180)
    }

    private fun clickNode(n: AccessibilityNodeInfo?) {
        if (n == null) return
        n.refresh()
        val maxH = resources.displayMetrics.heightPixels * 0.5
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                val b = Rect(); cur.getBoundsInScreen(b)
                if (b.height() < maxH && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                break
            }
            cur = cur.parent
            depth++
        }
    }

    private fun tap(r: Rect, onDone: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val x = r.exactCenterX().coerceIn(2f, dm.widthPixels - 2f)
        val y = r.exactCenterY().coerceIn(2f, dm.heightPixels - 2f)
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 20)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!auto || event == null) return
        if (event.packageName?.toString() == packageName) return
        handler.removeCallbacks(eventTick)
        handler.postDelayed(eventTick, 40)
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        handler.removeCallbacks(idleTicker)
        (getSystemService(WINDOW_SERVICE) as WindowManager).let { wm -> button?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
