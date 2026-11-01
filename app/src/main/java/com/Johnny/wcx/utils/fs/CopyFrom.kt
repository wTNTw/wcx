package com.Johnny.wcx.utils.fs

import java.io.InputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 把输入流写到目标路径（覆盖）。 */
fun Path.copyFrom(input: InputStream) {
    java.nio.file.Files.newOutputStream(this).use { output ->
        input.copyTo(output)
    }
}
