package com.alphasteg.pro.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * Finds DLNA renderers on the local network and hands them a URL to play.
 *
 * This is the untethered counterpart to plugging a DAC in over USB: the DAP sits
 * on Wi-Fi running whatever renderer it already shipped with (on a HiBy that is
 * HiBy Music's DMRService) and we push a vault file at it. Nothing is installed
 * on the DAP, and nothing here is HiBy-specific — anything answering the standard
 * MediaRenderer discovery works.
 *
 * Worth being clear about the trade against the USB path: casting streams the
 * *decrypted* file across the LAN over plain HTTP, and the URL authenticates
 * itself (see [CastGrants]). The USB path never puts plaintext on a network at
 * all. Prefer USB where fidelity or secrecy matters; cast for convenience.
 *
 * Every call here blocks on network I/O and must run off the main thread.
 */
object DlnaCaster {

    data class Renderer(
        val name: String,
        val model: String,
        val controlUrl: String,
        val location: String
    ) {
        /** Host of the renderer, handy for showing which box on the LAN it is. */
        val host: String get() = runCatching { URL(location).host }.getOrDefault("")
    }

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    /** A device description is small; refuse to read an unbounded body from a peer. */
    private const val MAX_DESCRIPTION_BYTES = 256 * 1024

    /**
     * Broadcast an SSDP search and resolve everything that answers. Runs for
     * roughly [timeoutMs]; renderers stagger their replies up to the MX value, so
     * do not cut this much shorter than a couple of seconds.
     */
    fun discover(timeoutMs: Int = 3000): List<Renderer> {
        val found = LinkedHashMap<String, Renderer>()
        val locations = LinkedHashSet<String>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 600
                socket.broadcast = true
                val probe = Upnp.msearch().toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(Upnp.SSDP_ADDRESS)

                // Send more than once: SSDP is UDP and the first probe is often
                // lost while Wi-Fi power saving wakes the radio up.
                repeat(2) {
                    runCatching {
                        socket.send(DatagramPacket(probe, probe.size, group, Upnp.SSDP_PORT))
                    }
                }

                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val buf = ByteArray(8192)
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!got) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val ssdp = Upnp.parseSsdp(text) ?: continue
                    locations.add(ssdp.location)
                }
            }
        }

        for (location in locations) {
            val xml = fetchDescription(location) ?: continue
            val device = Upnp.parseDevice(xml, location) ?: continue
            found[device.controlUrl] = Renderer(
                name = device.friendlyName,
                model = device.modelName,
                controlUrl = device.controlUrl,
                location = location
            )
        }
        return found.values.toList()
    }

    /**
     * Point [renderer] at [url] and start it. Returns a failure carrying the SOAP
     * fault or transport error, so the UI can say why nothing started playing.
     */
    fun cast(
        renderer: Renderer,
        url: String,
        title: String,
        contentType: String,
        sizeBytes: Long? = null
    ): Result<Unit> {
        val metadata = Upnp.didl(title, url, contentType, sizeBytes)
        return soap(renderer.controlUrl, "SetAVTransportURI", Upnp.setAvTransportUri(url, metadata))
            .mapCatching {
                // Some renderers autoplay on SetAVTransportURI and answer Play with
                // a benign fault; a failure here is not worth surfacing on its own.
                soap(renderer.controlUrl, "Play", Upnp.play())
                Unit
            }
    }

    fun stop(renderer: Renderer): Result<Unit> =
        soap(renderer.controlUrl, "Stop", Upnp.stop()).map { }

    private fun fetchDescription(location: String): String? = runCatching {
        val conn = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.readNBytesCompat(MAX_DESCRIPTION_BYTES)
            String(body, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun soap(controlUrl: String, action: String, body: String): Result<String> = runCatching {
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", Upnp.soapAction(action))
            setRequestProperty("Connection", "close")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reply = stream?.readNBytesCompat(MAX_DESCRIPTION_BYTES)
                ?.toString(Charsets.UTF_8).orEmpty()
            if (code !in 200..299 || Upnp.isSoapFault(reply)) {
                throw java.io.IOException("$action rejected by renderer (HTTP $code)")
            }
            reply
        } finally {
            conn.disconnect()
        }
    }

    /** readNBytes is API 33+; this keeps the minSdk 26 floor. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
