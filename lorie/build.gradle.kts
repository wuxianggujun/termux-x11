// TinaIDE override for termux-x11/lorie (replaces upstream Groovy build.gradle).
//
// 上游脚本假定 termux-x11 是独立单 app 仓库：它遍历 rootProject.subprojects 反查
// "哪个 application 模块依赖了 :lorie" 来推导 APPLICATION_ID，在 TinaIDE 的多模块
// 布局里必然抛异常。这里改为直接写死 TinaIDE 的 applicationId，并把 SDK / JVM 版本
// 对齐 TinaVersions。
//
// 注意：本文件位于 external/termux-x11 内部，属于对 vendored 上游的本地改动；
// 升级 termux-x11 时需要人工重新核对上游 build.gradle 的变化。

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

plugins {
    id("com.android.library")
}

// version.gradle 是上游对 NDK 版本与 versionName 的唯一事实源。
val versionGradle = file("version.gradle")
if (!versionGradle.exists()) {
    throw GradleException("version.gradle not found in ${project.projectDir}")
}
apply(from = versionGradle)

// Gradle 9.6 起 `by extra` 委托已废弃（编译为 error），改用显式读取。
// version.gradle 里 termuxX11VersionName 是 Groovy GString 插值结果，
// 直接 `as String` 会抛 GStringImpl cannot be cast to String，必须走 toString()。
val termuxX11VersionName = extra["termuxX11VersionName"].toString()
val termuxX11NdkVersion = extra["termuxX11NdkVersion"].toString()

