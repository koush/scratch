package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.uv.uv_close
import com.koushikdutta.scratch.uv.uv_handle_t
import kotlinx.cinterop.*
import platform.posix.sockaddr_in

internal open class Alloced<T : CStructVar>(var value: T? = null, private val destructor: T.() -> Unit = {}) {
    val struct: T
        get() = value!!
    val ptr: CPointer<T>
        get() = struct.ptr

    fun free() {
        if (value == null)
            return
        val v = value!!
        value = null
        try {
            destructor(v)
        } finally {
            freePointer(v)
        }
    }

    open fun freePointer(value: T) {
        nativeHeap.free(value.ptr)
    }
}

internal class AllocedArray<T: CStructVar>() {
    var array: CArrayPointer<T>? = null

    var length: Int = 0

    fun free() {
        if (array == null)
            return
        val a = array!!
        array = null
        length = 0
        nativeHeap.free(a)
    }

    inline fun <reified T2: T> ensure(length: Int): AllocedArray<T> {
        if (this.length >= length)
            return this
        free()
        array = nativeHeap.allocArray<T2>(length).reinterpret()
        this.length = length
        return this
    }
}

internal fun <R> freeStableRef(data: COpaquePointer?): R? {
    if (data == null)
        return null
    val ref = data.asStableRef<Any>()
    val ret = ref.get()
    ref.dispose()
    return ret as R
}

internal class AllocedHandle<T: CStructVar>(val loop: AsyncEventLoop, value: T? = null, destructor: T.() -> Unit = {}): Alloced<T>(value, destructor) {
    fun <R> freeData(): R? {
        return freeUvHandleData(struct)
    }
    override fun freePointer(value: T) {
        freeUvHandleData<Any>(value)
        loop.handles.remove(this)
        uv_close(value.ptr.reinterpret(), closeCallbackPtr)
    }
}

internal fun <R> freeUvHandleData(self: CStructVar): R? {
    val handle: uv_handle_t = self.reinterpret()
    val data = handle.data
    if (data == null)
        return null
    handle.data = null
    return freeStableRef(data)
}

inline fun <reified T: CVariable> MemScope.allocPtr(): CPointer<T> {
    return alloc<T>().ptr
}

inline fun <reified T: CVariable> MemScope.allocUsePtr(block: (CPointer<T>) -> Unit): CPointer<T> {
    val ret = allocPtr<T>()
    block(ret)
    return ret
}
