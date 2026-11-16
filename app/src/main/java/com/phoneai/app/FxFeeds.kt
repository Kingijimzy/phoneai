package com.phoneai.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Second forex/gold candle source so the desk never goes blind when Yahoo throttles: Deriv's public
 * `ticks_history` (no token, no account). Same Candle type; Yahoo stays primary, this is the failover.
 */
internal object FxFeeds {
    fun granularity(tf: String): Int = when (tf) { "15m" -> 900; "1h", "4h" -> 3600; else -> 86_400 }

    fun count(tf: String): Int = when (tf) { "15m" -> 400; "1h" -> 400; "4h" -> 720; else -> 250 }

    fun request(pair: String, tf: String): String? {
        val symbol = DerivProtocol.symbolFor(pair) ?: return null
        return JSONObject().put("ticks_history", symbol).put("adjust_start_time", 1).put("count", count(tf))
            .put("end", "latest").put("style", "candles").put("granularity", granularity(tf)).toString()
    }

    fun parseDeriv(json: String): List<Candle> {
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val arr = root.optJSONArray("candles") ?: return emptyList()
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (!c.has("open") || !c.has("close")) continue
            out += Candle(c.getLong("epoch") * 1000L, c.getDouble("open"), c.getDouble("high"), c.getDouble("low"), c.getDouble("close"), 0.0)
        }
        return out
    }

    fun deriv(pair: String, tf: String): List<Candle> {
        val msg = request(pair, tf) ?: return emptyList()
        val reply = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
        val ws = http.newWebSocket(Request.Builder().url(DerivProtocol.ENDPOINT + DerivProtocol.DEFAULT_APP_ID).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(msg) }
            override fun onMessage(webSocket: WebSocket, text: String) { reply.set(text); latch.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
        })
        return try {
            latch.await(12, TimeUnit.SECONDS)
            parseDeriv(reply.get().orEmpty()).let { if (tf == "4h") FxData.aggregate(it, 4) else it }
        } catch (_: Exception) { emptyList() } finally { ws.cancel(); http.dispatcher.executorService.shutdown() }
    }

    /** Yahoo first, Deriv when Yahoo returns nothing. */
    fun candles(pair: String, tf: String, primary: (String, String) -> List<Candle>, backup: (String, String) -> List<Candle> = ::deriv): List<Candle> =
        primary(pair, tf).ifEmpty { backup(pair, tf) }
}
