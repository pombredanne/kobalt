package com.beust.kobalt.misc

import com.beust.kobalt.*
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.Project
import com.beust.kobalt.maven.Md5
import org.apache.commons.io.FileUtils
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.jar.JarInputStream
import java.util.regex.Pattern


class KFiles {
    /**
     * This actually returns a list of strings because in development mode, we are not pointing to a single
     * jar file but to a set of classes/directories.
     */
    val kobaltJar : List<String>
        get() {
            val PATTERN = Pattern.compile("kobalt-([-.0-9]+)")

            fun latestInstalledVersion() : StringVersion {
                val versions = File(distributionsDir).listFiles().map { it.name }.map {
                    val matcher = PATTERN.matcher(it)
                    val result =
                        if (matcher.matches()) matcher.group(1)
                        else null
                    result
                }.filterNotNull().map(::StringVersion)
                Collections.sort(versions, reverseOrder())
                return versions[0]
            }

            val envJar = System.getenv("KOBALT_JAR")
            if (envJar != null) {
                debug("Using kobalt jar $envJar")
                return listOf(File(envJar).absolutePath)
            } else {
                val jar = joinDir(distributionsDir, "kobalt-" + Kobalt.version,
                        "kobalt/wrapper/kobalt-" + Kobalt.version + ".jar")
                val jarFile = File(jar)
                if (jarFile.exists()) {
                    return listOf(jarFile.absolutePath)
                } else {
                    // In development mode, keep your kobalt.properties version to a nonexistent version
                    // kobalt.properties:  kobalt.version=0.828
                    // kobalt-wrapper.properties: kobalt.version=0.827
                    // When Kobalt can't find the newest jar file, it will instead use the classes produced by IDEA
                    // in the directories specified here:
                    val previousVersion = latestInstalledVersion().version
                    val previousJar = joinDir(distributionsDir, "kobalt-" + previousVersion,
                            "kobalt/wrapper/kobalt-$previousVersion.jar")
                    latestInstalledVersion()
                    val result = listOf("", "modules/kobalt-plugin-api", "modules/wrapper").map {
                        File(homeDir(KFiles.joinDir("kotlin", "kobalt", it, "kobaltBuild", "classes"))) //kobalt build dirs
                                .absolutePath
                    } + listOf("modules/kobalt", "modules/kobalt-plugin-api", "modules/wrapper").map {
                        File(homeDir(KFiles.joinDir("kotlin", "kobalt", it, "target", "classes"))) //maven build dirs
                                .absolutePath
                    } + listOf(previousJar)
                    debug("Couldn't find ${jarFile.absolutePath}, using\n  " + result.joinToString(" "))
                    return result.filter { File(it).exists() }
                }
            }
        }

    init {
        File(KOBALT_DOT_DIR).mkdirs()
    }

