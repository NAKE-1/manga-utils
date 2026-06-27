/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Vendored from Suwayomi-Server (MPL-2.0).
 */
package mangautils.core.extension.internal

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.Enumeration

/**
 * A parent-last class loader that tries, in order: the system class loader,
 * the child (extension jar) class loader, then the parent. This lets an extension's
 * classes win over anything on the app classpath while still seeing JDK + our shims.
 */
class ChildFirstURLClassLoader(
    urls: Array<URL>,
    parent: ClassLoader? = null,
) : URLClassLoader(urls, parent) {
    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(
        name: String?,
        resolve: Boolean,
    ): Class<*> {
        var c = findLoadedClass(name)

        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        if (c == null) {
            c =
                try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, resolve)
                }
        }

        if (resolve) {
            resolveClass(c)
        }

        return c
    }

    override fun getResource(name: String?): URL? =
        systemClassLoader?.getResource(name)
            ?: findResource(name)
            ?: super.getResource(name)

    override fun getResources(name: String?): Enumeration<URL> {
        val systemUrls = systemClassLoader?.getResources(name)
        val localUrls = findResources(name)
        val parentUrls = parent?.getResources(name)
        val urls =
            buildList {
                while (systemUrls?.hasMoreElements() == true) add(systemUrls.nextElement())
                while (localUrls?.hasMoreElements() == true) add(localUrls.nextElement())
                while (parentUrls?.hasMoreElements() == true) add(parentUrls.nextElement())
            }

        return object : Enumeration<URL> {
            val iterator = urls.iterator()

            override fun hasMoreElements() = iterator.hasNext()

            override fun nextElement() = iterator.next()
        }
    }

    override fun getResourceAsStream(name: String?): InputStream? =
        try {
            getResource(name)?.openStream()
        } catch (_: IOException) {
            null
        }
}
