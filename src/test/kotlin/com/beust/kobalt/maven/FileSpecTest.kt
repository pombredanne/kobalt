package com.beust.kobalt.maven

import com.beust.kobalt.IFileSpec
import com.beust.kobalt.homeDir
import com.beust.kobalt.misc.From
import com.beust.kobalt.misc.IncludedFile
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.To
import org.testng.annotations.Test

@Test(enabled = false)
class FileSpecTest {
    fun f() {
        val inf = IncludedFile(From("src/main/resources"), To(""), listOf(IFileSpec.GlobSpec("**")))
        val files = inf.allFromFiles(homeDir("kotlin/kobalt/modules/kobalt-plugin-api"))
        println("FILES: $files")
    }

    fun findSourceFiles() {
        val sourceFiles = KFiles.findSourceFiles(homeDir("kotlin/kobalt/modules/wrapper"),
                listOf("src/main/java"), listOf("java"))
        println("Source files: " + sourceFiles)
    }
}
