package com.deskstream.client.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.deskstream.client.input.RemoteMouseController
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlin.random.Random

/**
 * Fixed-port (47820) HTTP + WebSocket server that turns a phone browser into a touchpad
 * controller while the TV only displays the stream — screen and control are separated:
 * TV = monitor, phone = controller.
 *
 * Flow: the phone opens `http://<tv-ip>:47820/`, receives the embedded touchpad page, and
 * must authenticate the WebSocket with the 4-digit code shown on the TV before any input is
 * forwarded. Input is dispatched to [RemoteMouseController]'s phone API on the main thread,
 * so phone packets share that class's sequence counters with TV-touch packets — the server's
 * monotonicity check requires exactly one sequence space (PROTOCOL.md §5).
 */
class PhoneControllerServer(private val mouse: RemoteMouseController) {

    companion object {
        private const val TAG = "PhoneControllerServer"
        const val PORT = 47820
        private const val BIND_ATTEMPTS = 4
        private const val BIND_RETRY_MS = 250L
        private const val AUTH_MAX_ATTEMPTS = 5
        private const val AUTH_FAILURE_DELAY_MS = 600L
        private const val HEADER_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        /** This device's LAN IPv4 (for the URL shown on the TV), or null if none is up. */
        fun localAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallback: String? = null
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                        if (addr.isSiteLocalAddress) return addr.hostAddress
                        fallback = fallback ?: addr.hostAddress
                    }
                }
                fallback
            } catch (_: Exception) {
                null
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** Always the fixed [PORT] while running (see PROTOCOL.md §6 — startup is deterministic). */
    @Volatile var port: Int = PORT
        private set

    /** 4-digit pairing code, regenerated each [start]; displayed on the TV. */
    @Volatile var code: String = ""
        private set

    val isRunning: Boolean get() = running

    /**
     * Binds the fixed port with a short retry (restart overlap, e.g. a stream that stopped and
     * restarted immediately), then serves until [stop]. Returns false when the port stays busy —
     * the caller surfaces that on screen; streaming itself is unaffected.
     */
    fun start(): Boolean {
        if (running) return true
        if (code.isEmpty()) code = (1000 + Random.nextInt(9000)).toString()

        var bound: ServerSocket? = null
        for (attempt in 0 until BIND_ATTEMPTS) {
            try {
                bound = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "bind :$PORT attempt ${attempt + 1}/$BIND_ATTEMPTS failed: $e")
                try { bound?.close() } catch (_: Exception) { }
                bound = null
                if (attempt < BIND_ATTEMPTS - 1) Thread.sleep(BIND_RETRY_MS)
            }
        }
        val ss = bound ?: run {
            Log.e(TAG, "port $PORT still busy after $BIND_ATTEMPTS attempts — phone pad disabled")
            return false
        }

        serverSocket = ss
        port = ss.localPort
        running = true
        Thread({
            while (running) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                sockets.add(client)
                Thread({ serveClient(client) }, "PhonePad-conn").apply { isDaemon = true }.start()
            }
        }, "PhonePad-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "phone pad listening on :$port (code $code)")
        return true
    }

    /** Closes the listener and every open connection. Safe to call repeatedly. */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        synchronized(sockets) { sockets.toList() }.forEach { s ->
            try { s.close() } catch (_: Exception) { }
        }
        sockets.clear()
    }

    // ---- HTTP -------------------------------------------------------------------------

    private fun serveClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HEADER_TIMEOUT_MS
            val request = readRequest(socket.getInputStream())
            if (request == null) {
                socket.close()
                return
            }
            val (requestLine, headers) = request
            val upgrade = headers["upgrade"]
            val wsKey = headers["sec-websocket-key"]
            if (upgrade.equals("websocket", ignoreCase = true) && wsKey != null) {
                writeHandshake(socket, wsKey)
                socket.soTimeout = READ_TIMEOUT_MS
                webSocketLoop(socket)
            } else {
                serveHttp(socket, requestLine)
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "client header timeout: $e")
        } catch (e: Exception) {
            Log.d(TAG, "client connection ended: $e")
        } finally {
            sockets.remove(socket)
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /** Reads until CRLFCRLF, returning the request line and lower-cased header map. */
    private fun readRequest(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = ByteArray(MAX_HEADER_BYTES)
        var len = 0
        while (len < buf.size) {
            val b = input.read()
            if (b < 0) return null
            buf[len++] = b.toByte()
            if (len >= 4 &&
                buf[len - 4] == 13.toByte() && buf[len - 3] == 10.toByte() &&
                buf[len - 2] == 13.toByte() && buf[len - 1] == 10.toByte()
            ) break
        }
        if (len >= buf.size) return null
        val lines = String(buf, 0, len, Charsets.ISO_8859_1).split("\r\n")
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase()] =
                    lines[i].substring(colon + 1).trim()
            }
        }
        return lines[0] to headers
    }

    private fun serveHttp(socket: Socket, requestLine: String) {
        val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
        val (status, contentType, body) = when {
            requestLine.startsWith("GET ") && (path == "/" || path == "/index.html") ->
                Triple("200 OK", "text/html; charset=utf-8", PAGE.toByteArray(Charsets.UTF_8))
            requestLine.startsWith("GET ") && path == "/favicon.ico" ->
                Triple("204 No Content", "", ByteArray(0))
            else ->
                Triple("404 Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
        }
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            if (contentType.isNotEmpty()) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) write(body)
            flush()
        }
    }

    // ---- WebSocket --------------------------------------------------------------------

    private fun writeHandshake(socket: Socket, key: String) {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        val accept = Base64.getEncoder().encodeToString(digest)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        socket.getOutputStream().apply {
            write(response.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    private fun webSocketLoop(socket: Socket) {
        val input = socket.getInputStream()
        var authed = false
        var authAttempts = 0
        while (running) {
            val frame = try {
                readFrame(input)
            } catch (_: SocketTimeoutException) {
                Log.d(TAG, "websocket read timeout — dropping idle client")
                break
            } catch (e: Exception) {
                Log.d(TAG, "websocket read ended: $e")
                break
            } ?: break

            when (frame.first) {
                OPCODE_TEXT -> {
                    val msg = try {
                        JSONObject(String(frame.second, Charsets.UTF_8))
                    } catch (_: Exception) {
                        continue
                    }
                    val type = msg.optString("t")
                    // Everything except auth is ignored until the TV's code has been verified.
                    if (!authed && type != "auth") continue
                    when (type) {
                        "auth" -> {
                            if (msg.optString("code") == code) {
                                authed = true
                                sendJson(socket, """{"t":"authok"}""")
                                Log.i(TAG, "phone pad authenticated")
                            } else {
                                authAttempts++
                                try { Thread.sleep(AUTH_FAILURE_DELAY_MS) } catch (_: Exception) { }
                                sendJson(socket, """{"t":"authfail"}""")
                                if (authAttempts >= AUTH_MAX_ATTEMPTS) {
                                    Log.w(TAG, "too many bad codes — closing connection")
                                    break
                                }
                            }
                        }
                        "ping" -> sendJson(socket, """{"t":"pong"}""")
                        "move" -> {
                            val dx = msg.optDouble("dx", 0.0).toFloat()
                            val dy = msg.optDouble("dy", 0.0).toFloat()
                            if (dx != 0f || dy != 0f) {
                                mainHandler.post { mouse.phoneMove(dx, dy) }
                            }
                        }
                        "scroll" -> {
                            val units = msg.optInt("d", 0)
                            if (units != 0) mainHandler.post { mouse.phoneScroll(units) }
                        }
                        "click" -> {
                            val button = if (msg.optString("b") == "right") "right" else "left"
                            mainHandler.post { mouse.phoneClick(button) }
                        }
                        "button" -> {
                            val button = if (msg.optString("b") == "right") "right" else "left"
                            val down = msg.optBoolean("down", false)
                            mainHandler.post { mouse.phoneButton(button, down) }
                        }
                    }
                }
                OPCODE_CLOSE -> {
                    // Close status 1000 (normal), big-endian.
                    sendFrame(socket, OPCODE_CLOSE, byteArrayOf(0x03, 0xE8.toByte()))
                    break
                }
                OPCODE_PING -> sendFrame(socket, OPCODE_PONG, frame.second)
                OPCODE_PONG -> { /* keep-alive from the phone; nothing to do */ }
                else -> break // unknown opcode (incl. fragmented continuations) — drop client
            }
        }
    }

    /** Returns opcode to payload for one frame, or null when the stream ends/overflows. */
    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = b1 and 0x7F
        when (length) {
            126 -> {
                val hi = input.read()
                val lo = input.read()
                if (hi < 0 || lo < 0) return null
                length = (hi shl 8) or lo
            }
            127 -> return null // touchpad JSON never needs 64-bit lengths
        }
        if (length < 0 || length > MAX_FRAME_BYTES) return null
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length)
        readFully(input, payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val read = input.read(target, off, target.size - off)
            if (read < 0) throw java.io.EOFException("stream closed mid-frame")
            off += read
        }
    }

    private fun sendFrame(socket: Socket, opcode: Int, payload: ByteArray) {
        if (socket.isClosed) return
        if (payload.size >= 65536) return
        val out = socket.getOutputStream()
        synchronized(socket) {
            out.write(0x80 or opcode)
            if (payload.size < 126) {
                out.write(payload.size)
            } else {
                out.write(126)
                out.write(payload.size shr 8 and 0xFF)
                out.write(payload.size and 0xFF)
            }
            out.write(payload)
            out.flush()
        }
    }

    private fun sendJson(socket: Socket, json: String) =
        sendFrame(socket, OPCODE_TEXT, json.toByteArray(Charsets.UTF_8))

    // ---- Embedded page ---------------------------------------------------------------
    // Single self-contained document: no external assets (the TV may be offline beyond the
    // LAN) and no "$" anywhere so the Kotlin raw string never interpolates.

    private val PAGE = """
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>DeskStream リモート</title>
<style>
html,body{margin:0;height:100%;background:#0d1117;color:#e6edf3;overscroll-behavior:none;
  font-family:system-ui,-apple-system,sans-serif;touch-action:none;-webkit-user-select:none;user-select:none}
#status{position:fixed;top:0;left:50%;transform:translateX(-50%);z-index:9;font-size:12px;
  padding:6px 14px;margin-top:8px;border-radius:999px;background:#161b22aa;pointer-events:none}
#auth{position:absolute;inset:0;z-index:10;display:flex;flex-direction:column;align-items:center;
  justify-content:center;gap:16px;background:#0d1117}
#auth .title{font-size:22px;font-weight:600}
#auth .sub{font-size:14px;color:#8b949e;text-align:center;line-height:1.6;padding:0 24px}
#code{font-size:32px;letter-spacing:14px;text-indent:14px;text-align:center;width:170px;
  padding:12px 0;background:#161b22;border:1px solid #30363d;border-radius:12px;color:#e6edf3;outline:none}
#code:focus{border-color:#2f81f7}
#connect{font-size:16px;padding:12px 40px;border-radius:12px;border:0;background:#238636;
  color:#fff;font-weight:600}
#connect:active{background:#2ea043}
#autherr{font-size:13px;color:#f85149;min-height:18px}
#pad{position:absolute;inset:0;display:none;background:
  radial-gradient(circle at 50% 45%,#161b22 0%,#0d1117 75%)}
#padhint{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#30363d;
  font-size:14px;text-align:center;line-height:2;pointer-events:none}
#padbtns{position:absolute;left:0;right:0;bottom:0;z-index:5;display:none;gap:12px;
  justify-content:center;padding:14px 16px calc(14px + env(safe-area-inset-bottom))}
#padbtns button{flex:1;max-width:220px;height:56px;font-size:16px;border-radius:14px;
  border:1px solid #30363d;background:#21262d;color:#e6edf3}
#padbtns button:active{background:#30363d}
</style>
</head>
<body>
<div id="status">未接続</div>

<div id="auth">
  <div class="title">DeskStream リモート</div>
  <div class="sub">TV に表示されている<br>4 桁のコードを入力してください</div>
  <input id="code" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off">
  <button id="connect">接続</button>
  <div id="autherr"></div>
</div>

<div id="pad">
  <div id="padhint">1本: 移動 / タップ: 左クリック<br>
長押し: ダブルタップからのドラッグ<br>
2本: スクロール / 2本タップ: 右クリック</div>
</div>

<div id="padbtns">
  <button id="bleft">左クリック</button>
  <button id="bright">右クリック</button>
</div>

<script>
(function(){
  var authBox=document.getElementById('auth'),codeInput=document.getElementById('code'),
      connectBtn=document.getElementById('connect'),authErr=document.getElementById('autherr'),
      pad=document.getElementById('pad'),btns=document.getElementById('padbtns'),
      stat=document.getElementById('status');
  var ws=null,authed=false,everAuthed=false,retries=0,reconnTimer=null,pingTimer=null,
      pendingCode='';
  var SENS=2.0,SCROLL_UNITS_PER_PX=6,CODE_KEY='deskstream-code';

  function status(t){stat.textContent=t}
  function send(o){if(ws&&ws.readyState===1&&authed)ws.send(JSON.stringify(o))}
  function showPad(){authBox.style.display='none';pad.style.display='block';
    btns.style.display='flex';everAuthed=true;status('接続済み')}
  function showAuth(msg){authBox.style.display='flex';pad.style.display='none';
    btns.style.display='none';authErr.textContent=msg||''}

  function openSocket(){
    try{ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws')}
    catch(e){scheduleReconnect();return}
    ws.onopen=function(){
      retries=0;status('コード確認中');
      pendingCode=pendingCode||localStorage.getItem(CODE_KEY)||codeInput.value.trim();
      ws.send(JSON.stringify({t:'auth',code:pendingCode}));
      if(pingTimer)clearInterval(pingTimer);
      pingTimer=setInterval(function(){if(ws&&ws.readyState===1)ws.send('{"t":"ping"}')},15000);
    };
    ws.onmessage=function(ev){
      var m;try{m=JSON.parse(ev.data)}catch(e){return}
      if(m.t==='authok'){
        authed=true;
        if(pendingCode)localStorage.setItem(CODE_KEY,pendingCode);
        showPad();
      }else if(m.t==='authfail'){
        authed=false;pendingCode='';
        showAuth('コードが違います');
        status('未接続');
      }
    };
    ws.onclose=function(){
      authed=false;
      if(pingTimer){clearInterval(pingTimer);pingTimer=null}
      status(everAuthed?'再接続中…':'未接続');
      scheduleReconnect();
    };
    ws.onerror=function(){};
  }
  function scheduleReconnect(){
    if(reconnTimer)clearTimeout(reconnTimer);
    retries++;
    reconnTimer=setTimeout(openSocket,Math.min(8000,700*retries));
  }

  function doConnect(){
    var typed=codeInput.value.trim();
    if(typed.length<4){authErr.textContent='4桁のコードを入力してください';return}
    pendingCode=typed;
    if(ws&&ws.readyState===1)ws.send(JSON.stringify({t:'auth',code:typed}));
    else openSocket();
  }
  connectBtn.addEventListener('click',doConnect);
  codeInput.addEventListener('keydown',function(e){if(e.key==='Enter')doConnect()});

  // ---- gestures -------------------------------------------------------------------
  var g={id:null,lx:0,ly:0,t0:0,dist:0,drag:false,hold:0,
         two:false,twoT0:0,twoDist:0,twoLastY:0,scrollAcc:0};

  function releaseDrag(){if(g.hold){clearTimeout(g.hold);g.hold=0}
    if(g.drag){send({t:'button',b:'left',down:false});g.drag=false}}

  pad.addEventListener('touchstart',function(e){
    e.preventDefault();
    if(!authed)return;
    if(e.touches.length>=2){
      releaseDrag();g.id=null;
      g.two=true;g.twoT0=Date.now();g.twoDist=0;
      g.twoLastY=(e.touches[0].clientY+e.touches[1].clientY)/2;
      return;
    }
    if(g.two||g.id!==null)return;
    var t=e.changedTouches[0];
    g.id=t.identifier;g.lx=t.clientX;g.ly=t.clientY;
    g.t0=Date.now();g.dist=0;g.drag=false;
    g.hold=setTimeout(function(){
      if(g.id!==null&&g.dist<12){
        g.drag=true;send({t:'button',b:'left',down:true});status('ドラッグ中');
      }
    },450);
  },{passive:false});

  pad.addEventListener('touchmove',function(e){
    e.preventDefault();
    if(!authed)return;
    if(g.two&&e.touches.length>=2){
      var cy=(e.touches[0].clientY+e.touches[1].clientY)/2;
      var dy=g.twoLastY-cy;
      g.twoLastY=cy;
      g.twoDist+=Math.abs(dy);
      g.scrollAcc+=dy*SCROLL_UNITS_PER_PX;
      var units=Math.round(g.scrollAcc);
      if(units!==0){send({t:'scroll',d:units});g.scrollAcc-=units}
      return;
    }
    if(g.id===null)return;
    for(var i=0;i<e.changedTouches.length;i++){
      var t=e.changedTouches[i];
      if(t.identifier!==g.id)continue;
      var dx=(t.clientX-g.lx)*SENS,dy2=(t.clientY-g.ly)*SENS;
      g.lx=t.clientX;g.ly=t.clientY;
      g.dist+=Math.abs(dx)+Math.abs(dy2);
      if(g.dist>14&&g.hold&&!g.drag){clearTimeout(g.hold);g.hold=0}
      var ix=Math.round(dx),iy=Math.round(dy2);
      if(ix!==0||iy!==0)send({t:'move',dx:ix,dy:iy});
    }
  },{passive:false});

  function endTouch(e){
    e.preventDefault();
    if(g.two&&e.touches.length<2){
      var dur=Date.now()-g.twoT0;
      g.two=false;
      if(g.twoDist<14&&dur<400)send({t:'click',b:'right'});
      return;
    }
    for(var i=0;i<e.changedTouches.length;i++){
      var t=e.changedTouches[i];
      if(t.identifier!==g.id)continue;
      var dur1=Date.now()-g.t0;
      if(g.hold){clearTimeout(g.hold);g.hold=0}
      if(g.drag){
        send({t:'button',b:'left',down:false});g.drag=false;status('接続済み');
      }else if(g.dist<14&&dur1<400){
        send({t:'click',b:'left'});
      }
      g.id=null;
    }
  }
  pad.addEventListener('touchend',endTouch,{passive:false});
  pad.addEventListener('touchcancel',endTouch,{passive:false});

  function bindButton(id,button){
    var el=document.getElementById(id);
    el.addEventListener('touchstart',function(e){
      e.preventDefault();send({t:'click',b:button});
    },{passive:false});
    el.addEventListener('mousedown',function(e){
      e.preventDefault();send({t:'click',b:button});
    });
  }
  bindButton('bleft','left');
  bindButton('bright','right');

  window.addEventListener('beforeunload',function(){releaseDrag()});

  // ---- init ------------------------------------------------------------------------
  var saved=localStorage.getItem(CODE_KEY);
  if(saved){
    codeInput.value=saved;
    openSocket();
  }else{
    showAuth('');
  }
})();
</script>
</body>
</html>
"""
}
