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
        "chọn nào hiện trên màn hình thì bỏ qua. KHÔNG được trả rỗng chỉ vì ô chọn không có chữ. Chỉ trả lời câu mà trong ảnh thấy ĐỦ đề bài VÀ ĐỦ các phương án; câu bị cắt ở mép trên/dưới màn hình (thiếu một phần đề hoặc thiếu phương án) thì BỎ QUA để lần sau cuộn trang rồi làm (trừ khi câu dài hơn cả màn hình). " +
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

    // Vị trí (0..1000 theo chiều cao màn hình) mà phương án cuối của câu vừa làm xong sẽ được đưa tới khi cuộn:
    // 250 = 1/4 từ mép trên, 350 = khoảng 35% (thấp hơn, thấy nhiều phần câu trước hơn). Muốn nhích thấp hơn nữa thì tăng số này.
    private const val ANCHOR_Y = 350

    // Định dạng GỌN để AI trả lời nhanh: mỗi câu chỉ khoảng 40 ký tự mã thay vì ~150 (thời gian trả lời chủ yếu tỉ lệ với số chữ AI viết ra).
    private fun visionRules(fast: Boolean): String = """Bạn là giáo viên Việt Nam đang làm bài trắc nghiệm trên điện thoại. Ảnh là ảnh chụp màn hình (thường là trang web gồm nhiều câu nối tiếp nhau theo chiều dọc; mỗi câu có số thứ tự/tiêu đề, đề bài, rồi các phương án có ô tròn ở bên trái).
Liệt kê MỌI câu xuất hiện trong ảnh (kể cả câu chỉ hiện một phần) theo thứ tự từ trên xuống. Mỗi câu là một phần tử của "cau":
- "so": SỐ THỨ TỰ của câu (ví dụ 7); chỉ khi câu không có số thì ghi tiêu đề ngắn.
- "dc": true nếu câu ĐÃ có phương án được chọn (có ô tròn được tô màu/có dấu chọn). Ô tròn chỉ có viền xám, bên trong trống = CHƯA chọn (false). Ô tròn MÀU ĐỎ hoặc dòng "Bạn đã trả lời sai, vui lòng làm lại" = đã chọn SAI: coi là CHƯA xong (false) và phải chọn lại phương án ĐÚNG KHÁC phương án màu đỏ. Ô màu xanh hoặc dòng "Câu trả lời đúng" = đã làm đúng (true). Không chắc thì false.
- "dau": toạ độ y mép trên dòng số thứ tự/tiêu đề của câu; -1 nếu dòng đó đã bị cắt mất ở mép trên của ảnh.
- "cuoi": toạ độ y mép dưới phương án cuối cùng nhìn thấy; ghi 1000 nếu phương án cuối bị cắt hoặc nằm sát đáy ảnh (cách đáy dưới 8% chiều cao ảnh, vì có thể còn phương án khác nằm ngoài ảnh).
@@TINH@@- "chon": CHỈ khi câu hiện đủ (thấy dòng tiêu đề và đủ mọi phương án) và chưa chọn (dc=false): chữ cái A, B, C, D... của phương án ĐÚNG NHẤT theo thứ tự từ trên xuống. Câu phủ định (KHÔNG PHẢI/SAI/NGOẠI TRỪ) chọn phương án không thỏa điều kiện; câu lý thuyết vẫn phải chọn phương án phù hợp NHẤT, không được bỏ trống. Các trường hợp khác để "".
- "x": toạ độ x của tâm các ô tròn/ô vuông phương án (cùng một cột).
- "ys": toạ độ y của tâm ô tròn của TỪNG phương án nhìn thấy, theo thứ tự từ trên xuống (tăng dần): phần tử thứ 1 là A, thứ 2 là B... Hãy đo riêng từng phương án (phương án dài 2 dòng thì khoảng cách lớn hơn). Làm cho MỌI câu, kể cả câu đã chọn hoặc chưa hiện đủ.
Mọi toạ độ là số nguyên 0..1000 theo ảnh (x: 0 = mép trái, 1000 = mép phải; y: 0 = mép trên, 1000 = mép dưới).
Chỉ liệt kê phần câu hỏi trắc nghiệm; bỏ qua thanh địa chỉ, nút Kết thúc / Nộp bài / Tiếp theo / Xem lại và các dòng thông báo không thuộc câu hỏi."""
        .replace("@@TINH@@", if (fast) "" else "- \"tinh\": CHỈ khi câu hiện đủ và chưa chọn: lý do cực ngắn (tối đa 12 từ); còn lại để \"\".\n")

    private fun strProp() = JSONObject().put("type", "STRING")
    private fun intProp() = JSONObject().put("type", "INTEGER")
    private fun boolProp() = JSONObject().put("type", "BOOLEAN")

    // taps: các điểm cần chạm; cuon: mức cần kéo trang tiếp theo (đơn vị 0..1000 chiều cao màn hình, âm = kéo ngược lên);
    // retry: AI chưa cho đủ thông tin chắc chắn cho một câu đã hiện trọn -> nên nhìn lại; pending: còn câu chưa làm.
    data class VResult(val taps: List<VTap>, val cuon: Int, val retry: Boolean = false, val pending: Boolean = false, val hint: String = "",
                       val upLabel: String = "", val minLabel: Int = Int.MAX_VALUE, val doneLabels: List<String> = emptyList(),
                       val completeCounts: List<Int> = emptyList())

    private fun visionBody(img: String, mode: Int, fast: Boolean, hint: String = ""): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        parts.put(JSONObject().put("text", "Làm bài trắc nghiệm trong ảnh theo hướng dẫn." +
            (if (hint.isNotEmpty()) " LƯU Ý QUAN TRỌNG: $hint" else "")))
        val gen = JSONObject().put("maxOutputTokens", if (fast) 1500 else 4096)
        if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", if (fast) 0 else 1024))
        if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (mode < 3) {
            val names = if (fast) listOf("so", "dc", "dau", "cuoi", "chon", "x", "ys")
                        else listOf("so", "dc", "dau", "cuoi", "tinh", "chon", "x", "ys")
            val props = JSONObject().put("so", strProp()).put("dc", boolProp()).put("dau", intProp()).put("cuoi", intProp())
                .put("chon", strProp()).put("x", intProp())
                .put("ys", JSONObject().put("type", "ARRAY").put("items", intProp()))
            if (!fast) props.put("tinh", strProp())
            val item = JSONObject().put("type", "OBJECT").put("properties", props)
                .put("propertyOrdering", JSONArray(names))
                .put("required", JSONArray(names))
            gen.put("responseMimeType", "application/json")
            gen.put("responseSchema", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("cau", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray(listOf("cau"))))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", visionRules(fast)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun normKey(s: String): String = s.trim().trimEnd('.', ')', ':').uppercase()

    // Nhãn số của câu ("9", "7/10" -> "7"); rỗng nếu không có số.
    private fun labelOf(so: String): String = Regex("\\d+").find(so)?.value ?: ""

    // Đọc kết quả. App KHÔNG tin hoàn toàn vào AI (đã kiểm chứng bằng mô phỏng hàng nghìn trang giả):
    //  - câu "đủ" = có dòng tiêu đề, có phương án cuối, VÀ có bằng chứng đã hết câu (câu kế tiếp đã hiện tiêu đề, hoặc phương án
    //    cuối cách mép dưới đủ xa để nếu còn phương án nữa thì phải thấy) - tránh chọn khi một phương án nằm ngoài màn hình;
    //  - câu "đã chọn" = kiểm tra bằng điểm ảnh tại các ô tròn (CaptureService.selectionAt), không dựa vào cờ dc;
    //  - toạ độ chạm lấy từ CHÍNH phương án được chọn trong danh sách (y phải tăng dần và khoảng cách khá đều);
    //  - câu CHƯA hiện đủ nhưng đã làm ở màn hình trước (doneSet) thì không kéo ngược lên lại (ô đã chọn có thể nằm ngoài ảnh).
    // relax=true (đã cuộn hết trang mà còn câu chưa làm): bỏ yêu cầu "bằng chứng hết câu" và chấp nhận câu sát mép dưới.
    // upBlocked: các câu đã kéo ngược lên đủ số lần mà vẫn không thấy trọn đầu câu -> bỏ qua, không kéo lên nữa.
    private fun parseVision(text: String, upBlocked: Set<String>, doneSet: Set<String>, relax: Boolean, typical: Int): VResult {
        class Raw(val label: String, val so: String, val dcModel: Boolean, val top: Int, val bot: Int, val chon: String,
                  val x: Int, val keys: List<String>, val ys: List<Int>)
        class Blk(val label: String, val top: Int, val bottom: Int, val complete: Boolean, val done: Boolean, val lastY: Int)
        val raws = ArrayList<Raw>()
        try {
            val a = text.indexOf('{')
            val b = text.lastIndexOf('}')
            if (a >= 0 && b > a) {
                val arr = JSONObject(text.substring(a, b + 1)).optJSONArray("cau")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val so = o.optString("so", "").ifEmpty { "${i + 1}" }
                        val ol = o.optJSONArray("o")
                        val ya = o.optJSONArray("ys")
                        val keys = ArrayList<String>()
                        val ys = ArrayList<Int>()
                        if (ya != null) {
                            for (j in 0 until ya.length()) {
                                ys.add(ya.optInt(j, -1))
                                keys.add(('A' + j).toString())
                            }
                        } else if (ol != null) {
                            for (j in 0 until ol.length()) {
                                val e = ol.optJSONObject(j) ?: continue
                                keys.add(normKey(e.optString("k", "")))
                                ys.add(e.optInt("y", -1))
                            }
                        }
                        val topV = o.optInt("dau", -1)
                        if (so.contains('/')) continue                       // bộ đếm "7/10" ở thanh tiêu đề không phải một câu hỏi
                        if (ys.isEmpty() && topV in 0..60) continue          // dòng dính sát mép trên không có phương án: tiêu đề cố định
                        raws.add(Raw(labelOf(so), so, o.optBoolean("dc", false), o.optInt("dau", -1), o.optInt("cuoi", 1000),
                            normKey(o.optString("chon", "")), o.optInt("x", -1), keys, ys))
                    }
                }
            }
        } catch (e: Exception) {
        }
        val taps = ArrayList<VTap>()
        val blocks = ArrayList<Blk>()
        val need = ArrayList<String>()
        val doneOut = ArrayList<String>()
        val completeOut = ArrayList<Int>()
        var retry = false
        var pending = false
        for ((i, r) in raws.withIndex()) {
            val ys = r.ys
            var listOk = ys.size >= 2 && r.x in 0..1000 && ys.all { it in 0..1000 }
            for (j in 1 until ys.size) if (ys[j] <= ys[j - 1]) listOk = false
            // Khoảng cách giữa các ô phải khá đều: một khoảng lớn gấp ~2 lần khoảng khác nghĩa là AI có thể đã bỏ sót một phương án.
            var uneven = false
            if (listOk && ys.size >= 3) {
                var gmax = 0
                var gmin = Int.MAX_VALUE
                for (j in 1 until ys.size) {
                    val g = ys[j] - ys[j - 1]
                    if (g > gmax) gmax = g
                    if (g < gmin) gmin = g
                }
                if (gmax > 2.2f * gmin && gmax - gmin > 110) { listOk = false; uneven = true }
            }
            val lastY = if (ys.isEmpty()) 0 else ys[ys.size - 1]
            val next = raws.getOrNull(i + 1)
            // Bằng chứng "đã hết câu": câu kế tiếp đã hiện tiêu đề, hoặc phương án cuối cách đáy đủ xa, hoặc câu CAO có tiêu đề đã sát đầu
            // màn hình (không thể đưa lên cao hơn) và phương án cuối còn cách đáy >= 6%.
            val ended = relax || (next != null && next.top >= 0) || lastY <= 860 || (r.top in 0..200 && lastY <= 940)
            val c1 = r.top >= 0 && ys.size >= 2 && r.bot <= (if (relax) 1000 else 985) && ended
            // Hoặc: số phương án nhìn thấy bằng số phương án quen thuộc (học từ các câu đã đủ trước đó, thường là 4) và ô cuối còn trong ảnh.
            val c2 = typical > 0 && ys.size >= typical && r.top >= 0 && lastY <= 975
            val complete = c1 || c2
            if (c1) completeOut.add(ys.size)
            var sel = r.dcModel
            val redIdx = ArrayList<Int>()
            val redKeys = ArrayList<String>()
            if (listOk) {
                var unknown = false
                var anySel = false
                for (j in ys.indices) {
                    val st = CaptureService.selectionAt(r.x, ys[j])
                    if (st == null) unknown = true
                    else if (st == 1) anySel = true
                    else if (st == 2) { redIdx.add(j); redKeys.add(r.keys.getOrElse(j) { "${j + 1}" }) }
                }
                // Chỉ có ô ĐỎ (chọn sai) thì coi là chưa làm xong -> phải chọn lại phương án khác.
                if (!unknown) sel = anySel
            }
            var tapped = false
            if (complete && !sel) {
                var pos = r.keys.indexOf(r.chon)
                if (pos < 0 && r.chon.isNotEmpty()) {
                    val d = r.chon.toIntOrNull()
                    if (d != null) pos = d - 1
                    else if (r.chon.length == 1 && r.chon[0] in 'A'..'Z') pos = r.chon[0] - 'A'
                }
                if (listOk && r.chon.isNotEmpty() && pos in ys.indices && pos !in redIdx) {
                    taps.add(VTap(r.x, ys[pos], "${r.so} ${r.chon}"))
                    tapped = true
                } else {
                    retry = true
                    if (uneven) {
                        need.add("Câu ${r.so}: các toạ độ \"ys\" cách nhau không đều (có thể đã bỏ sót một phương án). Hãy đo lại và liệt kê ĐỦ MỌI phương án nhìn thấy của câu này.")
                    } else if (redIdx.isNotEmpty()) {
                        need.add("Câu ${r.so} đã chọn SAI ở phương án ${redKeys.joinToString("/")} (ô tròn màu đỏ): bắt buộc dc=false và chọn phương án ĐÚNG KHÁC phương án đó.")
                    } else {
                        need.add("Câu ${r.so} đã hiện trọn trong ảnh và CHƯA có ô nào được chọn (các ô tròn đều còn trống): bắt buộc dc=false và chọn đáp án đúng nhất (\"chon\" không được để trống).")
                    }
                }
            }
            val ignoreTop = r.top < 0 && r.label in upBlocked
            val ignoreDone = !complete && r.label.isNotEmpty() && r.label in doneSet && redIdx.isEmpty()
            val done = sel || tapped || ignoreTop || ignoreDone
            blocks.add(Blk(r.label, r.top, r.bot, complete, done, lastY))
            if (!done) pending = true
            if ((sel || tapped) && r.label.isNotEmpty()) doneOut.add(r.label)
        }
        // Mức kéo trang (app tự tính, kiểm chứng bằng mô phỏng):
        //  - câu chưa làm mất đầu ở mép trên -> kéo ngược lên VỪA ĐỦ để phương án cuối về y<=850 (để vẫn đủ bằng chứng "hết câu");
        //  - câu chưa làm bị cắt ở mép dưới -> đưa đầu câu đó lên sát mép trên;
        //  - mọi câu đã đủ và đã làm -> kéo xuống ĐÚNG vị trí phương án cuối của câu thấp nhất, đưa nó lên ANCHOR_Y;
        //    nếu câu thấp nhất đã làm nhưng bị cắt đáy thì đưa đầu câu đó lên trên để thấy phần tiếp theo.
        var cuon = 0
        var upLabel = ""
        val inc = blocks.filter { !it.complete && !it.done }
        val topCut = inc.firstOrNull { it.top < 0 }
        if (topCut != null) {
            val bb = maxOf(if (topCut.bottom < 1000) topCut.bottom else 0, topCut.lastY + 25)
            cuon = -(850 - bb).coerceIn(150, 650)
            upLabel = topCut.label
        } else if (inc.isNotEmpty()) {
            cuon = inc.minOf { it.top } - 80
            // Tiêu đề đã sát đầu màn hình mà câu vẫn chưa đủ (câu quá cao): KHÔNG cuộn kiểu "cả trang" làm mất phần đề, chỉ đi tiếp vừa phải.
            if (cuon < 120) cuon = 420
        } else {
            val low = blocks.maxByOrNull { it.top }
            if (low != null) {
                cuon = if (low.complete) (maxOf(low.bottom, low.lastY + 25) - ANCHOR_Y).coerceIn(120, 850)
                       else if (low.top >= 0) (low.top - 80).coerceIn(120, 850)
                       else 600
            }
        }
        val minLabel = raws.mapNotNull { it.label.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE
        return VResult(taps.sortedBy { it.y }, cuon, retry, pending, need.joinToString(" "), upLabel, minLabel, doneOut, completeOut)
    }

    // ---------- Chọn model / key: bỏ qua model đang hết hạn mức, hỗ trợ nhiều API key ----------
    private val blockedUntil = HashMap<String, Long>()
    private val badKeys = HashSet<String>()

    private fun kid(k: String) = k.takeLast(8)

    // Có thể dán nhiều API key cách nhau dấu phẩy/xuống dòng: mỗi key có hạn mức riêng nên chuyển key khi một key hết quota.
    private fun keysOf(raw: String): List<String> {
        val l = raw.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.length >= 10 }
        return if (l.isEmpty()) listOf(raw.trim()) else l
    }

    // Khoá (key, model) đến khi hết thời gian server yêu cầu chờ (retryDelay); hết hạn mức theo NGÀY thì khoá 1 giờ.
    private fun blockFor(key: String, m: String, resp: String, defSec: Long) {
        var sec = defSec
        val mt = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(resp)
            ?: Regex("retry in (\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(resp)
        if (mt != null) sec = Math.ceil(mt.groupValues[1].toDouble()).toLong() + 1
        if (resp.contains("PerDay", ignoreCase = true) || resp.contains("per day", ignoreCase = true)) sec = maxOf(sec, 3600L)
        blockedUntil[kid(key) + "|" + m] = System.currentTimeMillis() + sec * 1000
    }

    // Gọi lần lượt (model ưu tiên, key) chưa bị khoá. Trả (tên model, chế độ suy nghĩ, phản hồi).
    private fun callModels(apiKeyRaw: String, tag: String, fast: Boolean, bodyOf: (Int) -> JSONObject): Triple<String, Int, String> {
        val keys = keysOf(apiKeyRaw)
        var lastErr = "không có model"
        var attempts = 0
        while (attempts < 20) {
            val base0 = models ?: DEFAULT_MODELS
            val base = if (fast) base0.sortedBy { if (it.contains("lite")) 0 else 1 } else base0
            val now = System.currentTimeMillis()
            var pm: String? = null
            var pk: String? = null
            outer@ for (mm in base) {
                for (kk in keys) {
                    if (badKeys.contains(kid(kk))) continue
                    if ((blockedUntil[kid(kk) + "|" + mm] ?: 0L) <= now) { pm = mm; pk = kk; break@outer }
                }
            }
            if (pm == null || pk == null) {
                if (models == null) {
                    try { loadModels(keys[0]); attempts++; continue } catch (e: Exception) { }
                }
                if (keys.all { badKeys.contains(kid(it)) }) throw RuntimeException("API key không hợp lệ: $lastErr")
                var minT = Long.MAX_VALUE
                for (mm in base) for (kk in keys) {
                    if (!badKeys.contains(kid(kk))) minT = minOf(minT, blockedUntil[kid(kk) + "|" + mm] ?: 0L)
                }
                val wait = ((minT - now) / 1000 + 1).coerceAtLeast(1)
                throw RuntimeException("Hết hạn mức API (429) ở mọi model/key, tự thử lại sau ${wait}s. $lastErr")
            }
            val m: String = pm
            val k: String = pk
            val mk = "$m|$tag"
            var mode = modeOf[mk] ?: 0
            var r = post(k, m, bodyOf(mode), fast)
            while (r.first == 400 && mode < 3 && !r.second.contains("API key", ignoreCase = true)) {
                mode++
                r = post(k, m, bodyOf(mode), fast)
            }
            if (r.first in 200..299) {
                if (mode < 3) modeOf[mk] = mode
                return Triple(m, mode, r.second)
            }
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            when {
                r.first == 429 -> blockFor(k, m, r.second, 45)
                r.first == 404 -> for (kk in keys) blockFor(kk, m, "", 900)
                r.first == 408 || r.first == 500 || r.first == 503 -> blockFor(k, m, "", 15)
                r.first == 401 || r.first == 403 || (r.first == 400 && r.second.contains("API key", ignoreCase = true)) -> {
                    badKeys.add(kid(k))
                    if (keys.all { badKeys.contains(kid(it)) }) throw RuntimeException(lastErr)
                }
                else -> throw RuntimeException(lastErr)
            }
            lastSwitch = "Đã chuyển khỏi $m (key …${kid(k).takeLast(4)}) vì: " + lastErr.take(90)
            attempts++
        }
        throw RuntimeException(lastErr)
    }

    // CHẾ ĐỘ NHÌN ẢNH: dùng khi cây giao diện không đưa nội dung trang web (không có ô chọn để chạm theo [id]).
    // AI đọc ảnh chụp, liệt kê các câu đang hiện (đủ/thiếu, đã chọn/chưa) và chọn đáp án; app tự quyết định chạm và kéo trang.
    fun askVision(apiKey: String, fast: Boolean, upBlocked: Set<String> = emptySet(), hint: String = "", relax: Boolean = false,
                  doneSet: Set<String> = emptySet(), typical: Int = 0): VResult {
        val img = (try { CaptureService.grab(if (fast) 960 else 0) } catch (e: Exception) { null })
            ?: throw RuntimeException(if (CaptureService.blackFrame) "Ảnh chụp bị đen (app chặn chụp màn hình)" else "Chưa có ảnh chụp màn hình (hãy bật 📷)")
        val (m, mode, resp) = callModels(apiKey, "vision|$fast", fast) { md -> visionBody(img, md, fast, hint) }
        val text = replyText(resp)
        lastInfo = "Model: $m | CHẾ ĐỘ NHÌN ẢNH | Nhanh: $fast | Chế độ suy nghĩ: $mode\n" + lastSwitch + "\nAI trả lời:\n$text"
        return parseVision(text, upBlocked, doneSet, relax, typical)
    }

    // Không gọi danh sách model trước (bớt 1 vòng mạng); chỉ dò model khi mọi model mặc định đều lỗi.
    private val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

    // Mở sẵn kết nối HTTPS khi bật nút AI để câu đầu tiên không mất thời gian bắt tay TLS.
    fun warmUp(apiKey: String, fast: Boolean = true) {
        // Chuẩn bị sẵn ảnh chụp (mã hoá JPEG) để lần hỏi đầu tiên không mất thời gian này.
        try { if (CaptureService.isRunning()) CaptureService.grab(if (fast) 960 else 0) } catch (e: Exception) { }
        try {
            val c = URL("$BASE/models/gemini-flash-lite-latest").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("x-goog-api-key", keysOf(apiKey)[0])
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
        val img: String? = if (needImg) (try { CaptureService.grab(if (fast) 960 else 0) } catch (e: Exception) { null }) else null
        val (m, mode, resp) = callModels(apiKey, "$search|$hasInput|$fast", if (search) false else fast) { md ->
            buildBody(screenText, md, img, fast, search, hasInput)
        }
        val text = replyText(resp)
        lastInfo = "Model: $m | Nhanh: $fast | Ảnh: " + (if (img != null && mode < 3) "CÓ" else "KHÔNG") +
            " | Chế độ suy nghĩ: $mode\n" + lastSwitch + "\nAI trả lời:\n$text"
        return parseAnswer(text)
    }
}
