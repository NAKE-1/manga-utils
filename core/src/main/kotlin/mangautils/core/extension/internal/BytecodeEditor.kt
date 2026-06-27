/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Vendored from Suwayomi-Server (MPL-2.0).
 */
package mangautils.core.extension.internal

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.streams.asSequence

/**
 * Rewrites references to certain JDK classes inside an extension jar so they behave like
 * Android's versions (notably `java.text.SimpleDateFormat`, whose Android parsing leniency
 * many extensions rely on). The replacement class lives in android-compat at
 * `xyz/nulldev/androidcompat/replace/java/text/SimpleDateFormat`.
 */
object BytecodeEditor {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun fixAndroidClasses(jarFile: Path) {
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use {
            Files
                .walk(it.getPath("/"))
                .asSequence()
                .filterNotNull()
                .filterNot(Files::isDirectory)
                .mapNotNull(::getClassBytes)
                .map(::transform)
                .forEach(::write)
        }
    }

    private fun getClassBytes(path: Path): Pair<Path, ByteArray>? =
        try {
            if (path.toString().endsWith(".class")) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size < 4) {
                    null
                } else {
                    val cafebabe =
                        String.format("%02X%02X%02X%02X", bytes[0], bytes[1], bytes[2], bytes[3])
                    if (cafebabe.lowercase() != "cafebabe") null else path to bytes
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error loading class from Path: $path", e)
            null
        }

    private const val REPLACEMENT_PATH = "xyz/nulldev/androidcompat/replace"

    private val classesToReplace = listOf("java/text/SimpleDateFormat")

    private fun String?.replaceDirectly() =
        when (this) {
            null -> null
            in classesToReplace -> "$REPLACEMENT_PATH/$this"
            else -> this
        }

    private fun String?.replaceIndirectly(): String? {
        if (this == null) return null
        var classReference: String = this
        classesToReplace.forEach {
            classReference = classReference.replace(it, "$REPLACEMENT_PATH/$it")
        }
        return classReference
    }

    private fun transform(pair: Pair<Path, ByteArray>): Pair<Path, ByteArray> {
        val cr = ClassReader(pair.second)
        val cw = ClassWriter(cr, 0)
        cr.accept(
            object : ClassVisitor(Opcodes.ASM5, cw) {
                override fun visitField(
                    access: Int,
                    name: String?,
                    desc: String?,
                    signature: String?,
                    cst: Any?,
                ): FieldVisitor? = super.visitField(access, name, desc.replaceIndirectly(), signature, cst)

                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String?>?,
                ): MethodVisitor {
                    val mv: MethodVisitor? =
                        super.visitMethod(access, name, desc.replaceIndirectly(), signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM5, mv) {
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) = super.visitTypeInsn(opcode, type.replaceDirectly())

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                            itf: Boolean,
                        ) = super.visitMethodInsn(opcode, owner.replaceDirectly(), name, desc.replaceIndirectly(), itf)

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                        ) = super.visitFieldInsn(opcode, owner, name, desc.replaceIndirectly())

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            desc: String?,
                            bsm: Handle?,
                            vararg bsmArgs: Any?,
                        ) = super.visitInvokeDynamicInsn(name, desc, bsm, *bsmArgs)
                    }
                }
            },
            0,
        )
        return pair.first to cw.toByteArray()
    }

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
