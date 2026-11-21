package com.turboboost.system

import android.app.Activity
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

object Sh {
    // Executa um comando como shell (uid 2000) através do Shizuku
    fun run(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }
}

class Bridge(private val web: WebView, private val act: Activity) {

    private val pool = Executors.newSingleThreadExecutor()

    private fun send(fn: String, json: String) {
        web.post { web.evaluateJavascript("$fn($json)", null) }
    }

    private fun bg(block: () -> Unit) = pool.execute(block)

    // "off" = serviço do Shizuku não está rodando, "noperm" = sem autorização, "on" = pronto
    private fun state(): String = try {
        when {
            !Shizuku.pingBinder() -> "off"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "noperm"
            else -> "on"
        }
    } catch (e: Throwable) {
        "off"
    }

    private fun memKb(text: String, key: String): Long =
        Regex("$key:\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun memUsedMb(): Long {
        val m = Sh.run("cat /proc/meminfo")
        return (memKb(m, "MemTotal") - memKb(m, "MemAvailable")) / 1024
    }

    private fun dfFreeKb(): Long =
        Sh.run("df -k /data | tail -1 | awk '{print \$4}'").trim().toLongOrNull() ?: 0L

    private fun trimCaches(): Long {
        val before = dfFreeKb()
        Sh.run("pm trim-caches 1000000000000")
        return maxOf(0L, (dfFreeKb() - before) / 1024)
    }

    private fun buildStats(): JSONObject {
        val mem = Sh.run("cat /proc/meminfo")
        val total = memKb(mem, "MemTotal") / 1024
        val used = total - memKb(mem, "MemAvailable") / 1024

        val skip = listOf("com.android", "com.miui", "com.xiaomi", "com.google.android.gms")
        val procs = Sh.run("ps -A -o RSS,NAME").lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val rss = parts[0].toLongOrNull() ?: return@mapNotNull null
            val name = parts[1]
            if (!name.contains('.') || name.contains(':') || skip.any { name.startsWith(it) }) {
                return@mapNotNull null
            }
            name to rss / 1024
        }
            .groupBy({ it.first }, { it.second })
            .map { (n, v) -> n to v.sum() }
            .sortedByDescending { it.second }
            .take(8)

        val procArr = JSONArray()
        procs.forEach { (pkg, mb) ->
            procArr.put(
                JSONObject()
                    .put("pkg", pkg)
                    .put("name", pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
                    .put("mb", mb)
            )
        }

        var thumbs = 0L
        var appCache = 0L
        Sh.run("du -sk /sdcard/DCIM/.thumbnails /sdcard/Android/data/*/cache 2>/dev/null")
            .lines().forEach { line ->
                val p = line.split(Regex("\\s+"), limit = 2)
                if (p.size == 2) {
                    val kb = p[0].toLongOrNull() ?: 0L
                    if (p[1].contains(".thumbnails")) thumbs += kb else appCache += kb
                }
            }

        val cache = JSONArray()
            .put(JSONObject().put("id", "apps").put("label", "Cache de Apps").put("mb", appCache / 1024))
            .put(JSONObject().put("id", "thumbs").put("label", "Miniaturas").put("mb", thumbs / 1024))

        return JSONObject()
            .put("total", total)
            .put("used", used)
            .put("procs", procArr)
            .put("cache", cache)
    }

    @JavascriptInterface
    fun status() = bg { send("onShizuku", "\"${state()}\"") }

    @JavascriptInterface
    fun requestPerm() {
        act.runOnUiThread {
            try {
                if (Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                ) {
                    Shizuku.requestPermission(1001)
                }
            } catch (_: Throwable) { }
        }
    }

    @JavascriptInterface
    fun openShizuku() {
        act.runOnUiThread {
            val intent = act.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                act.startActivity(intent)
            } else {
                send("onNotice", "\"App Shizuku não encontrado. Instale pela Play Store ou GitHub.\"")
            }
        }
    }

    @JavascriptInterface
    fun stats() = bg {
        val json = try {
            buildStats()
        } catch (e: Throwable) {
            JSONObject().put("error", "Erro ao ler dados: ${e.message ?: e.javaClass.simpleName}")
        }
        send("onStats", json.toString())
    }

    @JavascriptInterface
    fun forceStop(pkg: String) = bg {
        if (pkg.matches(Regex("[A-Za-z0-9._]+"))) Sh.run("am force-stop $pkg")
    }

    @JavascriptInterface
    fun freeRam() = bg {
        val before = memUsedMb()
        Sh.run("am kill-all")
        Thread.sleep(1500)
        val freed = maxOf(0L, before - memUsedMb())
        send("onAction", JSONObject().put("freed", freed)
            .put("msg", "RAM liberada: $freed MB recuperados").toString())
    }

    @JavascriptInterface
    fun trimCache() = bg {
        val freed = trimCaches()
        send("onAction", JSONObject().put("freed", freed).put("cleaned", true)
            .put("msg", "Cache limpo: $freed MB liberados").toString())
    }

    @JavascriptInterface
    fun optimize() = bg {
        val before = memUsedMb()
        Sh.run("am kill-all")
        Thread.sleep(1500)
        val ram = maxOf(0L, before - memUsedMb())
        val cache = trimCaches()
        val total = ram + cache
        send("onAction", JSONObject().put("freed", total).put("cleaned", true)
            .put("msg", "Otimização concluída: $total MB liberados").toString())
    }
}
