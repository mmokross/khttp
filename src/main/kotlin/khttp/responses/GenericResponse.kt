/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package khttp.responses

import khttp.requests.GenericRequest
import khttp.requests.Request
import khttp.structures.cookie.Cookie
import khttp.structures.cookie.CookieJar
import khttp.structures.maps.CaseInsensitiveMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.nio.charset.Charset
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.text.Regex

class GenericResponse internal constructor(override val request: Request) : Response {

    internal companion object {

        internal val HttpURLConnection.cookieJar: CookieJar
            get() = CookieJar(*this.headerFields.filter { it.key == "Set-Cookie" }.flatMap { it.value }.map { Cookie(it) }.toTypedArray())

        internal fun <T> Class<T>.getSuperclasses(): List<Class<in T>> {
            val list = arrayListOf<Class<in T>>()
            var superclass = this.superclass
            while (superclass != null) {
                list.add(superclass)
                superclass = superclass.superclass
            }
            return list
        }

        internal fun HttpURLConnection.forceMethod(method: String) {
            try {
                this.requestMethod = method
            } catch (ex: ProtocolException) {
                try {
                    (this.javaClass.getDeclaredField("delegate").apply { this.isAccessible = true }.get(this) as HttpURLConnection?)?.forceMethod(method)
                } catch (ex: NoSuchFieldException) {
                    // ignore
                }
                (this.javaClass.getSuperclasses() + this.javaClass).forEach {
                    try {
                        it.getDeclaredField("method").apply { this.isAccessible = true }.set(this, method)
                    } catch (ex: NoSuchFieldException) {
                        // ignore
                    }
                }
            }
            check(this.requestMethod == method)
        }

        internal val defaultStartInitializers: MutableList<(GenericResponse, HttpURLConnection) -> Unit> = arrayListOf(
            { response, connection ->
                connection.forceMethod(response.request.method)
            },
            { response, connection ->
                for ((key, value) in response.request.headers) {
                    connection.setRequestProperty(key, value)
                }
            },
            { response, connection ->
                val cookies = response.request.cookies ?: return@arrayListOf
                // Get the cookies specified in the request and add the cookies from the response
                val cookieJar = CookieJar(cookies + response._cookies)
                // Set the merged cookies in the request
                connection.setRequestProperty("Cookie", cookieJar.toString())
            },
            { response, connection ->
                val timeout = (response.request.timeout * 1000.0).toInt()
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
            },
            { response, connection ->
                connection.instanceFollowRedirects = false
            }
        )
        internal val defaultEndInitializers: MutableList<(GenericResponse, HttpURLConnection) -> Unit> = arrayListOf(
            { response, connection ->
                val body = response.request.body
                // If the body is empty, there is nothing to write
                if (body.isEmpty()) return@arrayListOf
                // Otherwise, we'll be writing output
                connection.doOutput = true
                // Write out all the bytes
                connection.outputStream.use { it.write(body) }
            },
            { response, connection ->
                val files = response.request.files
                val data = response.request.data
                // If we're dealing with a non-streaming request, ignore
                if (files.isNotEmpty()) return@arrayListOf
                // Stream the contents if data is a File or InputStream, otherwise ignore
                val input = if (data is File) data.inputStream() else if (data is InputStream) data else return@arrayListOf
                // We'll be writing output
                if (!connection.doOutput) {
                    connection.doOutput = true
                }
                // Write out the file in 4KiB chunks
                input.use { input ->
                    connection.outputStream.use { output ->
                        while (input.available() > 0) {
                            output.write(
                                ByteArray(Math.min(4096, input.available())).apply { input.read(this) }
                            )
                        }
                    }
                }
            },
            { response, connection ->
                // Add all the cookies from every response to our cookie jar
                response._cookies.putAll(connection.cookieJar)
            }
        )
    }

    internal fun URL.openRedirectingConnection(first: Response, receiver: HttpURLConnection.() -> Unit): HttpURLConnection {
        val connection = (this.openConnection() as HttpURLConnection).apply {
            this.instanceFollowRedirects = false
            this.receiver()
            this.connect()
        }
        if (first.request.allowRedirects && connection.responseCode in 301..303) {
            val cookies = connection.cookieJar
            val req = with(first.request) {
                GenericResponse(
                    GenericRequest(
                        method = this.method,
                        url = this@openRedirectingConnection.toURI().resolve(connection.getHeaderField("Location")).toASCIIString(),
                        headers = this.headers,
                        params = this.params,
                        data = this.data,
                        json = this.json,
                        auth = this.auth,
                        cookies = cookies + (this.cookies ?: mapOf()),
                        timeout = this.timeout,
                        allowRedirects = false,
                        stream = this.stream,
                        files = this.files
                    )
                )
            }
            req._cookies.putAll(cookies)
            req._history.addAll(first.history)
            (first as GenericResponse)._history.add(req)
            req.init()
        }
        return connection
    }

    internal var _history: MutableList<Response> = arrayListOf()
    override val history: List<Response>
        get() = Collections.unmodifiableList(this._history)