// 上游用 `'git rev-parse HEAD'.execute()`，在 TinaIDE 里进程 CWD 是主仓库根目录，
// 会取到主仓库的 commit。这里显式指定 -C 到 lorie 所在的 termux-x11 工作树。
val termuxX11Commit: String = runCatching {
    providers.exec {
        commandLine("git", "-C", project.projectDir.absolutePath, "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()
    ?.takeIf { it.isNotEmpty() }
    ?: System.getenv("CURRENT_COMMIT")
    ?: "NO_COMMIT"

// 与 tina.android.library 约定插件保持一致的 ABI 收敛策略：
// 本地默认单 ABI，否则整套 X.Org 会按 4 个 ABI 各编译一遍。
val nativeAbis: Set<String> = run {
    val allAbiProperty = providers.gradleProperty("tina.allAbi").orNull?.trim()
    val allAbi = when {
        allAbiProperty != null -> allAbiProperty.equals("true", ignoreCase = true)
        System.getenv("CI").equals("true", ignoreCase = true) -> true
        else -> generateSequence(gradle) { it.parent }
            .flatMap { it.startParameter.taskNames.asSequence() }
            .any { it.contains("AllAbi", ignoreCase = true) }
    }
    if (allAbi) {
        setOf("arm64-v8a", "x86_64")
    } else {
        val injectedDevAbi = providers.gradleProperty("android.injected.build.abi").orNull
            ?.split(',')
            ?.firstNotNullOfOrNull { abi ->
                when {
                    abi.trim().startsWith("x86_64") -> "x86_64"
                    abi.trim().startsWith("arm64") || abi.trim().startsWith("aarch64") -> "arm64"
                    else -> null
                }
            }
        when (injectedDevAbi ?: providers.gradleProperty("tina.devAbi").orNull?.trim()) {
            "x86_64" -> setOf("x86_64")
            else -> setOf("arm64-v8a")
        }
    }
}

private fun discoverHostTool(
    propertyName: String,
    candidates: List<String>,
): String? {
    providers.gradleProperty(propertyName).orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { override -> return override }

    return candidates.map(::file).firstOrNull { candidate ->
        candidate.isFile && candidate.canExecute()
    }?.absolutePath
}

private fun discoverLorieHostToolArguments(): List<String> {
    val python = discoverHostTool(
        "tina.termuxX11.python",
        listOf(
            "D:/Programs/Python/Python313/python.exe",
            "C:/Python313/python.exe",
            "C:/Python312/python.exe",
        ),
    )
    val bison = discoverHostTool(
        "tina.termuxX11.bison",
        listOf(
            "D:/Programs/msys64/usr/bin/bison.exe",
            "C:/msys64/usr/bin/bison.exe",
            "D:/Programs/cygwin64/bin/bison.exe",
            "C:/cygwin64/bin/bison.exe",
        ),
    )
    val hostCompiler = discoverHostTool(
        "tina.termuxX11.hostCompiler",
        listOf(
            "D:/Programs/msys64/mingw64/bin/gcc.exe",
            "C:/msys64/mingw64/bin/gcc.exe",
            "D:/Programs/cygwin64/bin/gcc.exe",
            "C:/cygwin64/bin/gcc.exe",
        ),
    )
    val hostToolPath = providers.gradleProperty("tina.termuxX11.hostToolPath").orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: hostCompiler?.let { File(it).parent }

    return buildList {
        python?.let { add("-DPython3_EXECUTABLE=$it") }
        bison?.let { add("-DBISON_EXECUTABLE=$it") }
        hostCompiler?.let { add("-DTINA_HOST_C_COMPILER=$it") }
        hostToolPath?.let { add("-DTINA_HOST_TOOL_PATH=$it") }
    }
}

android {
    namespace = "com.termux.x11"
    compileSdk = 36 // TinaVersions.COMPILE_SDK

    ndkVersion = termuxX11NdkVersion

    defaultConfig {
        minSdk = 28 // TinaVersions.MIN_SDK
        // AGP 9 的 library defaultConfig 不再提供 targetSdk（已迁移到 testOptions / lint）。

        buildConfigField("String", "VERSION_NAME", "\"$termuxX11VersionName\"")
        buildConfigField("String", "COMMIT", "\"$termuxX11Commit\"")
        // TinaIDE 的 applicationId：上游靠反查宿主 app 模块推导，此处直接固定。
        buildConfigField("String", "APPLICATION_ID", "\"com.wuxianggujun.tinaide\"")

        consumerProguardFiles("proguard-rules.pro")

        ndk {
            abiFilters += nativeAbis
        }

        // path 在 android.externalNativeBuild.cmake；arguments 必须写在
        // defaultConfig.externalNativeBuild.cmake。AGP 9 的 path 节点没有 arguments。
        // 不改 Gradle launcher 的 PATH：Windows 上把 Cygwin/MSYS 提前会让 wrapper
        // 把 JDK 解析成 /cygdrive/... 然后找不到 java。这里把 host 工具的绝对路径
        // 交给 CMake，由 CMake 自己给 generator 进程补 PATH。
        externalNativeBuild {
            cmake {
                arguments += discoverLorieHostToolArguments()
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

// 代码生成目录注册。
// 不能写在上面的 `android { }` 里：该 accessor 解析到 AGP 的 legacy
// com.android.build.gradle.LibraryExtension，其 sourceSets 声明为 legacy
// AndroidLibrarySourceSet，而 AGP 9 的实现只满足新 DSL 接口，调用 named() 会抛
// ClassCastException。改为显式按新 DSL 类型 configure。
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.named("main") {
        // 这里必须传 concrete File；AGP 9 禁止把 Provider 加入 legacy SourceSet API。
        java.srcDir(file("build/generated/java"))
        res.srcDir(file("build/generated/templateRes"))
    }
}

dependencies {
    implementation(libs.lifecycle.viewmodel)
    implementation("androidx.preference:preference:1.2.1")
    // Android 隐藏 API 桩，仅供 CmdEntryPoint 编译期使用，不打进产物。
    compileOnly(project(":termux-x11-shell-loader-stub"))
}

// ===== 代码生成（移植自上游 build.gradle 的 generatePrefs / generateShortcuts）=====
//
// 上游把生成逻辑直接写在 `tasks.register {}` 的配置块里（即配置阶段执行）；
// 这里改为 doLast + 显式 inputs/outputs，避免每次配置都读写文件。

private data class LoriePref(
    val type: String,
    val key: String,
    val defaultValue: String,
    val entriesArray: String = "",
    val valuesArray: String = ""
)

private fun parseLoriePreferences(source: File): List<LoriePref> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // 关闭命名空间感知，才能用 getAttribute("app:key") 直接取带前缀的属性名。
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(source.readText())))
    val nodes = document.documentElement.getElementsByTagName("*")
    val result = mutableListOf<LoriePref>()
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        val key = element.getAttribute("app:key")
        val defaultValue = element.getAttribute("app:defaultValue")
        when (element.nodeName) {
            // extra_keys_config 的默认值来自 Java 常量，单独生成。
            "EditTextPreference" -> if (key != "extra_keys_config") {
                result += LoriePref("String", key, defaultValue)
            }
            "SeekBarPreference" -> result += LoriePref("Int", key, defaultValue)
            "SwitchPreferenceCompat" -> result += LoriePref("Boolean", key, defaultValue)
            "ListPreference" -> result += LoriePref(
                type = "List",
                key = key,
                defaultValue = defaultValue,
                // "@array/foo" -> "foo"
                entriesArray = element.getAttribute("app:entries").removePrefix("@array/"),
                valuesArray = element.getAttribute("app:entryValues").removePrefix("@array/")
            )
        }
    }
    return result
}

private fun LoriePref.toFieldDeclaration(): String = when (type) {
    "Int", "Boolean" -> "  public final ${type}Preference $key = new ${type}Preference(\"$key\", $defaultValue);"
    "String" -> "  public final StringPreference $key = new StringPreference(\"$key\", \"$defaultValue\");"
    "List" ->
        "  public final ListPreference $key = " +
            "new ListPreference(\"$key\", \"$defaultValue\", R.array.$entriesArray, R.array.$valuesArray);"
    else -> throw GradleException("Unknown lorie preference type: $type")
}

private fun renderPrefsJava(preferences: List<LoriePref>): String = buildString {
    appendLine("package com.termux.x11;")
    appendLine("import java.util.HashMap;")
    appendLine("import android.content.Context;")
    appendLine("import android.content.SharedPreferences;")
    appendLine("import com.termux.x11.utils.TermuxX11ExtraKeys;")
    appendLine()
    appendLine("public class Prefs extends LoriePreferences.PrefsProto {")
    preferences.forEach { appendLine(it.toFieldDeclaration()) }
    appendLine(
        "  public final StringPreference extra_keys_config = " +
            "new StringPreference(\"extra_keys_config\", TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);"
    )
    appendLine("  public final HashMap<String, Preference> keys = new HashMap<>() {{")
    preferences.forEach { appendLine("    put(\"${it.key}\", ${it.key});") }
    appendLine("    put(\"extra_keys_config\", extra_keys_config);")
    appendLine("  }};")
    appendLine()
    appendLine("  public Prefs(Context ctx) {")
    appendLine("    super(ctx);")
    appendLine(
        "    for (SharedPreferences store : " +
            "new SharedPreferences[]{ builtInDisplayPreferences, secondaryDisplayPreferences }) {"
    )
    appendLine("      Object legacyKeepScreenOn = store.getAll().get(\"keepScreenOn\");")
    appendLine("      if (legacyKeepScreenOn instanceof Boolean && !store.contains(\"screenIdleTimeout\"))")
    appendLine(
        "        store.edit().putString(\"screenIdleTimeout\", " +
            "(Boolean) legacyKeepScreenOn ? \"never\" : \"system\").commit();"
    )
    appendLine("    }")
    appendLine("  }")
    appendLine("}")
}

val generatePrefs = tasks.register("generatePrefs") {
    val preferencesXml = file("src/main/res/xml/preferences.xml")
    val outputFile = layout.buildDirectory.file("generated/java/com/termux/x11/Prefs.java")
    inputs.file(preferencesXml)
    outputs.file(outputFile)
    doLast {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(renderPrefsJava(parseLoriePreferences(preferencesXml)))
    }
}

val generateShortcuts = tasks.register("generateShortcuts") {
    val template = file("src/main/templates/xml/shortcuts.xml")
    val outputFile = layout.buildDirectory.file("generated/templateRes/xml/lorie_shortcuts.xml")
    val applicationId = "com.wuxianggujun.tinaide"
    inputs.file(template)
    inputs.property("applicationId", applicationId)
    outputs.file(outputFile)
    doLast {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(template.readText().replace("@@APPLICATION_ID@@", applicationId))
    }
}

tasks.named("preBuild").configure {
    dependsOn(generatePrefs, generateShortcuts)
}
