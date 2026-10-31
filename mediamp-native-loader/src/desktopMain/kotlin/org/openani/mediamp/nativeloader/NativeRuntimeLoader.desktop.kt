/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.nativeloader

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File

@Suppress("UnsafeDynamicallyLoadedCode")
internal actual fun loadRuntimeWrapperLibrary(runtimeDir: File, libraryName: String) {
    val wrapperFile = runtimeDir.resolve(nativeLibraryFileName(libraryName))

    if (!wrapperFile.isFile) {
        error("Failed to locate ${wrapperFile.name} in runtime directory ${runtimeDir.absolutePath}")
    }

    if (isWindowsRuntime()) {
        withWindowsDllDirectory(runtimeDir, wrapperFile.name) {
            System.load(wrapperFile.absolutePath)
        }
    } else {
        System.load(wrapperFile.absolutePath)
    }
}

internal var jnaPresenceProbe: () -> Boolean = ::detectJnaPresent

private fun detectJnaPresent(): Boolean =
    try {
        Class.forName("com.sun.jna.Native", false, NativeRuntimeLoader::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

internal fun isJnaPresent(): Boolean = jnaPresenceProbe()

internal fun <T> withWindowsDllDirectory(
    runtimeDir: File,
    libraryFileName: String = "",
    block: () -> T,
): T {
    if (!isJnaPresent()) {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load native library '$libraryFileName' from ${runtimeDir.absolutePath}. " +
                    "On Windows, resolving dependent DLLs without JNA may require adding 'net.java.dev.jna:jna' " +
                    "to your runtime dependencies, or ensuring the runtime directory is added to system PATH. " +
                    "Cause: ${e.message}",
            ).apply { initCause(e) }
        }
    }
    return JnaWindowsDllDirectoryHelper.withDllDirectory(runtimeDir, block)
}

/**
 * Isolated in a helper class so that JNA types (Native, WString, StdCallLibrary)
 * are only referenced and resolved by the JVM ClassLoader when [isJnaPresent] is true.
 */
internal object JnaWindowsDllDirectoryHelper {
    fun <T> withDllDirectory(runtimeDir: File, block: () -> T): T {
        val configured = WindowsDllKernel32.INSTANCE.SetDllDirectoryW(WString(runtimeDir.absolutePath))
        check(configured) {
            "Failed to add Windows DLL search directory: ${runtimeDir.absolutePath}"
        }
        try {
            return block()
        } finally {
            WindowsDllKernel32.INSTANCE.SetDllDirectoryW(null)
        }
    }

    @Suppress("FunctionName")
    private interface WindowsDllKernel32 : StdCallLibrary {
        fun SetDllDirectoryW(pathName: WString?): Boolean

        companion object {
            val INSTANCE: WindowsDllKernel32 = Native.load(
                "kernel32",
                WindowsDllKernel32::class.java,
                W32APIOptions.DEFAULT_OPTIONS,
            ) as WindowsDllKernel32
        }
    }
}