    companion object {
        const val KOBALT_DOT_DIR : String = ".kobalt"
        val KOBALT_DIR : String = "kobalt"
        val HOME_KOBALT_DIR = makeDir(homeDir(".config", KOBALT_DIR))
        val KOBALT_BUILD_DIR = "kobaltBuild"

        /** Where all the .zip files are extracted */
        val distributionsDir = homeDir(KOBALT_DOT_DIR, "wrapper", "dist")

        // Directories under ./.kobalt
        val SCRIPT_BUILD_DIR : String = "build"
        val CLASSES_DIR : String = "classes"

        /** Where build file and support source files go, under KOBALT_DIR */
        private val SRC = "src"

        val TEST_CLASSES_DIR : String = "test-classes"

        val NATIVES_DIR : String = "native"

        fun nativeBuildDir(project: Project) = KFiles.joinDir(project.directory, project.buildDirectory, NATIVES_DIR)

        fun generatedSourceDir(project: Project, variant: Variant, name: String) =
                KFiles.joinDir(project.directory, project.buildDirectory, "generated", "source", name,
                        variant.toIntermediateDir())

        fun buildDir(project: Project) = KFiles.makeDir(project.directory, project.buildDirectory)

        /**
         * Join the paths elements with the file separator.
         */
        fun joinDir(paths: List<String>): String = paths.joinToString(File.separator)

        /**
         * Join the paths elements with the file separator.
         */
        fun joinDir(vararg ts: String): String = ts.toMutableList().joinToString(File.separator)

        val LIBS_DIR = "libs"

        /**
         * Where assemblies get generated ("kobaltBuild/libs")
         */
        fun libsDir(project: Project): String = KFiles.makeDir(KFiles.buildDir(project).path, LIBS_DIR).path

        /**
         * The paths elements are expected to be a directory. Make that directory and join the
         * elements with the file separator.
         */
        fun joinAndMakeDir(paths: List<String>) = joinDir(paths).apply { File(this).mkdirs() }

        /**
         * The paths elements are expected to be a directory. Make that directory and join the
         * elements with the file separator.
         */
        fun joinAndMakeDir(vararg ts: String) = joinAndMakeDir(ts.toList())

        /**
         * The paths elements are expected to be a file. Make that parent directory of that file and join the
         * elements with the file separator.
         */
        fun joinFileAndMakeDir(vararg ts: String) = joinDir(joinAndMakeDir(ts.slice(0..ts.size - 2)), ts[ts.size - 1])

        fun fixSlashes(f: File) = f.normalize().path.replace('\\', '/')
        fun fixSlashes(s: String) = s.replace('\\', '/')

        fun makeDir(dir: String, s: String? = null) =
                (if (s != null) File(dir, s) else File(dir)).apply { mkdirs() }

        fun findRecursively(rootDir: File) : List<String> =
                findRecursively(rootDir, arrayListOf(), { _ -> true })

        fun findRecursively(rootDir: File, directories: List<File>,
                function: Function1<String, Boolean>): List<String> {
            val result = arrayListOf<String>()

            val allDirs = arrayListOf<File>()
            if (directories.isEmpty()) {
                allDirs.add(rootDir)
            } else {
                allDirs.addAll(directories.map { File(rootDir, it.path) })
            }

            val seen = hashSetOf<java.nio.file.Path>()
            allDirs.forEach { dir ->
                if (! dir.exists()) {
                    kobaltLog(2, "Couldn't find directory $dir")
                } else if (! dir.isDirectory) {
                    throw IllegalArgumentException("$dir is not a directory")
                } else {
                    val files = findRecursively(dir, function)
                    files.map { Paths.get(it) }.forEach {
                        val rel = Paths.get(dir.path).relativize(it)
                        if (! seen.contains(rel)) {
                            result.add(File(dir, rel.toFile().path).path)
                            seen.add(rel)
                        } else {
                            kobaltLog(2, "Skipped file already seen in previous flavor: $rel")
                        }
                    }
                }
            }
            // Return files relative to rootDir
            val r = result.map { it.substring(rootDir.path.length + 1)}
            return r
        }

        fun findRecursively(directory: File, function: Function1<String, Boolean>): List<String> {
            val result = arrayListOf<String>()
            directory.listFiles().forEach {
                if (it.isFile && function(it.path)) {
                    result.add(it.path)
                } else if (it.isDirectory) {
                    result.addAll(findRecursively(it, function))
                }
            }
            return result
        }

        /**
         * List the files contained in a directory or a jar file.
         */
        fun listFiles(file: File, block: (String) -> Unit) {
            if (file.isDirectory) {
                KFiles.findRecursively(file).forEach {
                    block(it)
                }
            } else if (file.name.endsWith(".jar")) {
                FileInputStream(file).use {
                    JarInputStream(it).use { stream ->
                        var entry = stream.nextJarEntry
                        while (entry != null) {
                            block(entry.name)
                            entry = stream.nextJarEntry;
                        }
                    }
                }

            } else {
                throw KobaltException("Can't list files of a file: " + file)
            }
        }

        /**
         * The build location for build scripts is .kobalt/build
         */
        fun findBuildScriptDir(parent: String = ".") : File {
            val result = File(joinAndMakeDir(parent, KFiles.dotKobaltDir.path, KFiles.SCRIPT_BUILD_DIR))
            kobaltLog(2, "  Script jar files in: $result")
            return result
        }

        fun saveFile(file: File, text: String) {
            var canCreate = true
            with(file.absoluteFile.parentFile) {
                if (!exists()) {
                    val success = mkdirs()
                    if (!success) {
                        warn("Couldn't create directory to save $file")
                        canCreate = false
                    }
                }
            }
            if (canCreate) {
                file.writeText(text)
                kobaltLog(2, "Created $file")
            }
        }

        private fun isWindows() = System.getProperty("os.name").contains("Windows")

        fun copy(from: Path?, to: Path?, option: StandardCopyOption = StandardCopyOption.REPLACE_EXISTING) {
            try {
                if (from != null && to != null) {
                    if (!Files.exists(to) || Md5.toMd5(from.toFile()) != Md5.toMd5(to.toFile())) {
                        kobaltLog(3, "Copy from $from to $to")
                        Files.copy(from, to, option)
                    } else {
                        kobaltLog(3, "  Not copying, indentical files: $from $to")
                    }
                }
            } catch(ex: IOException) {
                // Windows is anal about this
                kobaltLog(1, "Couldn't copy $from to $to: ${ex.message}")
            }
        }

        fun copy(from: InputStream, to: OutputStream) {
            var read = from.read()
            while (read != -1) {
                to.write(read)
                read = from.read()
            }
        }

        fun createTempBuildFileInTempDirectory(deleteOnExit: Boolean = false) : File =
                File(createTempDirectory("kobalt", deleteOnExit), Constants.BUILD_FILE_NAME).let {
                if (deleteOnExit) it.deleteOnExit()
                return it
            }

        fun createTempDirectory(prefix : String = "kobalt", deleteOnExit: Boolean = false) : File =
            Files.createTempDirectory(prefix).let {
                if (deleteOnExit) it.toFile().deleteOnExit()
                return it.toFile()
            }

        fun src(filePath: String): String = KFiles.joinDir(KOBALT_DIR, SRC, filePath)

        fun makeDir(project: Project, suffix: String) : File {
            return File(project.directory, project.buildDirectory + File.separator + suffix).apply { mkdirs() }
        }

        fun makeOutputDir(project: Project) : File = makeDir(project, KFiles.CLASSES_DIR)

        fun makeOutputTestDir(project: Project) : File = makeDir(project, KFiles.TEST_CLASSES_DIR)

        fun isExcluded(file: String, excludes: Glob) = isExcluded(file, listOf(excludes))

        fun isExcluded(file: File, excludes: List<Glob>) = isExcluded(file.path, excludes)

        fun isExcluded(file: String, excludes: List<Glob>): Boolean = excludes.any { it.matches(file) }

        /**
         * TODO: cache these per project so we don't do it more than once.
         */
        fun findSourceFiles(projectDirectory: String, sourceDirectories: Collection<String>,
                suffixes: List<String>) : Set<String> {
            val result = hashSetOf<String>()
            sourceDirectories.forEach { source ->
                val sourceDir = File(KFiles.joinDir(projectDirectory, source))
                if (sourceDir.exists()) {
                    KFiles.findRecursively(sourceDir, { file ->
                        val ind = file.lastIndexOf(".")
                        if (ind >= 0) {
                            val suffix = file.substring(ind + 1)
                            if (suffixes.contains(suffix)) {
                                result.add(file)
                            }
                        }
                        false
                    })
                } else {
                    kobaltLog(3, "Skipping nonexistent source directory $sourceDir")
                }
            }
            return result
        }

        fun isResource(name: String) = name.contains("res") || name.contains("resources")

        /**
         * @return true as soon as a file meeting the condition is found.
         */
        fun containsCertainFile(dir: File, condition: (File) -> Boolean) : Boolean {
            if (dir.isDirectory) {
                val directories = arrayListOf<File>()
                dir.listFiles().forEach {
                    if (condition(it)) return true
                    if (it.isDirectory) directories.add(it)
                }
                return directories.any { containsCertainFile(it, condition) }
            } else {
                return false
            }
        }

        val dotKobaltDir = File(KFiles.joinAndMakeDir(KFiles.KOBALT_DOT_DIR))

        /**
         * Turn the IncludedFiles into actual Files
         */
        fun materializeIncludedFiles(project: Project, includedFiles: List<IncludedFile>) : List<File> {
            val result = includedFiles.fold(arrayListOf<File>()) { files, includedFile: IncludedFile ->
                val foundFiles = includedFile.allFromFiles(project.directory)
                val absFiles = foundFiles.map {
                    if (it.isAbsolute) {
                        it
                    } else if (File(includedFile.from).isAbsolute) {
                        File(includedFile.from, it.path)
                    } else {
                        File(KFiles.joinDir(project.directory, includedFile.from, it.path))
                    }
                }
                files.addAll(absFiles)
                files
            }
            return result
        }

        fun copyRecursively(from: File, to: File, replaceExisting: Boolean = true, deleteFirst: Boolean = false) {
// fun copy(relativePath: String, sourceDir: File, targetDir: File) =
//            sourceDir.resolve(relativePath).copyRecursively(targetDir.resolve(relativePath), overwrite = true)
            if (from.isFile) FileUtils.copyFileToDirectory(from, to)
            else FileUtils.copyDirectory(from, to)
        }

    }

    fun findRecursively(directory: File, function: Function1<String, Boolean>): List<String> {
        return KFiles.findRecursively(directory, function)
    }

    fun findRecursively(rootDir: File, directories: List<File>,
            function: Function1<String, Boolean>): List<String> {
        return KFiles.findRecursively(rootDir, directories, function)
    }
}