    private var _connection: HttpURLConnection? = null
    override val connection: HttpURLConnection
        get() {
            if (this._connection == null) {
                this._connection = URL(this.request.url).openRedirectingConnection(this._history.firstOrNull() ?: this.apply { this._history.add(this) }) {
                    (GenericResponse.defaultStartInitializers + this@GenericResponse.initializers + GenericResponse.defaultEndInitializers).forEach { it(this@GenericResponse, this) }
                }
            }
            return this._connection ?: throw IllegalStateException("Set to null by another thread")
        }

    override val statusCode: Int
        get() = this.connection.responseCode

    override val headers: Map<String, String>
        get() = CaseInsensitiveMap(this.connection.headerFields.mapValues { it.value.joinToString(", ") }.filterKeys { it != null })

    private val HttpURLConnection.realInputStream: InputStream
        get() {
            val stream = try {
                this.inputStream
            } catch (ex: IOException) {
                this.errorStream
            }
            return when (this@GenericResponse.headers["Content-Encoding"]?.toLowerCase()) {
                "gzip" -> GZIPInputStream(stream)
                "deflate" -> InflaterInputStream(stream)
                else -> stream
            }
        }

    private var _raw: InputStream? = null
    override val raw: InputStream
        get() {
            if (this._raw == null) {
                this._raw = this.connection.realInputStream
            }
            return this._raw ?: throw IllegalStateException("Set to null by another thread")
        }

    private var _contents: ByteArray? = null
    override val content: ByteArray
        get() {
            if (this._contents == null) {
                this._contents = this.raw.use { it.readBytes() }
            }
            return this._contents ?: throw IllegalStateException("Set to null by another thread")
        }

    override val text: String
        get() = this.content.toString(this.encoding)

    override val jsonObject: JSONObject
        get() = JSONObject(this.text)

    override val jsonArray: JSONArray
        get() = JSONArray(this.text)

    private val _cookies = CookieJar()
    override val cookies: CookieJar
        get() {
            this.connection // Ensure that we've connected
            return this._cookies
        }

    override val url: String
        get() = this.connection.url.toString()

    private var _encoding: Charset? = null
        set(value) {
            field = value
        }
    override var encoding: Charset
        get() {
            if (this._encoding != null) {
                return this._encoding ?: throw IllegalStateException("Set to null by another thread")
            }
            this.headers["Content-Type"]?.let {
                val charset = it.split(";").map { it.split("=") }.filter { it[0].trim().toLowerCase() == "charset" }.filter { it.size == 2 }.map { it[1] }.firstOrNull()
                return Charset.forName(charset?.toUpperCase() ?: Charsets.UTF_8.name())
            }
            return Charsets.UTF_8
        }
        set(value) {
            this._encoding = value
        }

    // Initializers
    val initializers: MutableList<(GenericResponse, HttpURLConnection) -> Unit> = arrayListOf()

    override fun contentIterator(chunkSize: Int): Iterator<ByteArray> {
        return object : Iterator<ByteArray> {
            val stream = if (this@GenericResponse.request.stream) this@GenericResponse.raw else this@GenericResponse.content.inputStream()

            override fun next() = ByteArray(Math.min(chunkSize, stream.available())).apply { stream.read(this) }

            override fun hasNext(): Boolean {
                return try {
                    (this@GenericResponse.raw.available() > 0).apply {
                        if (!this) {
                            stream.close()
                        }
                    }
                } catch(ex: IOException) {
                    false
                }
            }
        }
    }

    override fun lineIterator(chunkSize: Int, delimiter: Regex): Iterator<ByteArray> {
        return object : Iterator<ByteArray> {
            val byteArrays = this@GenericResponse.contentIterator(chunkSize)
            var leftOver: ByteArray? = null
            val overflow = arrayListOf<ByteArray>()

            override fun next(): ByteArray {
                if (overflow.isNotEmpty()) return overflow.removeAt(0)
                while (byteArrays.hasNext()) {
                    do {
                        val left = leftOver
                        val array = byteArrays.next()
                        val content = if (left != null) left + array else array
                        leftOver = content
                        val split = content.toString(this@GenericResponse.encoding).split(delimiter)
                        if (split.size >= 2) {
                            leftOver = split.last().toByteArray(this@GenericResponse.encoding)
                            overflow.addAll(split.subList(1, split.size - 1).map { it.toByteArray(this@GenericResponse.encoding) })
                            return split[0].toByteArray(this@GenericResponse.encoding)
                        }
                    } while (split.size < 2)
                }
                return leftOver!!
            }

            override fun hasNext() = overflow.isNotEmpty() || byteArrays.hasNext()

        }
    }

    override fun toString(): String {
        return "<Response [${this.statusCode}]>"
    }

    /**
     * Used to ensure that the proper connection has been made.
     */
    internal fun init() {
        if (this.request.stream) {
            this.connection // Establish connection if streaming
        } else {
            this.content // Download content if not
        }
    }

}
