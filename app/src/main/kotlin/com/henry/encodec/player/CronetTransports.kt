package com.henry.encodec.player

import android.content.Context
import com.google.android.gms.net.CronetProviderInstaller
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class NetworkProtocolMode(val label: String) {
    DEFAULT("Default"),
    HTTP3("HTTP/3"),
    HTTP2("HTTP/2"),
    HTTPS("HTTPS"),
    HTTP("Plain HTTP"),
}

internal enum class HttpCompressionPreference(val label: String, val acceptEncoding: String) {
    DEFAULT("Default", "br, gzip;q=0.8, identity;q=0.1"),
    BROTLI("Brotli", "br"),
    GZIP("GZip", "gzip"),
    UNCOMPRESSED("Uncompressed", "identity"),
}

internal object HttpCompressionSettings {
    private const val PREFS = "emergency_radio_settings"
    private const val KEY = "http_compression_preference"
    private val selected = AtomicReference(HttpCompressionPreference.DEFAULT)

    fun initialize(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        selected.set(runCatching { HttpCompressionPreference.valueOf(stored.orEmpty()) }
            .getOrDefault(HttpCompressionPreference.DEFAULT))
    }

    fun current(): HttpCompressionPreference = selected.get()

    fun set(context: Context, preference: HttpCompressionPreference) {
        selected.set(preference)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, preference.name).apply()
        LiveDiagnostics.info("http compression preference=${preference.name} acceptEncoding=${preference.acceptEncoding}")
    }
}

internal object NetworkProtocolSettings {
    private const val PREFS = "emergency_radio_settings"
    private const val KEY = "network_protocol_mode"
    private val selected = AtomicReference(NetworkProtocolMode.DEFAULT)

