package tool.generator

import com.badlogic.gdx.jnigen.*
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

@CompileStatic
class GenerateLibs extends DefaultTask {
    @Internal
    String group = 'build'
    @Internal
    String description = 'Generate imgui-java native binaries.'

    private final String[] buildEnvs = System.getProperty('envs')?.split(',')
    private final boolean forWindows = buildEnvs?.contains('win')
    private final boolean forLinux = buildEnvs?.contains('linux')
    private final boolean forMac = buildEnvs?.contains('mac')
    private static final boolean isARM = System.getProperty("os.arch").equals("arm") || System.getProperty("os.arch").startsWith("aarch64");

    private final boolean isLocal = System.properties.containsKey('local')
    private final boolean withFreeType = Boolean.valueOf(System.properties.getProperty('freetype', 'false'))

    private final String sourceDir = project.file('src/main/java')
    private final String classpath = project.file('build/classes/java/main')
    private final String jniDir = (isLocal ? project.buildDir.path : '/tmp/imgui') + '/jni'
    private final String tmpFolder = (isLocal ? project.buildDir.path : '/tmp/imgui') + '/tmp'
    private final String libsFolder = 'libsNative'

    @TaskAction
    void generate() {
        println 'Generating Native Libraries...'
        println "Build targets: $buildEnvs"
        println "Local: $isLocal"
        println "FreeType: $withFreeType"
        println '====================================='

        if (!buildEnvs) {
            throw new IllegalStateException('No build targets')
        }

        // Generate h/cpp files for JNI
        new NativeCodeGenerator().generate(sourceDir, classpath, jniDir)

        // Copy ImGui h/cpp files
        project.copy { CopySpec spec ->
            ['include/imgui', 'include/imnodes', 'include/imgui-node-editor',
             'include/imguizmo', 'include/implot', 'include/ImGuiColorTextEdit',
             'include/ImGuiFileDialog', 'include/imgui_club/imgui_memory_editor'].each {
                spec.from(project.rootProject.file(it)) { CopySpec s -> s.include('*.h', '*.cpp', '*.inl') }
            }
            spec.from(project.rootProject.file('imgui-binding/src/main/native'))
            spec.into(jniDir)
            spec.duplicatesStrategy = DuplicatesStrategy.INCLUDE //Allows for duplicate imconfig.h, we ensure the correct one is copied below
        }

        //Ensure we overwrite imconfig.h with our own
        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('imgui-binding/src/main/native/imconfig.h'))
            spec.into(jniDir)
        }

        //Copy dirent for ImGuiFileDialog
        project.copy { CopySpec spec ->
            ['include/ImGuiFileDialog/dirent'].each {
                spec.from(project.rootProject.file(it)) { CopySpec s -> s.include('*.h', '*.cpp', '*.inl') }
            }
            spec.into(jniDir + '/dirent')
        }

        if (withFreeType) {
            project.copy { CopySpec spec ->
                spec.from(project.rootProject.file('include/imgui/misc/freetype')) { CopySpec it -> it.include('*.h', '*.cpp') }
                spec.into("$jniDir/misc/freetype")
            }

            enableDefine('IMGUI_ENABLE_FREETYPE')
        }

        // Generate platform dependant ant configs and header files
        def buildConfig = new BuildConfig('imgui-java', tmpFolder, libsFolder, jniDir)
        def buildTargets = [] as BuildTarget[]

        if (forWindows) {
            def win64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Windows, true)
            addFreeTypeIfEnabled(win64)
            buildTargets += win64
        }

        if (forLinux) {
            def linux64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Linux, true)
            addFreeTypeIfEnabled(linux64)
            buildTargets += linux64
        }

        if (forMac) {
            def minMacOsVersion = '10.15'
            def mac64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.MacOsX, true)
            mac64.cppFlags += ' -std=c++14'
            mac64.cppFlags = mac64.cppFlags.replace('10.7', minMacOsVersion)
            mac64.linkerFlags = mac64.linkerFlags.replace('10.7', minMacOsVersion)
            addFreeTypeIfEnabled(mac64)
            buildTargets += mac64

            def macM1 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.MacOsX, true, true)
            macM1.cppFlags += ' -std=c++14'
            macM1.cppFlags = macM1.cppFlags.replace('10.7', minMacOsVersion)
            macM1.linkerFlags = macM1.linkerFlags.replace('10.7', minMacOsVersion)
            addFreeTypeIfEnabled(macM1)
            buildTargets += macM1
        }

        new AntScriptGenerator().generate(buildConfig, buildTargets)

        // Generate native libraries
        // Comment/uncomment lines with OS you need.

        def commonParams = ['-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile'] as String[]

        if (forWindows)
            BuildExecutor.executeAnt(jniDir + '/build-windows64.xml', commonParams)
        if (forLinux)
            BuildExecutor.executeAnt(jniDir + '/build-linux64.xml', commonParams)
        if (forMac) {
            BuildExecutor.executeAnt(jniDir + '/build-macosx64.xml', commonParams)
            BuildExecutor.executeAnt(jniDir + '/build-macosxarm64.xml', commonParams)
        }

        BuildExecutor.executeAnt(jniDir + '/build.xml', '-v', 'pack-natives')
    }

    void addFreeTypeIfEnabled(BuildTarget target) {
        if (!withFreeType) {
            return
        }

        switch (target.os) {
            case BuildTarget.TargetOs.Windows:
                target.cppFlags += ' -I/usr/x86_64-w64-mingw32/include/freetype2'
                break
            case BuildTarget.TargetOs.Linux:
                target.cppFlags += ' -I/usr/include/freetype2'
                break
            case BuildTarget.TargetOs.MacOsX:
                target.cppFlags += ' -I/usr/local/include/freetype2'
                if (isARM) {
                    //For GHA
                    target.cppFlags += ' -I/usr/local/arm64/include/freetype2'
                }
                break
        }

        target.libraries += ' -lfreetype'
    }

    void enableDefine(String define) {
        project.file("$jniDir/imconfig.h").text += "#define $define"
    }
}
