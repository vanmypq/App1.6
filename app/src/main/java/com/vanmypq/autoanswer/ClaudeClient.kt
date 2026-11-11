package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
// Ưu tiên model Flash đầy đủ (suy luận tốt hơn); chỉ tạm chuyển sang Flash-Lite khi lỗi/hết hạn mức,
// và sau 5 phút tự thử lại model tốt nhất.
object ClaudeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private var models: List<String>? = null
    private var idx = 0
    private var switchedAt = 0L
    private val modeOf = HashMap<String, Int>()
    // mode 0: thinkingBudget=1024 | 1: thinkingLevel=low | 2: không cấu hình suy nghĩ | 3: như 2 nhưng bỏ ảnh

    @Volatile var lastInfo: String = ""
    @Volatile var lastSwitch: String = ""

    // Một điểm cần chạm do AI chỉ ra trên ẢNH CHỤP: x, y chuẩn hoá 0..1000 (x từ trái sang, y từ trên xuống).
    data class VTap(val x: Int, val y: Int, val label: String)

    // ids: các phần tử cần chạm; inputs: id ô nhập -> chữ cần gõ.
    data class Answer(val ids: List<Int>, val inputs: Map<Int, String>)

    private const val BASE_RULES = "Bạn là giáo viên Toán/Khoa học THCS Việt Nam giải bài trắc nghiệm. Người dùng gửi " +
        "danh sách các phần tử trên màn hình điện thoại, mỗi dòng dạng [số] nội dung, và có thể kèm ẢNH CHỤP " +
        "màn hình. Dùng ảnh để đọc công thức, hình vẽ, bảng biểu mà danh sách chữ thiếu hoặc bị dính chữ " +
        "(ví dụ 'y2' nghĩa là y bình phương). Dòng '(công thức)' là công thức đã ghép lại, nằm ngay sau đáp án " +
        "đứng trước nó. Ký hiệu ☐ là ô tích chọn chưa chọn, ☑ là ô đã chọn, '(ô chọn) ...' là ô tích chọn kèm chữ " +
        "cùng hàng. Dòng '(chữ ẩn) ...' là chữ thường (câu hỏi, tiêu đề) mà trình duyệt web ẩn khỏi danh sách: đọc như " +
        "bình thường, ví dụ '(chữ ẩn) Câu 2: ...' là nội dung câu hỏi số 2. Ô '(ô chọn A - chưa có chữ, xem ảnh)', " +
        "'(ô chọn B ...)', '(ô chọn C ...)', '(ô chọn D ...)' là ô tích chọn KHÔNG kèm chữ phương án: A là ô đầu tiên nằm " +
        "dưới câu hỏi đó, B là ô thứ hai, C thứ ba, D thứ tư, theo thứ tự từ trên xuống; hãy NHÌN ẢNH CHỤP để đọc nội dung " +
        "từng phương án A/B/C/D của câu, chọn phương án đúng rồi trả số [id] của ĐÚNG ô cùng thứ tự đó. Câu nào chưa có ô " +
        "chọn nào hiện trên màn hình thì bỏ qua. KHÔNG được trả rỗng chỉ vì ô chọn không có chữ. " +
        "ẢNH CHỤP LÀ NGUỒN CHÍNH: hãy đọc câu hỏi và các phương án TRỰC TIẾP trong ảnh; danh sách chữ chỉ để biết số [id] " +
        "và vị trí của từng phần tử. Nếu ảnh và danh sách chữ khác nhau thì tin ảnh. Cuối mỗi dòng ô chọn có {x=..% y=..%} là " +
        "vị trí của ô trên màn hình (x: 0%=mép trái, 100%=mép phải; y: 0%=mép trên, 100%=mép dưới): ô nằm cùng hàng/cạnh phương " +
        "án nào trong ảnh thì là ô của phương án đó. Đọc kỹ cả đề lẫn phương án trong ảnh trước khi quyết định. " +
        "Hỗ trợ MỌI dạng: đáp án có chữ A/B/C/D; chỉ có ô tròn/ô vuông không có chữ cái (không cần tự " +
        "thêm chữ A/B/C/D nếu đề không ghi); câu hỏi có thể được đóng khung bằng tiêu đề đánh số như '8. Chọn đáp " +
        "án đúng nhất' thay vì ghi 'Câu 8', các phương án nằm ngay sau nội dung câu hỏi đó cho tới tiêu đề câu kế " +
        "tiếp; nhiều câu hỏi CHƯA trả lời có thể hiện cùng lúc trên một màn hình (trả lời tất cả); dòng '---' là " +
        "vạch phân cách GIỮA HAI CÂU HỎI KHÁC NHAU, không thuộc về câu nào; dòng '(cần xem ảnh để biết Đúng/Sai " +
        "nào đang được chọn)' nghĩa là các nút 'Đúng'/'Sai' không tự hiển thị dấu đã chọn trong văn bản, hãy nhìn " +
        "ẢNH CHỤP để biết ý nào đã chọn trước khi quyết định bấm ý nào; một đáp án " +
        "đúng; nhiều đáp án đúng; dạng Đúng/Sai theo từng ý a,b,c,d (chọn nút Đúng hoặc Sai của MỖI ý); và nhiều " +
        "câu hỏi cùng lúc trên một màn hình (trả lời tất cả). Với câu PHỦ ĐỊNH (KHÔNG PHẢI, SAI, NGOẠI TRỪ) chọn " +
        "phương án KHÔNG thỏa điều kiện. Dù là câu lý thuyết/khái niệm không có phép tính, VẪN PHẢI chọn phương " +
        "án phù hợp NHẤT dựa trên kiến thức đã học, KHÔNG được bỏ trống chỉ vì không chắc chắn tuyệt đối; chỉ trả " +
        "rỗng khi màn hình thật sự không có câu hỏi/phương án nào (ví dụ màn hình cài đặt, menu, trang chủ). Ưu " +
        "tiên bấm ô tích chọn (☐) hoặc chữ cái A/B/C/D của đáp án; không chọn " +
        "lại ô đã ☑ trừ khi sai. Dòng cuối cùng phải là JSON duy nhất: {\"ids\": [N, N, ...]} gồm số [id] của MỌI " +
        "phần tử cần bấm, theo thứ tự từ trên xuống. Nếu màn hình không có câu trắc nghiệm hoặc không chắc chắn, " +
        "trả {\"ids\": []}. CHỈ trả lời câu CHƯA được làm: bỏ qua câu đã hiện kết quả (có dòng như 'Chưa đúng', " +
        "'Chính xác', 'Đáp án đúng là', ô đã tô màu đúng/sai) và các nút điều hướng số câu; câu cần làm thường nằm " +
        "gần ô nhập hoặc ở cuối màn hình. "
    private const val FAST_TAIL = "Trả về JSON gồm 2 trường theo thứ tự: \"tinh\" (tính nhẩm/đối chiếu CỰC NGẮN, tối đa 30 từ: " +
        "với bài tính toán phải tính ra kết quả, với bài lý thuyết nêu công thức/định nghĩa đúng rồi so từng phương án) " +
        "và \"ids\". Phải điền \"tinh\" trước rồi mới chọn \"ids\" theo kết quả đó. Nếu có NHIỀU câu hỏi cùng lúc, " +
        "\"tinh\" chỉ cần 1 cụm rất ngắn mỗi câu, và \"ids\" phải gồm ĐỦ đáp án của TẤT CẢ câu còn thiếu; câu nào " +
        "chưa có dòng kết quả/hướng dẫn của riêng nó thì vẫn phải trả lời, đừng bỏ qua chỉ vì câu khác gần đó đã " +
        "có kết quả."
    private const val INPUT_TAIL = "Màn hình có ô nhập chữ (✎). DÙNG Google Search để tìm đáp án chính xác nhất " +
        "(ưu tiên văn bản pháp luật/nguồn chính thống/sách giáo khoa). Chỉ điền ĐÚNG từ/cụm từ cần điền, ngắn gọn, " +
        "đúng nguyên văn, KHÔNG giải thích, KHÔNG dấu chấm cuối; bỏ qua '(...)' nằm trong câu lệnh \"điền từ thích hợp\". " +
        "Nếu một ô có nhiều chỗ trống thì ghi các từ theo thứ tự, cách nhau bằng dấu ';'. Nếu không chắc vẫn đưa đáp án " +
        "khả dĩ nhất. Dòng cuối cùng là JSON duy nhất: {\"ids\": [số các đáp án trắc nghiệm cần chạm, nếu có], " +
        "\"nhap\": [{\"id\": số của ô nhập, \"text\": \"chữ cần gõ\"}]}."
    private const val MATH_INPUT_TAIL = " Màn hình có ô nhập đáp số (✎). Hãy TÍNH RA đáp số (ghi phép tính từng bước ngắn gọn " +
        "trong \"tinh\", tối đa 60 từ), rồi đưa vào \"nhap\": [{\"id\": số của ô nhập, \"text\": \"đáp số\"}]. " +
        "Chỉ ghi ĐÁP SỐ trong \"text\" (số, hoặc chữ ngắn), KHÔNG kèm đơn vị nếu bên cạnh ô đã có sẵn đơn vị, " +
        "tuân thủ giới hạn ký tự/định dạng ghi trong đề (dấu thập phân, làm tròn...). Không có đáp án trắc nghiệm thì \"ids\" để rỗng."
    private const val SLOW_TAIL = "Kiểm tra TỪNG phương án (chú ý bẫy hệ số bằng 0, số mũ, mẫu số chứa ẩn), " +
        "chỉ ghi phân tích ngắn gọn tối đa 6 dòng rồi mới in JSON. Nếu màn hình có NHIỀU câu hỏi cùng lúc, chỉ " +
        "phân tích 1 dòng cực ngắn cho MỖI câu (không phân tích dài từng câu), ưu tiên liệt kê ĐỦ ids/nhap của " +
        "TẤT CẢ câu còn thiếu hơn là viết giải thích dài dòng. Câu nào CHƯA có dòng kết quả/hướng dẫn riêng của " +
        "NÓ thì vẫn là câu CHƯA làm, phải trả lời bình thường, kể cả khi câu khác ở gần đó đã có kết quả. Nếu đề " +
        "có đoạn văn/ngữ cảnh dài (đọc hiểu, tình huống), đọc kỹ TOÀN BỘ đoạn trước khi chọn, đừng chỉ dựa vào " +
        "vài dòng cuối hay tiêu đề."

    private fun loadModels(apiKey: String): List<String> {
        models?.let { return it }
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("x-goog-api-key", apiKey)
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${txt.take(150)}")
        val arr = JSONObject(txt).optJSONArray("models") ?: JSONArray()
        val skip = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp")
        val flash = ArrayList<String>()
        val lite = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent") || !n.startsWith("gemini-") ||
                !n.contains("flash") || skip.any { n.contains(it) }
            ) continue
            if (n.contains("preview") || n.contains("omni")) continue
            if (n.contains("lite")) lite.add(n) else flash.add(n)
        }
        val list = ArrayList<String>()
        list.add(if (flash.contains("gemini-flash-latest")) "gemini-flash-latest"
                 else flash.sortedDescending().firstOrNull() ?: "gemini-flash-latest")
        list.add(if (lite.contains("gemini-flash-lite-latest")) "gemini-flash-lite-latest"
                 else lite.sortedDescending().firstOrNull() ?: "gemini-flash-lite-latest")
        list.addAll(flash.sortedDescending())
        list.addAll(lite.sortedDescending())
        val result = list.distinct()
        models = result
        return result
    }

    private fun schemaProps(input: Boolean): JSONObject {
        val props = JSONObject()
            .put("tinh", JSONObject().put("type", "STRING"))
            .put("ids", JSONObject()
                .put("type", "ARRAY")
                .put("items", JSONObject().put("type", "INTEGER")))
        if (input) {
            props.put("nhap", JSONObject()
                .put("type", "ARRAY")
                .put("items", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("id", JSONObject().put("type", "INTEGER"))
                        .put("text", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("id").put("text"))))
        }
        return props
    }

    private fun buildBody(screenText: String, mode: Int, img: String?, fast: Boolean, search: Boolean, input: Boolean): JSONObject {
        val parts = JSONArray()
        if (img != null && mode < 3) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        }
        parts.put(JSONObject().put("text", screenText))
        val gen = JSONObject().put("maxOutputTokens", if (search || !fast) 8192 else 1024)
        if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", if (search) (if (fast) 512 else 1024) else if (fast) 0 else 1024))
        if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (fast && !search && mode < 3) {
            // Ép model chỉ trả {"ids":[...]} (vài token) -> nhanh nhất.
            gen.put("responseMimeType", "application/json")
            gen.put("responseSchema", JSONObject()
                .put("type", "OBJECT")
                .put("properties", schemaProps(input))
                .put("propertyOrdering", if (input) JSONArray().put("tinh").put("ids").put("nhap") else JSONArray().put("tinh").put("ids"))
                .put("required", if (input) JSONArray().put("tinh").put("ids").put("nhap") else JSONArray().put("tinh").put("ids")))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", BASE_RULES + (if (search) INPUT_TAIL else (if (fast) FAST_TAIL else SLOW_TAIL) + (if (input) MATH_INPUT_TAIL else ""))))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", parts)))
            .put("generationConfig", gen)
            .also { if (search && mode < 3) it.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))) }
    }

    private fun post(apiKey: String, m: String, body: JSONObject, fast: Boolean): Pair<Int, String> {
        try {
            val c = URL("$BASE/models/$m:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = if (fast) 20000 else 40000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            return Pair(code, resp)
        } catch (e: SocketTimeoutException) {
            return Pair(408, "quá thời gian chờ")
        }
    }

    private fun replyText(resp: String): String {
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return ""
        if (cands.length() == 0) return ""
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun extractArray(text: String, from: Int): String? {
        val start = text.indexOf('[', from)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            } else if (ch == '"') inStr = true
            else if (ch == '[') depth++
            else if (ch == ']') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun parseAnswer(text: String): Answer {
        val ids = parseIds(text)
        val inputs = LinkedHashMap<Int, String>()
        try {
            val pos = text.lastIndexOf("\"nhap\"")
            if (pos >= 0) {
                val arr = extractArray(text, pos)
                if (arr != null) {
                    val ja = JSONArray(arr)
                    for (i in 0 until ja.length()) {
                        val o = ja.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        val t = o.optString("text", "").trim()
                        if (id >= 1 && t.isNotEmpty()) inputs[id] = t
                    }
                }
            }
        } catch (e: Exception) {
        }
        return Answer(ids, inputs)
    }

    private fun parseIds(text: String): List<Int> {
        val arr = Regex("\"ids\"\\s*:\\s*\\[([^\\]]*)\\]").findAll(text).lastOrNull()
        if (arr != null) {
            return Regex("\\d+").findAll(arr.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
        }
        // Màn hình có nhiều câu đôi khi làm câu trả lời bị CẮT GIỮA CHỪNG (hết hạn mức token) nên mảng
        // "ids" không có dấu ']' đóng. Vẫn lấy các số đã kịp sinh ra thay vì bỏ hết, để không mất trắng
        // các câu đã được AI chọn xong trước khi bị cắt.
        val cutIdx = text.lastIndexOf("\"ids\"")
        if (cutIdx >= 0) {
            val open = text.indexOf('[', cutIdx)
            if (open >= 0) {
                val tail = text.substring(open + 1)
                val stop = tail.indexOfFirst { it == '"' }
                val segment = if (stop >= 0) tail.substring(0, stop) else tail
                val nums = Regex("\\d+").findAll(segment).mapNotNull { it.value.toIntOrNull() }.toList()
                if (nums.isNotEmpty()) return nums
            }
        }
        val one = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(text).lastOrNull()
        return if (one != null) listOfNotNull(one.groupValues[1].toIntOrNull()) else emptyList()
    }

    private val VISION_RULES = """Bạn là giáo viên Việt Nam đang làm bài trắc nghiệm. Ảnh là ảnh chụp màn hình điện thoại đang hiện một bài trắc nghiệm (thường trên trình duyệt, có thể có thanh địa chỉ, tiêu đề, nút điều hướng).
NHIỆM VỤ: tìm MỌI câu hỏi trắc nghiệm đang hiện trên màn hình mà (a) thấy rõ ĐỦ nội dung câu hỏi và ĐỦ các phương án, (b) CHƯA có phương án nào được chọn (ô tròn/ô vuông còn rỗng; ô đã tô màu hoặc có dấu chọn nghĩa là đã làm, BỎ QUA câu đó). Câu bị cắt ở mép trên hoặc mép dưới màn hình (thiếu đề hoặc thiếu phương án) thì BỎ QUA, sẽ làm sau khi cuộn trang.
Với mỗi câu cần làm: suy luận ngắn rồi chọn phương án đúng nhất (câu phủ định KHÔNG PHẢI/SAI/NGOẠI TRỪ thì chọn phương án không thỏa điều kiện; câu lý thuyết vẫn phải chọn phương án phù hợp NHẤT, không được bỏ trống).
Trả về JSON dạng {"cau":[{"so":"số hoặc tiêu đề câu","tinh":"lý do cực ngắn","dap_an":"chữ cái hoặc thứ tự 1-4 của phương án đúng","x":số,"y":số}]}. x, y là TOẠ ĐỘ TÂM của ô tròn/ô vuông (hoặc dấu chọn) nằm ngay trước phương án đúng, đo trên ảnh và chuẩn hoá thành số nguyên 0..1000 (x: 0 = mép trái, 1000 = mép phải; y: 0 = mép trên, 1000 = mép dưới). Nếu phương án không có ô tròn thì lấy tâm dòng chữ của phương án đó. Phải nhìn kỹ để điểm nằm đúng phương án đã chọn, không lệch sang phương án bên trên/dưới.
Chỉ liệt kê các câu cần chạm. Nếu không có câu nào thỏa điều kiện thì trả {"cau": []}. KHÔNG chạm các nút Kết thúc / Nộp bài / Tiếp theo / Xem lại."""

    private fun strProp() = JSONObject().put("type", "STRING")
    private fun intProp() = JSONObject().put("type", "INTEGER")

    private fun visionBody(img: String, mode: Int): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        parts.put(JSONObject().put("text", "Làm bài trắc nghiệm trong ảnh theo hướng dẫn."))
        val gen = JSONObject().put("maxOutputTokens", 4096)
        if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 1024))
        if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (mode < 3) {
            val names = listOf("so", "tinh", "dap_an", "x", "y")
            val item = JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("so", strProp()).put("tinh", strProp()).put("dap_an", strProp())
                    .put("x", intProp()).put("y", intProp()))
                .put("propertyOrdering", JSONArray(names))
                .put("required", JSONArray(names))
            gen.put("responseMimeType", "application/json")
            gen.put("responseSchema", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("cau", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray(listOf("cau"))))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", VISION_RULES))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun parseVision(text: String): List<VTap> {
        val out = ArrayList<VTap>()
        try {
            val a = text.indexOf('{')
            val b = text.lastIndexOf('}')
            if (a >= 0 && b > a) {
                val arr = JSONObject(text.substring(a, b + 1)).optJSONArray("cau")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val x = o.optInt("x", -1)
                        val y = o.optInt("y", -1)
                        if (x !in 0..1000 || y !in 0..1000) continue
                        if (out.any { Math.abs(it.x - x) < 15 && Math.abs(it.y - y) < 15 }) continue
                        out.add(VTap(x, y, o.optString("so") + " " + o.optString("dap_an")))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out.sortedBy { it.y }
    }

    // CHẾ ĐỘ NHÌN ẢNH: dùng khi cây giao diện không đưa nội dung trang web (không có ô chọn để chạm theo [id]).
    // AI đọc ảnh chụp, chọn đáp án và trả toạ độ ô cần chạm.
    fun askVision(apiKey: String): List<VTap> {
        val img = (try { CaptureService.grab(0) } catch (e: Exception) { null })
            ?: throw RuntimeException(if (CaptureService.blackFrame) "Ảnh chụp bị đen (app chặn chụp màn hình)" else "Chưa có ảnh chụp màn hình (hãy bật 📷)")
        val base = models ?: DEFAULT_MODELS
        if (idx != 0 && System.currentTimeMillis() - switchedAt > 120000L) idx = 0
        var lastErr = "không có model"
        var tries = 0
        while (tries < base.size) {
            val m = base[idx % base.size]
            val mk = "$m|vision"
            var mode = modeOf[mk] ?: 0
            var r = post(apiKey, m, visionBody(img, mode), false)
            while (r.first == 400 && mode < 3 && !r.second.contains("API key", ignoreCase = true)) {
                mode++
                r = post(apiKey, m, visionBody(img, mode), false)
            }
            if (r.first in 200..299) {
                if (mode < 3) modeOf[mk] = mode
                val text = replyText(r.second)
                lastInfo = "Model: $m | CHẾ ĐỘ NHÌN ẢNH | Chế độ suy nghĩ: $mode\n" + lastSwitch + "\nAI trả lời:\n$text"
                return parseVision(text)
            }
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            if (r.first == 429 || r.first == 404 || r.first == 408 || r.first == 500 || r.first == 503) {
                lastSwitch = "Đã chuyển khỏi $m vì: $lastErr"
                idx++
                switchedAt = System.currentTimeMillis()
                tries++
                continue
            }
            throw RuntimeException(lastErr)
        }
        models = null
        try { loadModels(apiKey) } catch (e: Exception) { }
        throw RuntimeException(lastErr)
    }

    // Không gọi danh sách model trước (bớt 1 vòng mạng); chỉ dò model khi mọi model mặc định đều lỗi.
    private val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

    // Mở sẵn kết nối HTTPS khi bật nút AI để câu đầu tiên không mất thời gian bắt tay TLS.
    fun warmUp(apiKey: String) {
        try {
            val c = URL("$BASE/models/gemini-flash-lite-latest").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.responseCode
            (try { c.inputStream } catch (e: Exception) { c.errorStream })?.use { it.readBytes() }
        } catch (e: Exception) {
        }
    }

    fun ask(apiKey: String, screenText: String, fast: Boolean, hasInput: Boolean = false, alwaysImg: Boolean = true): Answer {
        // Có ô nhập: câu tính toán/công thức -> tự tính (nhanh, không cần tra cứu); câu kiến thức/pháp luật -> tra Google Search.
        val mathy = screenText.contains("(công thức)") ||
            Regex("(?i)tính|giải|giá trị|rút gọn|phương trình|bằng bao nhiêu|diện tích|chu vi|thể tích").containsMatchIn(screenText)
        val search = hasInput && !mathy
        // Chế độ nhanh chỉ gửi ảnh khi có công thức; chế độ chính xác luôn gửi ảnh.
        val needImg = alwaysImg || !fast || screenText.contains("(công thức)") || screenText.contains("xem ảnh")
        val img: String? = if (needImg) (try { CaptureService.grab(if (fast) 1280 else 0) } catch (e: Exception) { null }) else null
        val base = models ?: DEFAULT_MODELS
        // Cả hai chế độ đều ưu tiên Flash đầy đủ (Lite hay đoán sai bài toán); Lite chỉ là dự phòng khi lỗi/hết hạn mức.
        val list = base
        if (idx != 0 && System.currentTimeMillis() - switchedAt > 120000L) idx = 0
        var lastErr = "không có model"
        var tries = 0
        while (tries < list.size) {
            val m = list[idx % list.size]
            val mk = "$m|$search|$hasInput|$fast"
            var mode = modeOf[mk] ?: 0
            var r = post(apiKey, m, buildBody(screenText, mode, img, fast, search, hasInput), if (search) false else fast)
            while (r.first == 400 && mode < 3 && !r.second.contains("API key", ignoreCase = true)) {
                mode++
                r = post(apiKey, m, buildBody(screenText, mode, img, fast, search, hasInput), if (search) false else fast)
            }
            if (r.first in 200..299) {
                if (mode < 3) modeOf[mk] = mode
                val text = replyText(r.second)
                lastInfo = "Model: $m | Nhanh: $fast | Ảnh: " + (if (img != null && mode < 3) "CÓ" else "KHÔNG") +
                    " | Chế độ suy nghĩ: $mode\n" + lastSwitch + "\nAI trả lời:\n$text"
                return parseAnswer(text)
            }
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            if (r.first == 429 || r.first == 404 || r.first == 408 || r.first == 500 || r.first == 503) {
                lastSwitch = "Đã chuyển khỏi $m vì: $lastErr"
                idx++
                switchedAt = System.currentTimeMillis()
                tries++
                continue
            }
            throw RuntimeException(lastErr)
        }
        models = null
        try { loadModels(apiKey) } catch (e: Exception) { }
        throw RuntimeException(lastErr)
    }
}
