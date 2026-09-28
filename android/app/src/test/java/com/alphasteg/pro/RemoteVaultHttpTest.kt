package com.alphasteg.pro

import com.alphasteg.pro.data.VaultVolume.Entry
import com.alphasteg.pro.data.VaultVolume.Index
import com.alphasteg.pro.net.RemoteVault
import com.alphasteg.pro.net.VaultDav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives [RemoteVault] against a real socket, so the parts that only exist on the
 * wire are covered: the Basic credentials, the request the client actually sends,
 * percent-encoding, and what happens when the far end refuses or is not there.
 * The stand-in server answers with the same [VaultDav] output the real one
 * produces, so client and server cannot drift apart unnoticed.
 *
 * This suite earned its keep immediately: the first version of the client sent
 * WebDAV's PROPFIND, which HttpURLConnection rejects outright, and every listing
 * failed silently.
 */
class RemoteVaultHttpTest {

    private lateinit var server: ServerSocket
    private lateinit var thread: Thread
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var acceptToken = "s3cret-token"
    @Volatile private var fileBody = "hidden bytes".toByteArray()

    private val index = Index(
        generation = 1,
        entries = listOf(
            Entry("id1", "song.flac", 12, 12, 4, 12, 4, 0L, path = "/"),
            Entry("id2", "photo.jpg", 34, 12, 4, 34, 4, 0L, path = "/Photos")
        ),
        folders = listOf("/Photos")
    )

    @Before
    fun startServer() {
        server = ServerSocket(0)
        thread = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun stopServer() {
        runCatching { server.close() }
    }

    private fun serve(socket: java.net.Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
        }
        requests.add(requestLine)

        val out = socket.getOutputStream()
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("vault:$acceptToken".toByteArray())
        if (headers["authorization"] != expected) {
            out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val method = requestLine.substringBefore(' ')
        val path = java.net.URLDecoder.decode(requestLine.split(" ")[1], "UTF-8")

        val wantsListing = requestLine.contains(VaultDav.JSON_QUERY)
        if (method == "GET" && wantsListing) {
            val dir = path.substringBefore('?').removeSuffix("/").ifEmpty { "/" }
            respond(out, "200 OK", "application/json", VaultDav.jsonListing(index, dir).toByteArray())
        } else {
            respond(out, "200 OK", "application/octet-stream", fileBody)
        }
    }

    private fun respond(out: java.io.OutputStream, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun client(token: String = acceptToken) =
        RemoteVault("127.0.0.1", server.localPort, token)

    // ---- tests ----

    @Test
    fun listsTheRootOverHttp() {
        val items = client().list("/")

        assertEquals(setOf("Photos", "song.flac"), items.map { it.name }.toSet())
        assertTrue(items.first { it.name == "Photos" }.isFolder)
    }

    @Test
    fun listingUsesAPlainGetSoHttpUrlConnectionAccceptsIt() {
        client().list("/")

        // PROPFIND would be the WebDAV-native verb, but HttpURLConnection refuses
        // any method outside its fixed list, so the client asks over GET instead.
        val seen = requests.single()
        assertTrue("expected GET, got $seen", seen.startsWith("GET "))
        assertTrue("expected the JSON listing query, got $seen", seen.contains(VaultDav.JSON_QUERY))
    }

    @Test
    fun connectivityCheckHitsTheRoot() {
        assertTrue(client().canConnect())
        assertTrue(requests.single().contains(VaultDav.JSON_QUERY))
    }

    @Test
    fun aWrongTokenIsRejectedRatherThanReturningContent() {
        val wrong = client(token = "not-the-token")

        assertFalse(wrong.canConnect())
        assertTrue("no listing may come back without the token", wrong.list("/").isEmpty())
    }

    @Test
    fun fetchStreamsTheBodyOut() {
        fileBody = ByteArray(200_000) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()

        assertTrue(client().fetch("/song.flac", sink))
        assertEquals(fileBody.toList(), sink.toByteArray().toList())
    }

    @Test
    fun fetchWithAWrongTokenWritesNothing() {
        val sink = ByteArrayOutputStream()

        assertFalse(client(token = "wrong").fetch("/song.flac", sink))
        assertEquals(0, sink.size())
    }

    @Test
    fun anUnreachableHostFailsQuietly() {
        // Nothing is listening on this port; it must not throw.
        val dead = RemoteVault("127.0.0.1", 1, "token")
        assertFalse(dead.canConnect())
        assertTrue(dead.list("/").isEmpty())
        assertFalse(dead.fetch("/x", ByteArrayOutputStream()))
    }

    @Test
    fun pathsWithSpacesAreEncodedOnTheWire() {
        client().fetch("/My Music/track one.flac", ByteArrayOutputStream())

        val seen = requests.single()
        assertTrue("path must be percent-encoded: $seen", seen.contains("%20"))
        assertFalse("raw spaces would break the request line: $seen", seen.contains("My Music"))
    }

    @Test
    fun streamUrlPointsAtTheServer() {
        val url = client().streamUrl("/Photos/a b.jpg")
        assertTrue(url.startsWith("http://127.0.0.1:${server.localPort}/"))
        assertTrue(url.contains("%20"))
    }
}
