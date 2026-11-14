package com.openconverter.app.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bilibili / YouTube reachability + UA risk-control probe — runs ON the
 * device so the network stack and Android's default UA are exactly what
 * production sees. Writes `oc_probe_report.json` into the app's external
 * files dir; soft-checks only (a probe documents, it does not gate).
 *
 * Reference values from the desktop probe (2026-11-13,
 * reports/openconverter_round3_20261113/link_audio_probe_report.md):
 * view API with a browser UA without cookies trips HTTP 412; the audio CDN
 * requires Referer and non-HEAD reads.
 */
@RunWith(AndroidJUnit4::class)
class LinkProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val report = JSONArray()

    private fun get(url: String, ua: String?, referer: String? = null, timeoutMs: Int = 12_000): Triple<Int, String?, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (ua != null) setRequestProperty("User-Agent", ua)
                if (referer != null) setRequestProperty("Referer", referer)
                connect()
            }
            val code = conn.responseCode
            val body = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(600) } } catch (_: Exception) { null }
            }
            Triple(code, body, "ok")
        } catch (e: Exception) {
            Triple(0, null, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun record(name: String, detail: JSONObject) {
        detail.put("probe", name)
        report.put(detail)
        println("PROBE $name → $detail")
    }

    @Test fun probe_bilibili_and_youtube_from_device() {
        val bvid = "BV1fx411N7bU"
        var cid: Long? = null

        // 1. Bilibili view API with Android default UA (no header set → Dalvik UA)
        run {
            val (code, body, err) = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", null)
            val apiCode = body?.let { runCatching { JSONObject(it).optInt("code", -999) }.getOrDefault(-999) }
            cid = body?.let {
                runCatching {
                    JSONObject(it).getJSONObject("data").getJSONArray("pages").getJSONObject(0).getLong("cid")
                }.getOrNull()
            }
            record("bili_view_default_ua", JSONObject()
                .put("http", code).put("api_code", apiCode).put("cid", cid).put("err", err))
        }

        // 2. Bilibili view API with a browser UA (desktop probe: 412 risk control)
        run {
            val (code, _, err) = get(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
            )
            record("bili_view_browser_ua", JSONObject().put("http", code).put("err", err))
        }

        // 3. Bilibili playurl fnval=16 — DASH audio availability (needs cid)
        if (cid != null) {
            val (code, body, err) = get(
                "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16", null)
            val audioCount = body?.let {
                runCatching {
                    JSONObject(it).getJSONObject("data").getJSONObject("dash").getJSONArray("audio").length()
                }.getOrDefault(0)
            } ?: 0
            record("bili_playurl_dash_audio", JSONObject()
                .put("http", code).put("audio_streams", audioCount).put("err", err))
        }

        // 4. YouTube reachability with the default UA
        run {
            val (code, _, err) = get("https://www.youtube.com/", null, timeoutMs = 10_000)
            record("youtube_reachability_default_ua", JSONObject().put("http", code).put("err", err))
        }

        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(outDir, "oc_probe_report.json").writeText(
        // also mirror into the internal files dir so `adb shell run-as` can
        // pull it (scoped storage blocks shell access to Android/data):
        
            JSONObject().put("generated_at", System.currentTimeMillis())
                .put("device_model", android.os.Build.MODEL)
                .put("android_version", android.os.Build.VERSION.RELEASE)
                .put("results", report).toString(2),
        )
        File(context.filesDir, "oc_probe_report.json").writeText(
            JSONObject().put("generated_at", System.currentTimeMillis())
                .put("results", report).toString(2),
        )
    }
}