    fun initialize(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, NetworkProtocolMode.DEFAULT.name)
        selected.set(runCatching { NetworkProtocolMode.valueOf(stored.orEmpty()) }
            .getOrDefault(NetworkProtocolMode.DEFAULT))
    }

    fun current(): NetworkProtocolMode = selected.get()

    fun set(context: Context, mode: NetworkProtocolMode) {
        selected.set(mode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        val scheme = if (mode == NetworkProtocolMode.HTTP) "http" else "https"
        val transport = if (mode == NetworkProtocolMode.HTTP) "plain_http" else "selected_secure_transport"
        LiveDiagnostics.info("network protocol mode=${mode.name} scheme=$scheme transport=$transport")
    }

    /** Keep the selected clear/encrypted scheme consistent for catalogs, manifests and segments. */
    fun applyScheme(url: URL): URL {
        val target = if (current() == NetworkProtocolMode.HTTP) "http" else "https"
        if (url.protocol.equals(target, ignoreCase = true)) return url
        val external = url.toExternalForm()
        return URL(external.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:"), "$target:"))
    }
}

internal enum class HttpTransport(val logName: String) {
    HTTP3("cronet_quic_http2"),
    HTTP2("cronet_http2"),
    PLATFORM("platform_https"),
}

/**
 * Keeps one QUIC/HTTP2 engine and one HTTP2-only engine for the app lifetime.
 * Google Play services supplies Cronet without adding its native libraries to
 * the APK. Platform HTTPS remains the final fallback if Cronet is unavailable.
 */
internal object CronetTransports {
    private data class Engines(val http3: CronetEngine, val http2: CronetEngine)

    private val started = AtomicBoolean(false)
    private val engines = AtomicReference<Engines?>(null)
    private val initializer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ondabaja-cronet-init").apply { isDaemon = true }
    }

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val applicationContext = context.applicationContext
        try {
            CronetProviderInstaller.installProvider(applicationContext).addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    LiveDiagnostics.warn(
                        "transport init unavailable provider error=" +
                            (task.exception?.javaClass?.simpleName ?: "unknown"),
                    )
                    return@addOnCompleteListener
                }
                initializer.execute {
                    runCatching {
                        fun engine(enableQuic: Boolean): CronetEngine {
                            val builder = CronetEngine.Builder(applicationContext)
                                .enableHttp2(true)
                                .enableBrotli(true)
                                .enableQuic(enableQuic)
                            if (enableQuic) {
                                // Persist QUIC server information, but never response bodies.
                                val storage = File(applicationContext.cacheDir, "cronet-server-info/http3")
                                check(storage.mkdirs() || storage.isDirectory) {
                                    "Could not create Cronet server-info directory"
                                }
                                builder.setStoragePath(storage.absolutePath)
                                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 1L shl 20)
                            }
                            return builder.build()
                        }

                        val h3 = engine(enableQuic = true)
                        try {
                            val h2 = engine(enableQuic = false)
                            observeProtocol(h3, HttpTransport.HTTP3)
                            observeProtocol(h2, HttpTransport.HTTP2)
                            engines.set(Engines(h3, h2))
                            LiveDiagnostics.info(
                                "transport ready cronet=${h3.versionString} " +
                                    "http3=enabled http2=enabled brotli=enabled platformFallback=enabled",
                            )
                        } catch (error: Throwable) {
                            h3.shutdown()
                            throw error
                        }
                    }.onFailure { error ->
                        LiveDiagnostics.warn("transport init failed error=${error.javaClass.simpleName}")
                    }
                }
            }
        } catch (error: Throwable) {
            LiveDiagnostics.warn("transport init failed error=${error.javaClass.simpleName}")
        }
    }

    fun isReady(): Boolean = engines.get() != null

    fun displayName(transport: HttpTransport, url: URL): String = when {
        transport == HttpTransport.PLATFORM && url.protocol.equals("http", true) -> "plain_http"
        else -> transport.logName
    }

    private fun observeProtocol(engine: CronetEngine, transport: HttpTransport) {
        engine.addRequestFinishedListener(object : RequestFinishedInfo.Listener(initializer) {
            override fun onRequestFinished(requestInfo: RequestFinishedInfo) {
                val url = runCatching { URL(requestInfo.url) }.getOrNull()
                val response = requestInfo.responseInfo
                val metrics = requestInfo.metrics
                fun elapsedMs(start: java.util.Date?, end: java.util.Date?): String =
                    if (start == null || end == null) "unavailable" else (end.time - start.time).coerceAtLeast(0).toString()
                val contentEncoding = response?.allHeaders
                    ?.entries
                    ?.firstOrNull { it.key.equals("Content-Encoding", ignoreCase = true) }
                    ?.value
                    ?.joinToString(",")
                    ?: "identity"
                LiveDiagnostics.info(
                    "cronet request finished scheme=${url?.protocol ?: "unknown"} " +
                        "transport=${url?.let { displayName(transport, it) } ?: transport.logName} host=${url?.host ?: "unknown"} " +
                    "path=${url?.path ?: "unknown"} protocol=${response?.negotiatedProtocol ?: "unknown"} " +
                    "contentEncoding=$contentEncoding " +
                    "status=${response?.httpStatusCode ?: -1} reason=${requestInfo.finishedReason} " +
                    "socketReused=${metrics.socketReused} " +
                    "dnsMs=${elapsedMs(metrics.dnsStart, metrics.dnsEnd)} " +
                    "connectMs=${elapsedMs(metrics.connectStart, metrics.connectEnd)} " +
                    "tlsMs=${elapsedMs(metrics.sslStart, metrics.sslEnd)} " +
                    "ttfbMs=${metrics.ttfbMs ?: "unavailable"} " +
                    "requestMs=${metrics.totalTimeMs ?: "unavailable"} " +
                    "transportRxBytes=${metrics.receivedByteCount ?: "unavailable"} " +
                    "transportTxBytes=${metrics.sentByteCount ?: "unavailable"}",
                )
            }
        })
    }

    fun <T> withFallback(url: URL, request: (HttpURLConnection) -> T): T {
        val normalizedUrl = NetworkProtocolSettings.applyScheme(url)
        val candidates = transportsForCurrentMode()
        var lastError: IOException? = null
        candidates.forEachIndexed { index, transport ->
            val connection = openConnection(normalizedUrl, transport)
            try {
                // Do not let a cleartext selection silently become encrypted via
                // HttpURLConnection's automatic redirect handling.
                connection.instanceFollowRedirects = NetworkProtocolSettings.current() != NetworkProtocolMode.HTTP
                LiveDiagnostics.info(
                    "http transport request host=${normalizedUrl.host} mode=${NetworkProtocolSettings.current().name} " +
                        "scheme=${normalizedUrl.protocol} acceptEncoding=${HttpCompressionSettings.current().acceptEncoding} " +
                        "transport=${displayName(transport, normalizedUrl)}",
                )
                return request(connection)
            } catch (error: IOException) {
                lastError = error
                if (index < candidates.lastIndex) {
                    LiveDiagnostics.warn(
                        "http transport fallback host=${normalizedUrl.host} scheme=${normalizedUrl.protocol} " +
                            "from=${displayName(transport, normalizedUrl)} " +
                            "to=${displayName(candidates[index + 1], normalizedUrl)} error=${error.javaClass.simpleName}",
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("Could not open an HTTP connection")
    }

    fun transportsForCurrentMode(): List<HttpTransport> = when (NetworkProtocolSettings.current()) {
        NetworkProtocolMode.DEFAULT, NetworkProtocolMode.HTTP3 -> if (isReady()) {
            listOf(HttpTransport.HTTP3, HttpTransport.HTTP2, HttpTransport.PLATFORM)
        } else listOf(HttpTransport.PLATFORM)
        NetworkProtocolMode.HTTP2 -> if (isReady()) listOf(HttpTransport.HTTP2, HttpTransport.PLATFORM)
            else listOf(HttpTransport.PLATFORM)
        NetworkProtocolMode.HTTPS, NetworkProtocolMode.HTTP -> listOf(HttpTransport.PLATFORM)
    }

    @Throws(IOException::class)
    fun openConnection(url: URL, transport: HttpTransport): HttpURLConnection {
        if (transport == HttpTransport.PLATFORM || !url.protocol.equals("https", ignoreCase = true)) {
            return (url.openConnection() as? HttpURLConnection)
                ?: throw IOException("URL did not produce an HTTP connection")
        }
        val snapshot = engines.get()
            ?: throw IOException("${transport.logName} is not ready")
        val engine = when (transport) {
            HttpTransport.HTTP3 -> snapshot.http3
            HttpTransport.HTTP2 -> snapshot.http2
            HttpTransport.PLATFORM -> error("Platform transport handled above")
        }
        return (engine.openConnection(url) as? HttpURLConnection)
            ?: throw IOException("Cronet did not produce an HTTP connection")
    }
}
