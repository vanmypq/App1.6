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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        val input: Boolean = false
    )

    // Kết quả quét các nút điều hướng trên màn hình hiện tại.
    class Nav {
        var confirm: AccessibilityNodeInfo? = null
        var next: AccessibilityNodeInfo? = null
        var finish: AccessibilityNodeInfo? = null
        var scroller: AccessibilityNodeInfo? = null
        var scrollRect: Rect? = null
    }

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
    private var tapping = false
    private var tapStart = 0L
    private var staleCount = 0
    private var advanceNotBefore = 0L
    private var bottomHash = 0
    private var confirmedHash = 0
    private var lastNextHash = 0
    private var nextRepeat = 0
    private var stuck = 0
    private var actions = 0
    private val clockRe = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")

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
            handler.postDelayed(this, 200)
        }
    }

    private val eventTick = Runnable {
        tick()
        handler.postDelayed({ tick() }, 90)
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
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-input", ClaudeClient.lastInfo + "\n\n" + lastSent))
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
        advanceNotBefore = 0L
        bottomHash = 0
        confirmedHash = 0
        lastNextHash = 0
        nextRepeat = 0
        stuck = 0
        actions = 0
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
        if (busy || tapping || SystemClock.uptimeMillis() < cooldownUntil) return
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

    // Nút tròn/ô vuông tự vẽ (ImageView bấm được, không có chữ) dùng làm ô tích chọn.
    private fun looksLikeIconButton(n: AccessibilityNodeInfo, r: Rect): Boolean {
        if (!n.isClickable || !n.isEnabled || n.childCount != 0) return false
        val d = resources.displayMetrics.density
        val w = r.width()
        val h = r.height()
        if (w < 18 * d || h < 18 * d || w > 90 * d || h > 90 * d) return false
        return abs(w - h) <= 0.35f * maxOf(w, h)
    }

    // Ô tích chọn không có chữ: gắn nhãn bằng chữ cùng hàng (ưu tiên bên phải) để AI biết ô nào ứng với đáp án nào.
    private fun labelControls(list: ArrayList<Item>): List<Item> {
        var k = 0
        for (i in list.indices) {
            val c = list[i]
            if (!c.ctrl || c.text.isNotEmpty()) continue
            var best: Item? = null
            var bestGap = Int.MAX_VALUE
            for (o in list) {
                if (o === c || o.ctrl || o.text.isEmpty()) continue
                val top = maxOf(o.rect.top, c.rect.top)
                val bot = minOf(o.rect.bottom, c.rect.bottom)
                if ((bot - top) < 0.5f * minOf(o.rect.height(), c.rect.height())) continue
                val gap = if (o.rect.left >= c.rect.right - 10) o.rect.left - c.rect.right
                          else 100000 + maxOf(0, c.rect.left - o.rect.right)
                if (gap < bestGap) { bestGap = gap; best = o }
            }
            val label = best?.text?.take(150)
            k++
            list[i] = c.copy(text = if (label != null) "(ô chọn) $label" else "(ô chọn trống $k)")
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
                        out.add(Item(t.take(400), r, n, choice, choice && n.isChecked))
                    } else if (choice || looksLikeIconButton(n, r)) {
                        out.add(Item("", r, n, true, choice && n.isChecked))
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, true, r) }
            } else if (parentVisible) {
                // Nút ẩn nằm trong nút đang hiện (ví dụ công thức KaTeX/MathML): lấy chữ, chạm vào vùng của nút cha.
                val t = gather(n, 0).trim()
                if (t.isNotEmpty() && !parentRect.isEmpty) {
                    out.add(Item("(công thức) " + t.take(300), Rect(parentRect), n))
                }
            }
        }
        walk(root, 0, false, Rect())
        return labelControls(out)
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
        if (h != currentHash) { currentHash = h; retries = 0; stuck = 0 }
        if (h == lastHash) { maybeAdvance(items, h); return }
        val lh = layoutOf(items)
        if (lh != layoutHash) {
            layoutHash = lh
            setStatus("chờ trang ổn định…")
            return
        }
        val cached = answerCache[h]
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
        if (!hasImg && text.contains("(công thức)")) {
            status = "⚠ CHƯA BẬT CHỤP MÀN HÌNH: mở app Auto Trắc Nghiệm > Cấp quyền chụp màn hình"
            refresh()
            cooldownUntil = SystemClock.uptimeMillis() + 3000
            return
        }
        lastHash = h
        busy = true
        val fast = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true)
        val hasInput = items.any { it.input }
        setStatus(if (hasInput) "AI đang điền…" else if (fast) "AI nhanh…" else "AI đang suy nghĩ…")
        lastSent = text
        exec.execute {
            var got = ClaudeClient.Answer(emptyList(), emptyMap())
            var gotErr: String? = null
            try {
                got = ClaudeClient.ask(key, text, fast, hasInput)
            } catch (e: Exception) {
                gotErr = e.message ?: "lỗi mạng"
            }
            val ans: ClaudeClient.Answer = got
            val err: String? = gotErr
            handler.post {
                busy = false
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
                    if (!hasInput && (hasImg || !text.contains("(công thức)"))) {
                        answerCache[h] = acts.map { p -> Pair(p.item.text, p.occ) }
                    }
                    chooseAll(acts, 0)
                } else if (retries < 1 && !items.any { it.checked }) {
                    retries++
                    lastHash = 0
                    status = "thử lại ($retries)"
                    refresh()
                } else {
                    status = "không thấy câu hỏi"
                    advanceNotBefore = SystemClock.uptimeMillis() + 400
                    refresh()
                }
            }
        }
    }

    // Ghép các mảnh công thức rời rạc (mỗi ký tự một nút) thành một dòng "(công thức)" cho AI dễ đọc.
    // Ô tích chọn được đánh dấu ☐ (chưa chọn) / ☑ (đã chọn). Số thứ tự gốc được giữ để AI trả đúng số cần chạm.
    private fun buildPrompt(items: List<Item>): String {
        val sb = StringBuilder()
        val run = ArrayList<Int>()
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
                if (item.ctrl) sb.append("[${i + 1}] ").append(if (item.checked) "☑ " else "☐ ").append(t).append('\n')
                else if (item.input) {
                    val filled = item.node?.let { nd ->
                        nd.refresh()
                        if (nd.isShowingHintText) "" else (nd.text?.toString()?.trim() ?: "")
                    } ?: ""
                    sb.append("[${i + 1}] ✎ ").append(t)
                    if (filled.isNotEmpty()) sb.append(" (đã điền: ").append(filled.take(40)).append(')')
                    val above = nearestAbove(items, i)
                    if (above.isNotEmpty()) sb.append(" (ô của dòng: ").append(above).append(')')
                    sb.append('\n')
                }
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
    private fun resolve(item: Item, occurrence: Int): Item {
        val n = item.node
        if (n != null && n.refresh()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty && n.isVisibleToUser) return item.copy(rect = r)
        }
        val fresh = collect().filter { it.text == item.text }
        if (fresh.size > occurrence) return fresh[occurrence]
        return item
    }

    // Gõ chữ vào ô nhập: SET_TEXT trước, nếu app/web không nhận thì chọn hết rồi dán qua clipboard.
    private fun typeInto(n: AccessibilityNodeInfo?, text: String): Boolean {
        if (n == null) return false
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            n.refresh()
            val now = if (n.isShowingHintText) "" else (n.text?.toString()?.trim() ?: "")
            // Rỗng/không đọc lại được thì tin kết quả của SET_TEXT; chỉ dán lại khi chắc chắn đang là chữ khác.
            if (now.isEmpty() || now == text || now.contains(text)) return true
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ans", text))
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, if (n.isShowingHintText) 0 else (n.text?.length ?: 0))
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // Nhập CHÍNH XÁC vào đúng ô: đưa ô vào màn hình, chạm thật vào giữa ô để lấy focus, rồi chỉ gõ vào ô đang focus
    // (nếu ô đó trùng vùng với ô đã chọn). Ô đã có đúng đáp án thì bỏ qua.
    private fun typeAt(cur: Item, text: String, done: (Boolean) -> Unit) {
        val n = cur.node
        if (n == null) { done(false); return }
        n.refresh()
        val existing = if (n.isShowingHintText) "" else (n.text?.toString()?.trim() ?: "")
        if (existing.isNotEmpty() && existing.equals(text.trim(), ignoreCase = true)) { done(true); return }
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        handler.postDelayed({
            n.refresh()
            val r = Rect(); n.getBoundsInScreen(r)
            val canTap = !r.isEmpty && n.isVisibleToUser && r.exactCenterY() < imeTop() - 4
            val afterFocus = {
                handler.postDelayed({
                    n.refresh()
                    var target: AccessibilityNodeInfo = n
                    if (!n.isFocused) {
                        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (f != null && (f.isEditable || (f.className?.toString() ?: "").contains("EditText"))) {
                            val fr = Rect(); f.getBoundsInScreen(fr)
                            if (Rect.intersects(fr, r)) target = f
                        }
                    }
                    done(typeInto(target, text))
                }, 220)
            }
            if (canTap) tap(r) { afterFocus() } else afterFocus()
        }, 120)
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
            cooldownUntil = SystemClock.uptimeMillis() + 500
            afterTap()
            return
        }
        val act = acts[index]
        val cur = resolve(act.item, act.occ)
        val next = { handler.postDelayed({ chooseAll(acts, index + 1) }, 30) }
        if (act.text != null) {
            typeAt(cur, act.text) { ok ->
                if (!ok) toast("Không gõ được vào ô nhập (ứng dụng chặn)")
                next()
            }
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
            advanceNotBefore = SystemClock.uptimeMillis() + 700
        }, 250)
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
            .addStroke(GestureDescription.StrokeDescription(p, 0, 30)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    // Dòng chữ gần nhất phía trên ô nhập, để AI biết ô nào thuộc câu nào.
    private fun nearestAbove(items: List<Item>, i: Int): String {
        val top = items[i].rect.top
        for (j in i - 1 downTo 0) {
            val o = items[j]
            if (o.ctrl || o.input || o.text.length < 6 || o.text.startsWith("(")) continue
            if (o.rect.bottom <= top + 20) return o.text.take(60)
        }
        return ""
    }

    // ===================== TỰ CUỘN / TỰ CHUYỂN CÂU =====================
    private val finishWords = listOf("nộp bài", "kết thúc", "hoàn thành", "hoàn tất", "submit", "finish", "gửi bài", "xem kết quả", "nộp")
    private val backWords = listOf("trước", "quay lại", "back", "previous", "prev")
    private val nextPrefix = listOf("câu tiếp", "tiếp theo", "tiếp tục", "next", "câu sau", "câu kế", "kế tiếp", "sang câu", "chuyển câu", "continue")
    private val nextExact = setOf("tiếp", "sau", "›", "»", "→", ">", ">>", "❯", "▶")
    private val confirmExact = setOf("kiểm tra", "kiểm tra đáp án", "kiểm tra câu trả lời", "check", "check answer", "trả lời", "gửi đáp án", "chốt đáp án", "xác nhận đáp án")
    private val resultWords = listOf("chính xác", "chưa đúng", "đáp án đúng", "đúng rồi", "sai rồi", "correct", "incorrect")

    // 1 = nút chuyển câu, 2 = nút kiểm tra/gửi đáp án của câu, 3 = nút nộp bài/kết thúc (không tự bấm), 0 = khác.
    private fun classify(lab: String, id: String): Int {
        if (lab.isNotEmpty() && lab.length <= 28) {
            if (finishWords.any { lab.contains(it) }) return 3
            if (backWords.any { lab.contains(it) }) return 0
            if (lab in nextExact || nextPrefix.any { lab.startsWith(it) } ||
                lab.endsWith(" →") || lab.endsWith(" ›") || lab.endsWith(" »")) return 1
            if (lab in confirmExact) return 2
        }
        if (id.contains("next") && !id.contains("prev") && !id.contains("text")) return 1
        return 0
    }

    private fun clickableChain(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 4) {
            if (c.isClickable) return true
            c = c.parent
            d++
        }
        return false
    }

    private fun scanNav(root: AccessibilityNodeInfo): Nav {
        val nav = Nav()
        val dm = resources.displayMetrics
        var bestArea = 0L
        var nextBottom = -1
        var confBottom = -1
        var finBottom = -1
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || !n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (n.isScrollable && r.height() >= 0.3f * dm.heightPixels && r.width() >= 0.5f * dm.widthPixels &&
                n.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) {
                val area = r.width().toLong() * r.height()
                if (area > bestArea) { bestArea = area; nav.scroller = n; nav.scrollRect = Rect(r) }
            }
            if (n.isEnabled && r.height() > 0 && r.width() > 0 && r.height() < 0.25f * dm.heightPixels && !isChoiceNode(n)) {
                val raw = (n.text ?: n.contentDescription)?.toString() ?: ""
                val lab = raw.trim().lowercase().replace(Regex("[\\s.!:]+"), " ").trim()
                val kind = classify(lab, n.viewIdResourceName?.lowercase() ?: "")
                if (kind != 0 && clickableChain(n)) {
                    when (kind) {
                        1 -> if (r.bottom > nextBottom) { nextBottom = r.bottom; nav.next = n }
                        2 -> if (r.bottom > confBottom) { confBottom = r.bottom; nav.confirm = n }
                        3 -> if (r.bottom > finBottom) { finBottom = r.bottom; nav.finish = n }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return nav
    }

    private fun imeTop(): Int {
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect(); w.getBoundsInScreen(r)
                    if (!r.isEmpty) return r.top
                }
            }
        } catch (e: Exception) {
        }
        return Int.MAX_VALUE
    }

    private fun swipe(x: Float, y1: Float, y2: Float, dur: Long, onDone: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    private fun clickButton(n: AccessibilityNodeInfo, done: () -> Unit) {
        n.refresh()
        var cur: AccessibilityNodeInfo? = n
        var d = 0
        while (cur != null && d < 5) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done(); return }
            cur = cur.parent
            d++
        }
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.isEmpty) { done(); return }
        tap(r) { done() }
    }

    private fun endAction(wait: Long) {
        tapping = false
        val t = SystemClock.uptimeMillis() + wait
        cooldownUntil = t
        advanceNotBefore = t
    }

    private fun stopAuto(msg: String) {
        auto = false
        tapping = false
        handler.removeCallbacks(loop)
        status = ""
        refresh()
        toast(msg)
    }

    // Màn hình đã xử lý xong: lần lượt kiểm tra đáp án -> cuộn tìm câu mới -> bấm chuyển câu. Không bao giờ tự nộp bài.
    private fun maybeAdvance(items: List<Item>, h: Int) {
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("advance", true)) return
        val now = SystemClock.uptimeMillis()
        if (busy || tapping || now < advanceNotBefore || now < cooldownUntil) return
        val lh = layoutOf(items)
        if (lh != layoutHash) { layoutHash = lh; advanceNotBefore = now + 400; return }
        val root = rootInActiveWindow ?: return
        val nav = scanNav(root)

        // 1) Nút "Kiểm tra / Trả lời" của câu (chỉ bấm 1 lần cho mỗi màn hình).
        val conf = nav.confirm
        if (conf != null && confirmedHash != h) {
            confirmedHash = h
            actions++
            tapping = true; tapStart = now
            status = "kiểm tra đáp án"; refresh()
            clickButton(conf) {
                handler.postDelayed({
                    val it2 = collect()
                    val joined = it2.joinToString(" ") { x -> x.text }.lowercase()
                    if (resultWords.any { w -> joined.contains(w) }) {
                        val h2 = sig(it2)
                        lastHash = h2; currentHash = h2; confirmedHash = h2; layoutHash = layoutOf(it2)
                    }
                    endAction(500)
                }, 700)
            }
            return
        }

        // 2) Cuộn xuống tìm câu mới (nếu trang còn cuộn được, hoặc không có nút chuyển câu để dùng).
        val atBottom = bottomHash == h
        if (!atBottom && (nav.scroller != null || nav.next == null)) {
            actions++
            if (actions > 400) { stopAuto("Đã chạy 400 bước, tự dừng cho an toàn"); return }
            scrollPage(nav, h)
            return
        }

        // 3) Bấm nút chuyển câu.
        val nx = nav.next
        if (nx != null) {
            if (h == lastNextHash) nextRepeat++ else { nextRepeat = 0; lastNextHash = h }
            if (nextRepeat == 1) {
                // Bấm rồi mà màn hình không đổi: có thể chưa chọn xong đáp án, hỏi lại AI một lần.
                answerCache.remove(h)
                lastHash = 0; layoutHash = 0; retries = 0
                advanceNotBefore = now + 800
                status = "chưa chuyển được, kiểm tra lại"; refresh()
                return
            }
            if (nextRepeat >= 3) {
                stopAuto("Bấm nút chuyển câu nhưng màn hình không đổi. Có thể câu này chưa chọn được đáp án hoặc đã hết bài.")
                return
            }
            actions++
            if (actions > 400) { stopAuto("Đã chạy 400 bước, tự dừng cho an toàn"); return }
            tapping = true; tapStart = now
            status = "chuyển câu"; refresh()
            clickButton(nx) { handler.postDelayed({ endAction(600) }, 300) }
            return
        }

        // 4) Hết trang và không có nút chuyển câu: nếu có nút Nộp bài thì dừng để người dùng tự bấm.
        if (nav.finish != null) {
            stopAuto("Đã làm hết câu. Hãy tự kiểm tra rồi tự bấm Nộp bài.")
            return
        }
        stuck++
        advanceNotBefore = now + 1500
        if (stuck >= 3) stopAuto("Không thấy thêm câu hỏi hoặc nút chuyển câu, đã dừng.")
    }

    // Vuốt lên để cuộn (có chồng lấn ~50% để không lỡ câu nằm giữa 2 màn hình); nếu không đổi thì thử ACTION_SCROLL_FORWARD;
    // nếu vẫn không đổi coi như đã tới cuối trang.
    private fun scrollPage(nav: Nav, h: Int) {
        tapping = true
        tapStart = SystemClock.uptimeMillis()
        status = "cuộn tìm câu mới"; refresh()
        val before = layoutOf(collect())
        val dm = resources.displayMetrics
        val sr = nav.scrollRect
        val top = (sr?.top ?: (dm.heightPixels * 0.15f).toInt())
        var bot = minOf(sr?.bottom ?: dm.heightPixels, imeTop())
        bot = minOf(bot, dm.heightPixels - (dm.heightPixels * 0.06f).toInt())
        val hgt = (bot - top).coerceAtLeast(200)
        val x = (sr?.exactCenterX() ?: (dm.widthPixels / 2f)).coerceIn(10f, dm.widthPixels - 10f)
        swipe(x, top + hgt * 0.78f, top + hgt * 0.28f, 450) {
            handler.postDelayed({
                val after = layoutOf(collect())
                if (after != before) {
                    endAction(700)
                } else {
                    nav.scroller?.let { sc -> sc.refresh(); sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    handler.postDelayed({
                        if (layoutOf(collect()) == before) { bottomHash = h; endAction(200) } else endAction(700)
                    }, 600)
                }
            }, 700)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!auto || event == null) return
        if (event.packageName?.toString() == packageName) return
        handler.removeCallbacks(eventTick)
        handler.postDelayed(eventTick, 60)
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        handler.removeCallbacks(idleTicker)
        (getSystemService(WINDOW_SERVICE) as WindowManager).let { wm -> button?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
