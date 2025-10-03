package io.github.weg2022.strguard

import com.squareup.javapoet.*
import groovy.transform.CompileStatic
import io.github.weg2022.strguard.api.IStrGuard
import io.github.weg2022.strguard.api.IkeyGenerator
import io.github.weg2022.strguard.api.StrGuard
import io.github.weg2022.strguard.api.StrGuardImpl
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

import javax.lang.model.element.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

@CompileStatic
class StrGuardPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.extensions.create("strGuard", StrGuardExtension)

        var javaFiles = List.of(
                _KeepString(),
                _KeepMetadata(),
                _IStrGuard(),
                _StrGuard(),
                _StrGuardImpl(),
        )

        var generateJavaTask = project.getTasks().register("generateStrguard", task -> {
            task.group = "strguard"
            task.doLast(t -> {
                File dir = new File(project.layout.buildDirectory.get().asFile, "generated/sources/strguard/main")
                dir.mkdirs()
                javaFiles.forEach {
                    it.writeTo(dir)
                }
            })
        })

        var javaExt = project.getExtensions().getByType(JavaPluginExtension.class)
        var sourceSets = javaExt.getSourceSets()

        var main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        main.getJava().srcDir("build/generated/sources/strguard/main")

        project.getTasks().named("compileJava").configure(task ->
                task.dependsOn(generateJavaTask)
        )
        project.tasks.named("classes").configure {
            project.tasks.named("classes").get().doLast {
                try {
                    var extension = project.extensions.getByName("strGuard") as StrGuardExtension
                    if (extension.stringGuard) {
                        var strLog = Paths.get(project.layout.buildDirectory.get().asFile.toString(), "mappings", "strGuard", "string_guard_mapping.txt")
                        var metadataLog = Paths.get(project.layout.buildDirectory.get().asFile.toString(), "mappings", "strGuard", "remove_metadata_mapping.txt")
                        var log = new ArrayList<String>()
                        var log2 = new ArrayList<String>()
                        var strGuard = new StrGuardImpl()
                        var success = false
                        var generator = extension.keyGenerator
                        try {
                            Files.createDirectories(strLog.getParent())
                        } catch (IOException ignored) {

                        }
                        if (extension.generateMappings && strLog.toFile().exists())
                            strLog.toFile().delete()

                        if (extension.generateMappings && metadataLog.toFile().exists())
                            metadataLog.toFile().delete()

                        Files.walk(Paths.get(project.layout.buildDirectory.get().asFile.toString(), "classes"))
                                .forEach(file -> {
                                    if (file.toFile().isFile() && file.toFile().name.endsWith(".class") && file.toFile().name != "module-info.class") {
                                        def bytes = transformClass(file.toFile(), strGuard, generator, log, log2, extension)
                                        if (bytes != null) {
                                            if (bytes.length == 0) {

                                            } else {
                                                var output = new BufferedOutputStream(new FileOutputStream(file.toFile()))
                                                output.write(bytes)
                                                output.flush()
                                                output.close()
                                                if (!success)
                                                    success = true
                                            }
                                        } else {
                                            println("PROCESS FAIL:" + file.toFile().absolutePath)
                                        }
                                    }
                                })
                        if (extension.generateMappings) {
                            writeLogsToFile(strLog, log)
                            writeLogsToFile(metadataLog, log2)
                        }
                    }

                } catch (IOException e) {
                    e.printStackTrace()
                }
            }
        }
    }

    private static JavaFile _StrGuardImpl() {
        def iStrGuard = ClassName.get("io.github.weg2022.strguard.api", "IStrGuard")
        def standardCharsets = ClassName.get("java.nio.charset", "StandardCharsets")

// encode 方法
        def encodeMethod = MethodSpec.methodBuilder("encode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(byte[].class)
                .addParameter(String.class, "raw")
                .addParameter(byte[].class, "key")
                .addStatement("return xor(raw.getBytes(\$T.UTF_8), key)", standardCharsets)
                .build()

// decode 方法
        def decodeMethod = MethodSpec.methodBuilder("decode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addParameter(byte[].class, "data")
                .addParameter(byte[].class, "key")
                .addStatement("return new String(xor(data, key), \$T.UTF_8)", standardCharsets)
                .build()

// xor 方法
        def xorMethod = MethodSpec.methodBuilder("xor")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(byte[].class)
                .addParameter(byte[].class, "data")
                .addParameter(byte[].class, "key")
                .addCode("""
int len = data.length;
int lenKey = key.length;
int i = 0;
int j = 0;
while (i < len) {
    if (j >= lenKey) {
        j = 0;
    }
    data[i] = (byte) (data[i] ^ key[j]);
    i++;
    j++;
}
return data;
        """)
                .build()

// StrGuardImpl 类定义
        def strGuardImpl = TypeSpec.classBuilder("StrGuardImpl")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(iStrGuard)
                .addMethod(encodeMethod)
                .addMethod(decodeMethod)
                .addMethod(xorMethod)
                .build()

        JavaFile.builder("io.github.weg2022.strguard.api", strGuardImpl).build()
    }

    private static JavaFile _StrGuard() {
        def iStrGuard = ClassName.get("io.github.weg2022.strguard.api", "IStrGuard")
        def strGuardImpl = ClassName.get("io.github.weg2022.strguard.api", "StrGuardImpl")

        def field = FieldSpec.builder(iStrGuard, "IMPL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new \$T()", strGuardImpl)
                .build()

        def decode = MethodSpec.methodBuilder("decode")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String)
                .addParameter(byte[].class, "value")
                .addParameter(byte[].class, "key")
                .addStatement("return IMPL.decode(value, key)")
                .build()

        def typeSpec = TypeSpec.classBuilder("StrGuard")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(field)
                .addMethod(decode)
                .build()

        JavaFile.builder("io.github.weg2022.strguard.api", typeSpec).build()
    }

    private static JavaFile _IStrGuard() {
        def encode = MethodSpec.methodBuilder("encode")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(byte[].class)
                .addParameter(String, "raw")
                .addParameter(byte[].class, "key")
                .build()

        def decode = MethodSpec.methodBuilder("decode")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(String)
                .addParameter(byte[].class, "data")
                .addParameter(byte[].class, "key")
                .build()


        def typeSpec = TypeSpec.interfaceBuilder("IStrGuard")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(encode)
                .addMethod(decode)
                .build()

        JavaFile.builder("io.github.weg2022.strguard.api", typeSpec).build()
    }

    private static JavaFile _KeepMetadata() {
        def retention = ClassName.get("java.lang.annotation", "Retention")
        def target = ClassName.get("java.lang.annotation", "Target")
        def retentionPolicy = ClassName.get("java.lang.annotation", "RetentionPolicy")
        def elementType = ClassName.get("java.lang.annotation", "ElementType")

        def retentionSpec = AnnotationSpec.builder(retention)
                .addMember("value", "\$T.CLASS", retentionPolicy)
                .build()

        def targetSpec = AnnotationSpec.builder(target)
                .addMember("value", "{\$T.TYPE}", elementType)
                .build()

        def annotationType = TypeSpec.annotationBuilder("KeepMetadata")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(retentionSpec)
                .addAnnotation(targetSpec)
                .build()

        JavaFile.builder("io.github.weg2022.strguard.annotation", annotationType)
                .addStaticImport(elementType, "TYPE")
                .build()
    }

    private static JavaFile _KeepString() {
        def retention = ClassName.get("java.lang.annotation", "Retention")
        def target = ClassName.get("java.lang.annotation", "Target")
        def retentionPolicy = ClassName.get("java.lang.annotation", "RetentionPolicy")
        def elementType = ClassName.get("java.lang.annotation", "ElementType")

        def retentionSpec = AnnotationSpec.builder(retention)
                .addMember("value", "\$T.CLASS", retentionPolicy)
                .build()

        def targetSpec = AnnotationSpec.builder(target)
                .addMember("value", "{\$T.TYPE}", elementType)
                .build()

        def annotationType = TypeSpec.annotationBuilder("KeepString")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(retentionSpec)
                .addAnnotation(targetSpec)
                .build()

        JavaFile.builder("io.github.weg2022.strguard.annotation", annotationType)
                .addStaticImport(elementType, "TYPE")
                .build()
    }

    private static void writeLogsToFile(Path logFilePath, List<String> logs) {
        try {
            Files.write(logFilePath, logs, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (IOException e) {
            e.printStackTrace()
        }
    }


    private static byte[] transformClass(File file,
                                         IStrGuard strGuard,
                                         IkeyGenerator generator,
                                         List<String> strLog,
                                         List<String> metadataLog,
                                         StrGuardExtension extension) {
        try {
            byte[] classBytes = Files.readAllBytes(file.toPath())
            var cr = new ClassReader(classBytes)
            var className = cr.getClassName()
            if (!isStrGuardPackages(className, extension.stringGuardPackages, extension.keepStringPackages)) {
                if (extension.consoleOutput)
                    println("StrGuard[SKIP]: " + className)
                return [] as byte[]
            } else {
                if (extension.consoleOutput)
                    println("StrGuard[PROCESS]: " + className)
                var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS)
                var visitor = new StrGuardClassVisitor(strGuard, strLog, metadataLog, StrGuard.class.name, cw, generator, extension, className)
                cr.accept(visitor, ClassReader.EXPAND_FRAMES)
                return cw.toByteArray()
            }
        } catch (Exception e) {
            e.printStackTrace()
            return null
        }
    }

    private static final String[] whiteList = [
            "io.github.weg2022.strguard"
    ]

    private static boolean isStrGuardPackages(String className, String[] guardPackages, String[] notGuardPackages) {
        if (className == null || className.trim().isEmpty()) {
            return false
        }
        whiteList.each { var name ->
            if (className.replace('/', '.').startsWith(name + ".")) {
                return false
            }
        }

        if (notGuardPackages != null && notGuardPackages.length > 0) {
            notGuardPackages.each { var name ->
                if (className.replace('/', '.').startsWith(name + ".")) {
                    return false
                }
            }
        }

        if (guardPackages != null && guardPackages.length > 0) {
            guardPackages.each { var name ->
                if (className.replace('/', '.').startsWith(name + ".")) {
                    return true
                }
            }
        }
        return false
    }

}
