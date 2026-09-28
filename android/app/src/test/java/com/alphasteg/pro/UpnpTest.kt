package com.alphasteg.pro

import com.alphasteg.pro.net.Upnp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {

    /** A device description shaped like the one HiBy Music's renderer publishes. */
    private fun description(
        avTransportUrl: String = "/AVTransport/control",
        serviceType: String = "urn:schemas-upnp-org:service:AVTransport:1"
    ) = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>HiBy M500</friendlyName>
            <modelName>M500</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/RenderingControl/control</controlURL>
              </service>
              <service>
                <serviceType>$serviceType</serviceType>
                <controlURL>$avTransportUrl</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun msearchIsAWellFormedSsdpProbe() {
        val probe = Upnp.msearch()
        assertTrue(probe.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(probe.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(probe.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(probe.contains("ST: ${Upnp.MEDIA_RENDERER}\r\n"))
        assertTrue("must end with a blank line", probe.endsWith("\r\n\r\n"))
    }

    @Test
    fun parsesAnSsdpReply() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://10.7.7.7:8200/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "SERVER: Linux/3.0 UPnP/1.0 HiByMusic/1.0\r\n\r\n"
        val ssdp = Upnp.parseSsdp(raw)
        assertNotNull(ssdp)
        assertEquals("http://10.7.7.7:8200/desc.xml", ssdp!!.location)
        assertTrue(ssdp.server.contains("HiByMusic"))
    }

    @Test
    fun ssdpHeaderCasingIsIgnored() {
        // Real devices are wildly inconsistent about header casing.
        val raw = "HTTP/1.1 200 OK\r\nlocation: http://192.168.1.5:80/d.xml\r\nSt: x\r\n\r\n"
        assertEquals("http://192.168.1.5:80/d.xml", Upnp.parseSsdp(raw)?.location)
    }

    @Test
    fun ssdpWithoutLocationIsUseless() {
        assertNull(Upnp.parseSsdp("HTTP/1.1 200 OK\r\nST: something\r\n\r\n"))
    }

    @Test
    fun findsAvTransportAndResolvesRelativeControlUrl() {
        val device = Upnp.parseDevice(description(), "http://10.7.7.7:8200/desc.xml")
        assertNotNull(device)
        assertEquals("HiBy M500", device!!.friendlyName)
        assertEquals("M500", device.modelName)
        // Must be the AVTransport control URL, not RenderingControl's, made absolute.
        assertEquals("http://10.7.7.7:8200/AVTransport/control", device.controlUrl)
    }

    @Test
    fun absoluteControlUrlIsLeftAlone() {
        val device = Upnp.parseDevice(
            description(avTransportUrl = "http://10.7.7.7:9000/ctl"),
            "http://10.7.7.7:8200/desc.xml"
        )
        assertEquals("http://10.7.7.7:9000/ctl", device?.controlUrl)
    }

    @Test
    fun laterAvTransportVersionsStillMatch() {
        val device = Upnp.parseDevice(
            description(serviceType = "urn:schemas-upnp-org:service:AVTransport:3"),
            "http://10.7.7.7:8200/desc.xml"
        )
        assertNotNull("a renderer on AVTransport:3 is still drivable", device)
    }

    @Test
    fun deviceWithoutAvTransportIsNotDrivable() {
        val xml = """
            <root><device><friendlyName>Speaker</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/rc</controlURL></service>
            </serviceList></device></root>
        """.trimIndent()
        assertNull(Upnp.parseDevice(xml, "http://host/d.xml"))
    }

    @Test
    fun malformedXmlIsRejectedNotThrown() {
        assertNull(Upnp.parseDevice("<root><device>truncated", "http://host/d.xml"))
        assertNull(Upnp.parseDevice("", "http://host/d.xml"))
    }

    @Test
    fun externalEntitiesAreNotExpanded() {
        // A hostile renderer must not be able to read local files through the
        // description it serves us.
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root><device><friendlyName>&xxe;</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/ctl</controlURL></service></serviceList></device></root>
        """.trimIndent()
        val device = Upnp.parseDevice(xxe, "http://host/d.xml")
        // Either the parse is refused outright or the entity is never expanded;
        // both are acceptable, leaking the file is not.
        assertFalse("XXE must not resolve", device?.friendlyName?.contains("root:") == true)
    }

    @Test
    fun didlCarriesTitleTypeAndSize() {
        val didl = Upnp.didl("Secret Track.flac", "http://10.7.7.9:8973/cast/tok/x.flac", "audio/flac", 4096)
        assertTrue(didl.contains("<dc:title>Secret Track.flac</dc:title>"))
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
        assertTrue(didl.contains("size=\"4096\""))
        assertTrue(didl.contains("http-get:*:audio/flac:"))
    }

    @Test
    fun didlEscapesTheUrlAndTitle() {
        val didl = Upnp.didl("A & B <tag>", "http://h/x?a=1&b=2", "audio/mpeg", null)
        assertTrue(didl.contains("A &amp; B &lt;tag&gt;"))
        assertTrue(didl.contains("a=1&amp;b=2"))
        assertFalse("no bare ampersand may survive", Regex("&(?![a-z]+;)").containsMatchIn(didl))
    }

    @Test
    fun upnpClassFollowsContentType() {
        assertEquals("object.item.audioItem.musicTrack", Upnp.upnpClass("audio/flac"))
        assertEquals("object.item.videoItem", Upnp.upnpClass("video/mp4"))
        assertEquals("object.item.imageItem.photo", Upnp.upnpClass("image/png"))
        assertEquals("object.item", Upnp.upnpClass("application/pdf"))
    }

    @Test
    fun setUriEnvelopeNestsEscapedMetadata() {
        val didl = Upnp.didl("t", "http://h/f", "audio/flac", null)
        val soap = Upnp.setAvTransportUri("http://h/f", didl)
        assertTrue(soap.contains("<u:SetAVTransportURI xmlns:u=\"${Upnp.AV_TRANSPORT}\">"))
        assertTrue(soap.contains("<InstanceID>0</InstanceID>"))
        // The metadata is a string value, so its markup must arrive escaped.
        assertTrue(soap.contains("&lt;DIDL-Lite"))
        assertFalse(soap.contains("<DIDL-Lite"))
    }

    @Test
    fun playAndStopEnvelopes() {
        assertTrue(Upnp.play().contains("<u:Play"))
        assertTrue(Upnp.play().contains("<Speed>1</Speed>"))
        assertTrue(Upnp.stop().contains("<u:Stop"))
    }

    @Test
    fun soapActionIsQuoted() {
        assertEquals("\"${Upnp.AV_TRANSPORT}#Play\"", Upnp.soapAction("Play"))
    }

    @Test
    fun recognisesSoapFaults() {
        assertTrue(Upnp.isSoapFault("<s:Envelope><s:Body><s:Fault>...</s:Fault></s:Body></s:Envelope>"))
        assertTrue(Upnp.isSoapFault("<UPnPError><errorCode>701</errorCode></UPnPError>"))
        assertFalse(Upnp.isSoapFault("<s:Envelope><s:Body><u:PlayResponse/></s:Body></s:Envelope>"))
    }
}
