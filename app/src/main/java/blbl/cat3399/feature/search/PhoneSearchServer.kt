package blbl.cat3399.feature.search

import blbl.cat3399.core.log.AppLog
import org.json.JSONObject
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tiny LAN HTTP server used by the QR search dialog.
 *
 * The phone page is intentionally dependency-free: it uses only HTML/CSS/JS so the
 * phone's own IME works in both portrait and landscape. All state changes are pushed
 * back to [onQueryChanged] / [onSearchRequested] on server worker threads.
 */
class PhoneSearchServer {
    enum class Engine(val wire: String) {
        BILI("bili"),
        IQIYI("iqiyi"),
        MANGO("mango"),
    }

    var onQueryChanged: ((String) -> Unit)? = null
    var onSearchRequested: ((Engine, String) -> Unit)? = null

    @Volatile private var query: String = ""
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverUrl: String? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    fun start(): String? {
        serverSocket?.let { return serverUrl }
        return runCatching {
            val socket = ServerSocket(0)
            val ip = localIpv4() ?: run {
                socket.close()
                return null
            }
            serverSocket = socket
            workers = Executors.newFixedThreadPool(4)
            serverUrl = "http://$ip:${socket.localPort}/"
            AppLog.i("PhoneSearch", "server started url=$serverUrl")
            acceptThread = Thread({
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    workers?.execute {
                        runCatching { handle(client) }
                            .onFailure { AppLog.w("PhoneSearch", "request failed", it) }
                    }
                }
            }, "PhoneSearchServer").apply {
                isDaemon = true
                start()
            }
            serverUrl
        }.getOrNull()
    }

    fun stop() {
        serverUrl = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        workers?.shutdownNow()
        workers = null
    }

    fun currentQuery(): String = query

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = client.getInputStream().buffered()
            val headerBytes = readHeader(input) ?: return
            val lines = String(headerBytes, Charsets.UTF_8).split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
            val path = target.substringBefore('?')
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 32_768) ?: 0
            val body = String(readExactly(input, contentLength), Charsets.UTF_8)
            when {
                method == "GET" && path == "/" -> {
                    respond(client, 200, "text/html; charset=utf-8", htmlPage())
                }

                method == "GET" && path == "/api/state" -> {
                    respond(client, 200, "application/json; charset=utf-8", JSONObject().put("query", query).toString())
                }

                method == "POST" && path == "/api/query" -> {
                    val text = runCatching { JSONObject(body).optString("query") }.getOrDefault("")
                    updateQuery(text)
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                method == "POST" && path == "/api/search" -> {
                    val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    val text = json.optString("query")
                    val engine = Engine.entries.firstOrNull { it.wire == json.optString("engine") } ?: Engine.BILI
                    updateQuery(text)
                    onSearchRequested?.invoke(engine, query)
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                else -> respond(client, 404, "text/plain; charset=utf-8", "Not Found")
            }
        }
    }

    private fun readHeader(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var state = 0
        while (out.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(data, read, length - read)
            if (count <= 0) break
            read += count
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun updateQuery(value: String) {
        val text = value.take(MAX_QUERY_LENGTH)
        if (text == query) return
        query = text
        onQueryChanged?.invoke(text)
    }

    private fun respond(client: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val output: OutputStream = client.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun localIpv4(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .mapNotNull { nic ->
                    val preferred = nic.name.startsWith("wlan") || nic.name.startsWith("eth")
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                        ?.let { preferred to it.hostAddress }
                }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { it.first }?.second ?: candidates.firstOrNull()?.second
    }

    private fun htmlPage(): String = HTML
        .replace("__QUERY_HTML__", escapeHtml(query))

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 200
        private val HTML = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#121212">
<title>手机搜索</title>
<style>
:root{
  color-scheme: dark;
  --bg:#121212; --surface:#1E1E1E; --surface2:#2C2C2E;
  --text:#F2F2F2; --secondary:#B3B3B3; --accent:#F58AAF; --blue:#0A84FF;
  --border:rgba(255,255,255,.10); --radius:16px;
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{min-height:100%}
body{
  margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Helvetica Neue",Arial,sans-serif;
  display:flex;justify-content:center;align-items:flex-start;
  padding:calc(18px + env(safe-area-inset-top)) 16px calc(18px + env(safe-area-inset-bottom));
  min-height:100dvh;
}
.card{
  width:min(760px,100%);background:var(--surface);border:1px solid var(--border);
  border-radius:24px;padding:20px;box-shadow:0 20px 60px rgba(0,0,0,.42);
}
header h1{font-size:22px;line-height:1.2;margin:0 0 5px;letter-spacing:-.02em}
header p{font-size:14px;line-height:1.4;color:var(--secondary);margin:0 0 16px}
textarea{
  display:block;width:100%;min-height:34vh;resize:vertical;
  background:var(--surface2);color:var(--text);border:1px solid var(--border);
  border-radius:16px;padding:14px 15px;font:inherit;font-size:17px;line-height:1.45;
  outline:none;caret-color:var(--accent);
}
textarea::placeholder{color:#7A7A7E}
textarea:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(245,138,175,.20)}
.side{margin-top:12px}
.row{display:flex;gap:10px;flex-wrap:wrap}
.row + .row{margin-top:10px}
button{
  flex:1 1 0;min-width:0;min-height:50px;border:0;border-radius:14px;
  font:inherit;font-size:16px;font-weight:600;cursor:pointer;
  transition:transform .12s ease,opacity .12s ease;touch-action:manipulation;
}
button:active{transform:scale(.98);opacity:.82}
.secondary{background:var(--surface2);color:var(--text);border:1px solid var(--border)}
.primary{background:var(--accent);color:#1C1C1E}
.primary.mango{background:var(--blue);color:#fff}
.status{min-height:19px;margin:14px 2px 0;color:var(--secondary);font-size:13px;line-height:1.4}
@media (orientation:landscape) and (max-height:620px){
  body{align-items:center;padding:10px 18px}
  .card{
    width:min(1040px,100%);padding:16px;display:grid;
    grid-template-columns:minmax(0,1.15fr) minmax(280px,.85fr);
    column-gap:18px;align-items:start;
  }
  header{grid-column:1 / -1}
  header p{margin-bottom:10px}
  textarea{grid-column:1;grid-row:2 / span 2;min-height:44vh;height:100%}
  .side{grid-column:2;grid-row:2;margin-top:0}
  .status{grid-column:1 / -1;margin-top:10px}
}
@media (max-width:420px){
  body{padding-left:12px;padding-right:12px}
  .card{padding:16px;border-radius:20px}
  button{font-size:15px}
}
</style>
</head>
<body>
<main class="card">
  <header>
    <h1>手机搜索</h1>
    <p>输入会实时同步到电视；可直接选择“搜哔哩哔哩”“搜爱奇艺”或“搜芒果”。</p>
  </header>
  <textarea id="q" maxlength="200" placeholder="输入要搜索的内容…" autofocus>__QUERY_HTML__</textarea>
  <section class="side">
    <div class="row">
      <button class="secondary" id="selectAll" type="button">全选</button>
      <button class="secondary" id="deleteSel" type="button">删除选区</button>
      <button class="secondary" id="clear" type="button">清空</button>
    </div>
    <div class="row">
      <button class="primary" id="searchBili" type="button">搜哔哩哔哩</button>
      <button class="primary iqiyi" id="searchIqiyi" type="button">搜爱奇艺</button>
      <button class="primary mango" id="searchMango" type="button">搜芒果</button>
    </div>
  </section>
  <p class="status" id="status">等待输入…</p>
</main>
<script>
const q = document.getElementById('q');
const status = document.getElementById('status');
let timer = null;
let sending = false;
function setStatus(text){ status.textContent = text; }
function api(path, body){
  return fetch(path, {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify(body)
  }).then(function(r){ if(!r.ok) throw new Error('http'); return r.json(); });
}
function syncNow(){
  clearTimeout(timer);
  if (sending) return Promise.resolve();
  sending = true;
  return api('/api/query', {query: q.value})
    .then(function(){ setStatus('已同步到电视'); })
    .catch(function(){ setStatus('同步失败，请检查电视连接'); })
    .finally(function(){ sending = false; });
}
function scheduleSync(){
  clearTimeout(timer);
  timer = setTimeout(syncNow, 220);
}
q.addEventListener('input', function(){ setStatus('输入中…'); scheduleSync(); });
document.getElementById('selectAll').addEventListener('click', function(){
  q.focus(); q.select(); setStatus('已全选');
});
document.getElementById('deleteSel').addEventListener('click', function(){
  const start = q.selectionStart;
  const end = q.selectionEnd;
  if (start !== end) {
    q.setRangeText('', start, end, 'end');
  } else if (q.value.length > 0) {
    q.setRangeText('', Math.max(0, q.value.length - 1), q.value.length, 'end');
  }
  q.focus();
  setStatus('已删除');
  syncNow();
});
document.getElementById('clear').addEventListener('click', function(){
  q.value = '';
  q.focus();
  setStatus('已清空');
  syncNow();
});
function submit(engine){
  clearTimeout(timer);
  api('/api/search', {engine: engine, query: q.value})
    .then(function(){ setStatus('已发送到电视'); })
    .catch(function(){ setStatus('发送失败，请检查电视连接'); });
}
document.getElementById('searchBili').addEventListener('click', function(){ submit('bili'); });
document.getElementById('searchIqiyi').addEventListener('click', function(){ submit('iqiyi'); });
document.getElementById('searchMango').addEventListener('click', function(){ submit('mango'); });
q.focus();
q.setSelectionRange(q.value.length, q.value.length);
</script>
</body>
</html>
""".trimIndent()
    }
}
