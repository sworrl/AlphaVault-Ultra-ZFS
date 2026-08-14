package com.alphasteg.pro.net

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure UPnP/DLNA request and response shapes, split out so they can be unit-tested
 * without a socket. [DlnaCaster] does the I/O and calls these to build the SSDP
 * probe, read what came back, and compose the SOAP calls that hand a renderer a
 * URL to play.
 *
 * The parsers here read XML that arrived from an unauthenticated device on the
 * local network, so [parseDevice] runs with DTDs and external entities disabled;
 * a hostile "renderer" must not be able to turn discovery into file disclosure.
 */
object Upnp {

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** A parsed SSDP reply: enough to know what answered and where to read about it. */
    data class Ssdp(val location: String, val st: String, val usn: String, val server: String)

    /** A renderer we can drive, resolved from its device description. */
    data class Device(
        val friendlyName: String,
        val modelName: String,
        /** Absolute URL of the AVTransport service's control endpoint. */
        val controlUrl: String
    )

    /** The SSDP discovery datagram. MX is the seconds a device may wait before replying. */
    fun msearch(target: String = MEDIA_RENDERER, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $target\r\n" +
            "\r\n"

    /**
     * Read an SSDP reply. Headers are case-insensitive per HTTP, and devices are
     * inconsistent about casing, so everything is matched lowercased. Returns null
     * unless there is a usable LOCATION to fetch the description from.
     */
    fun parseSsdp(raw: String): Ssdp? {
        val headers = raw.lineSequence()
            .drop(1) // status line
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }
            .toMap()
        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        return Ssdp(
            location = location,
            st = headers["st"].orEmpty(),
            usn = headers["usn"].orEmpty(),
            server = headers["server"].orEmpty()
        )
    }

    /**
     * Pull the friendly name and AVTransport control URL out of a device
     * description. [baseUrl] is the LOCATION it was fetched from, used to resolve
     * the relative control URLs most devices publish. Returns null if the device
     * cannot actually be driven (no AVTransport service).
     */
    fun parseDevice(xml: String, baseUrl: String): Device? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                // The XML came off the network from an unauthenticated peer.
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setXIncludeAware(false) }
            }
            f.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return null

        val services = doc.getElementsByTagName("service")
        var control: String? = null
        for (i in 0 until services.length) {
            val svc = services.item(i) as? Element ?: continue
            val type = svc.childText("serviceType").orEmpty()
            // Match on the service name rather than the exact version string, so a
            // renderer advertising AVTransport:2 or :3 still works.
            if (!type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) continue
            control = svc.childText("controlURL")?.takeIf { it.isNotBlank() }
            if (control != null) break
        }
        val controlUrl = control ?: return null

        val name = doc.getElementsByTagName("friendlyName").firstText()
            ?: doc.getElementsByTagName("modelName").firstText()
            ?: "DLNA renderer"
        val model = doc.getElementsByTagName("modelName").firstText().orEmpty()
        return Device(name.trim(), model.trim(), absoluteUrl(baseUrl, controlUrl))
    }

    /** Resolve a possibly-relative control URL against the description's location. */
    fun absoluteUrl(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return runCatching { java.net.URL(java.net.URL(base), ref).toString() }.getOrDefault(ref)
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** The UPnP item class a renderer uses to decide how to present the stream. */
    fun upnpClass(contentType: String): String = when {
        contentType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
        contentType.startsWith("video/") -> "object.item.videoItem"
        contentType.startsWith("image/") -> "object.item.imageItem.photo"
        else -> "object.item"
    }

    /**
     * DIDL-Lite metadata describing the single item being pushed. Renderers vary
     * in how much they need — many will not start without a `res` element naming
     * the protocol — so this is always sent alongside the URI.
     */
    fun didl(title: String, url: String, contentType: String, sizeBytes: Long?): String {
        val size = sizeBytes?.let { " size=\"$it\"" } ?: ""
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>${upnpClass(contentType)}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$contentType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\"$size>" +
            escapeXml(url) +
            "</res></item></DIDL-Lite>"
    }

    /** The SOAPAction header value for an AVTransport call. */
    fun soapAction(action: String): String = "\"$AV_TRANSPORT#$action\""

    private fun envelope(action: String, inner: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">" +
            inner +
            "</u:$action></s:Body></s:Envelope>"

    /** Hand the renderer the URL to fetch, with its metadata escaped as a string value. */
    fun setAvTransportUri(url: String, didlMetadata: String): String = envelope(
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>"
    )

    fun play(): String = envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stop(): String = envelope("Stop", "<InstanceID>0</InstanceID>")

    /** True if a SOAP reply is a fault rather than a success. */
    fun isSoapFault(response: String): Boolean =
        response.contains("<s:Fault", true) || response.contains("<SOAP-ENV:Fault", true) ||
            response.contains("UPnPError", true)

    // ---- small DOM helpers ----

    private fun Element.childText(tag: String): String? {
        val kids = getElementsByTagName(tag)
        return if (kids.length == 0) null else kids.item(0)?.textContent
    }

    private fun org.w3c.dom.NodeList.firstText(): String? {
        if (length == 0) return null
        val n: Node = item(0) ?: return null
        return n.textContent?.takeIf { it.isNotBlank() }
    }
}
