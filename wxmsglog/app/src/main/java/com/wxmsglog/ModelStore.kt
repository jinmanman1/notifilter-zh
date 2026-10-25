package com.wxmsglog

import android.content.Context
import java.io.File
import java.io.FileInputStream

/**
 * 模型文件的存放与校验。
 *
 * 位置放在 getExternalFilesDir：那是应用专属的外部目录，卸载时系统会一起清掉，
 * 不会被别的应用读到；同时它能被 USB 直连看到，用户想手动往里扔文件也方便。
 * 放 filesDir 也可以，但那个目录用户完全摸不到，导入模型会很难办。
 */
object ModelStore {

    private const val DIR_NAME = "models"

    /** GGUF 魔数。选错文件是最常见的翻车方式，加载前必须先过的第一道关。 */
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(DIR_NAME)
            ?: File(context.filesDir, DIR_NAME)
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** 目录里所有看起来像模型的文件。 */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.length() > 0
        }?.sortedBy { it.name } ?: emptyList()

    /** 当前选中的模型。文件被删掉时返回 null，不会留下一个悬空路径。 */
    fun current(context: Context): File? {
        val path = Prefs.modelPath(context) ?: return null
        val f = File(path)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun setCurrent(context: Context, file: File?) {
        Prefs.setModelPath(context, file?.absolutePath)
    }

    /**
     * 只读文件头 4 个字节判断格式。
     * 不用读整个文件——1.3GB 全读一遍要十几秒，用户会以为卡死了。
     */
    fun looksLikeGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        return try {
            FileInputStream(file).use { ins ->
                val head = ByteArray(4)
                if (ins.read(head) != 4) return false
                head.contentEquals(GGUF_MAGIC)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 已被选中的、正在被占用的那个就不能删。 */
    fun inUse(context: Context, file: File): Boolean =
        current(context)?.absolutePath == file.absolutePath

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (t: Throwable) {
        false
    }
}
