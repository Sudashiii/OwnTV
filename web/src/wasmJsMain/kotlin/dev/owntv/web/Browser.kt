package dev.owntv.web

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// The few browser calls the preview needs, as plain JS snippets.

/** Lets the page's script call into Kotlin: `window.owntv.render(json)`, `setPage(id)`, `setMetrics(on)`. */
fun installBridge(render: (String) -> Unit, setPage: (String) -> Unit, setMetrics: (Boolean) -> Unit): Unit =
    js("{ window.owntv = { render: render, setPage: setPage, setMetrics: setMetrics }; window.dispatchEvent(new Event('owntv-ready')); }")

/** Sends a decode result (JSON) to the page's script. */
fun postResult(json: String): Unit = js("window.dispatchEvent(new CustomEvent('owntv-result', { detail: JSON.parse(json) }))")

/** Removes the "Loading" text once the renderer can draw. */
fun hideLoading(): Unit = js("document.getElementById('tv-loading')?.remove()")

/** Used JS heap in bytes where the browser reports it (Chromium), else -1. */
fun jsHeapBytes(): Double = js("(performance.memory && performance.memory.usedJSHeapSize) || -1")

private fun fetchArrayBuffer(url: String): Promise<JsAny> =
    js("fetch(url).then(r => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.arrayBuffer(); })")

private fun byteLength(buffer: JsAny): Int = js("buffer.byteLength")

private fun copyIntoMemory(buffer: JsAny, memory: JsAny, address: Int): Unit =
    js("new Int8Array(memory.buffer, address, buffer.byteLength).set(new Int8Array(buffer))")

/** Downloads [url] into a ByteArray, copying through Wasm memory in one step instead of byte by byte. */
suspend fun fetchBytes(url: String): ByteArray {
    val buffer = fetchArrayBuffer(url).await<JsAny>()
    val size = byteLength(buffer)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(size)
        copyIntoMemory(buffer, wasmMemory, pointer.address.toInt())
        ByteArray(size) { (pointer + it).loadByte() }
    }
}
