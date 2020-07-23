/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.grpc.gradle.plugin

import com.google.gradle.osdetector.OsDetectorPlugin
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.GenerateIdeaModule
import org.gradle.plugins.ide.idea.IdeaPlugin

import java.nio.charset.StandardCharsets

class ServiceTalkGrpcPlugin implements Plugin<Project> {
  void apply(Project project) {
    project.apply([plugin: OsDetectorPlugin])

    ServiceTalkGrpcExtension extension = project.extensions.create("serviceTalkGrpc", ServiceTalkGrpcExtension)
    extension.conventionMapping.generatedCodeDir = { project.file("$project.buildDir/generated/source/proto") }

    def compileOnlyDeps = project.getConfigurations().getByName("compileOnly").getDependencies()
    def testCompileOnlyDeps = project.getConfigurations().getByName("testCompileOnly").getDependencies()

    project.afterEvaluate {
      Properties pluginProperties = new Properties()
      pluginProperties.load(getClass().getResourceAsStream("/META-INF/servicetalk-grpc-gradle-plugin.properties"))

      // In order to locate servicetalk-grpc-protoc we need either the ServiceTalk version for artifact resolution
      // or be provided with a direct path to the protoc plugin executable
      def serviceTalkGrpcProtoc = "servicetalk-grpc-protoc"
      String serviceTalkVersion = pluginProperties."implementation-version"
      def serviceTalkProtocPluginPath = extension.serviceTalkProtocPluginPath
      if (!isVersion(serviceTalkVersion) && !serviceTalkProtocPluginPath) {
        throw new InvalidUserDataException("Failed to retrieve ServiceTalk version from plugin meta " +
            "and `serviceTalkGrpc.serviceTalkProtocPluginPath` is not set.")
      }

      def protobufVersion = extension.protobufVersion
      if (!protobufVersion) {
        throw new InvalidUserDataException("Please set `serviceTalkGrpc.protobufVersion`.")
      }

      def serviceTalkGrpcProtocGenerateScriptTask = project.task("serviceTalkGrpcProtocGenerateScript", {
        Set<Task> deleteTasks = new HashSet<>()
        project.allprojects.forEach({ subProject ->
          deleteTasks.addAll(subProject.tasks.withType(Delete))
          deleteTasks.add(subProject.tasks.findByPath('clean'))
        })
        deleteTasks.remove(null)
        mustRunAfter = deleteTasks

        // If this project is outside of ServiceTalk's gradle build we need to add an explicit dependency on the
        // uber jar which contains the protoc logic, as otherwise the grpc-gradle-plugin will only add a dependency
        // on the executable script
        File uberJarFile
        if (serviceTalkProtocPluginPath) {
          uberJarFile = new File(serviceTalkProtocPluginPath.toString())
        } else {
          def stGrpcProtocDep =
              project.getDependencies().create("io.servicetalk:$serviceTalkGrpcProtoc:$serviceTalkVersion:all")
          compileOnlyDeps.add(stGrpcProtocDep)
          testCompileOnlyDeps.add(stGrpcProtocDep)

          Object rawUberJarFile = project.configurations.compileOnly.find { it.name.startsWith(serviceTalkGrpcProtoc) }
          if (!(rawUberJarFile instanceof File)) {
            throw new IllegalStateException("Failed to find the $serviceTalkGrpcProtoc:$serviceTalkVersion:all. found: " + rawUberJarFile)
          }
          uberJarFile = (File) rawUberJarFile
        }

        final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows")
        File scriptExecutableFile = new File("${project.buildDir}/scripts/${serviceTalkGrpcProtoc}." +
            (isWindows ? "bat" : "sh"))

        doFirst {
          try {
            if (createNewScriptFile(scriptExecutableFile)) {
              if (isWindows) {
                new FileOutputStream(scriptExecutableFile).withCloseable { execOutputStream ->
                  execOutputStream.write(("@ECHO OFF\r\n" +
                      "java -jar " + uberJarFile.getAbsolutePath() + " %*\r\n").getBytes(StandardCharsets.US_ASCII))
                }
              } else {
                new FileOutputStream(scriptExecutableFile).withCloseable { execOutputStream ->
                  execOutputStream.write(("#!/bin/sh\n" +
                      "exec java -jar " + uberJarFile.getAbsolutePath() + " \"\$@\"\n").getBytes(StandardCharsets.US_ASCII))
                }
              }
              finalizeScriptFile(scriptExecutableFile)
            }
          } catch (Exception e) {
            throw new IllegalStateException("$serviceTalkGrpcProtoc plugin failed to create executable script file which executes the protoc jar plugin.", e)
          }
        }

        outputs.file(scriptExecutableFile)
      })

      def protocConfig = project.configurations.create("serviceTalkGrpcProtobufConfig") {
        visible = false
        transitive = false
        extendsFrom = []
      }
      def protocDef = project.dependencies.add(protocConfig.name,
          [group: 'com.google.protobuf', name: 'protoc', version: protobufVersion, classifier: project.osdetector.classifier, ext: 'exe'])
      def protocFile = protocConfig.fileCollection(protocDef).singleFile

      project.sourceSets.each { sourceSet ->
        addTasksForSourceSet(project, sourceSet, extension, protocFile, serviceTalkGrpcProtocGenerateScriptTask)
      }

      }
  }

  /**
   * Creates Protobuf tasks for a sourceSet in a Java project.
   */
  private static void addTasksForSourceSet(Project project, SourceSet sourceSet, ServiceTalkGrpcExtension extension,
                                           File protocFile, Task protocPluginTask) {
    GenerateStProtoTask generateProtoTask = addGenerateProtoTask(project, sourceSet, extension, protocFile, protocPluginTask)
    generateProtoTask.sourceSet = sourceSet
    generateProtoTask.dependsOn(protocPluginTask)
    Task javaTask = project.tasks.findByName(sourceSet.getCompileTaskName("java"))
    javaTask.dependsOn(generateProtoTask)
    SourceDirectorySet generateProtoTaskOutputSrcDir = generateProtoTask.getOutputSourceDirectorySet()
    javaTask.source generateProtoTaskOutputSrcDir

    generateProtoTaskOutputSrcDir.srcDirs.each { File outputDir ->
      addToIdeSources(project, extension, Utils.isTest(sourceSet.name), outputDir)
    }

    Task extractTask = setupExtractProtosTask(project, sourceSet, generateProtoTask)
    setupExtractIncludeProtosTask(project, sourceSet, generateProtoTask, extension)

    // Include source proto files in the compiled archive, so that proto files from
    // dependent projects can import them.
    Task processResourcesTask =
        project.tasks.getByName(sourceSet.getTaskName('process', 'resources'))
    processResourcesTask.from(generateProtoTask.sourceFiles) { include '**/*.proto' }
    processResourcesTask.dependsOn(extractTask)
  }

  private static GenerateStProtoTask addGenerateProtoTask(Project project, SourceSet sourceSet,
                                                          ServiceTalkGrpcExtension extension, File protocFile,
                                                          Task protocPluginTask) {
    String generateProtoTaskName = 'stGenerate' +
        Utils.getSourceSetSubstringForTaskNames(sourceSet.name) + 'Proto'
    return project.tasks.create(generateProtoTaskName, GenerateStProtoTask) {
      description = "Compiles Proto source for '${sourceSet.name}'"
      outputBaseDir = "${extension.generatedCodeDir}/${sourceSet.name}"

      SourceDirectorySet protoSrcDirSet = (SourceDirectorySet) sourceSet.extensions.findByName('proto')
      if (protoSrcDirSet == null) {
        protoSrcDirSet = project.objects.sourceDirectorySet(sourceSet.name, "${sourceSet.name} Proto source")
        sourceSet.extensions.add('proto', protoSrcDirSet)
      }

      protoSrcDirSet.srcDirs.each { File protoDir ->
        addToIdeSources(project, extension, Utils.isTest(sourceSet.name), protoDir)
      }

      protoSrcDirSet.srcDir("src/${sourceSet.name}/proto")
      protoSrcDirSet.include("**/*.proto")

      addIncludeDir(protoSrcDirSet.sourceDirectories)
      addSourceFiles(sourceSet.proto)
      setProtocFile(protocFile)
      setProtocPluginFile(protocPluginTask.outputs.files.singleFile)
    }
  }

  private static void setupExtractIncludeProtosTask(
      Project project, SourceSet sourceSet, GenerateStProtoTask generateProtoTask, ServiceTalkGrpcExtension extension) {
    FileCollection compileClasspathConfiguration = createConfigurations(project, sourceSet.name)

    String extractIncludeProtosTaskName = 'stExtractInclude' +
        Utils.getSourceSetSubstringForTaskNames(sourceSet.name) + 'Proto'
    Task task = project.tasks.findByName(extractIncludeProtosTaskName)
    if (task == null) {
      task = project.tasks.create(extractIncludeProtosTaskName, ProtobufExtract) {
        description = "Extracts proto files from compile dependencies for includes"
        destDir = "${project.buildDir}/extracted-include-protos/${sourceSet.name}" as File
        inputFiles.from(compileClasspathConfiguration)

        // TL; DR: Make protos in 'test' sourceSet able to import protos from the 'main'
        // sourceSet.  Sub-configurations, e.g., 'testCompile' that extends 'compile', don't
        // depend on the their super configurations. As a result, 'testCompile' doesn't depend on
        // 'compile' and it cannot get the proto files from 'main' sourceSet through the
        // configuration.
        // In Java projects, the compileClasspath of the 'test' sourceSet includes all the
        // 'resources' of the output of 'main', in which the source protos are placed.  This is
        // nicer than the ad-hoc solution that Android has, because it works for any extended
        // configuration, not just 'testCompile'.
        inputFiles.from sourceSet.compileClasspath
      }
      addToIdeSources(project, extension, Utils.isTest(sourceSet.name), task.destDir)
    }
    generateProtoTask.dependsOn(task)
    generateProtoTask.addIncludeDir(project.files(task.destDir))
  }

  private static Task setupExtractProtosTask(
      Project project, SourceSet sourceSet, GenerateStProtoTask generateProtoTask) {
    String protobufConfigName = Utils.getConfigName(sourceSet.name, 'protobuf')
    Configuration protobufConfig = project.configurations.findByName(protobufConfigName)
    if (protobufConfig == null) {
      protobufConfig = project.configurations.create(protobufConfigName) {
        visible = false
        transitive = true
        extendsFrom = []
      }
    }

    String extractProtosTaskName = 'stExtract' +
        Utils.getSourceSetSubstringForTaskNames(sourceSet.name) + 'Proto'
    Task task = project.tasks.findByName(extractProtosTaskName)
    if (task == null) {
      task = project.tasks.create(extractProtosTaskName, ProtobufExtract) {
        description = "Extracts proto files/dependencies specified by 'protobuf' configuration"
        destDir = "${project.buildDir}/extracted-protos/${sourceSet.name}" as File
        inputFiles.from(protobufConfig)
      }
    }

    generateProtoTask.dependsOn(task)
    generateProtoTask.addIncludeDir(project.files(task.destDir))
    generateProtoTask.addSourceFiles(project.fileTree(task.destDir) { include "**/*.proto" })
    return task
  }

  private static FileCollection createConfigurations(Project project, String sourceSetName) {
    // Create a 'compileProtoPath' configuration that extends compilation configurations
    // as a bucket of dependencies with resources attribute. This works around 'java-library'
    // plugin not exposing resources to consumers for compilation.
    // Some Android sourceSets (more precisely, variants) do not have compilation configurations,
    // they do not contain compilation dependencies, so they would not depend on any upstream
    // proto files.
    String compileProtoConfigName = Utils.getConfigName(sourceSetName, 'compileProtoPath')
    Configuration compileConfig =
        project.configurations.findByName(Utils.getConfigName(sourceSetName, 'compileOnly'))
    Configuration implementationConfig =
        project.configurations.findByName(Utils.getConfigName(sourceSetName, 'implementation'))
    FileCollection fc = project.configurations.findByName(compileProtoConfigName)
    if (compileConfig && implementationConfig && fc == null) {
      fc = project.configurations.create(compileProtoConfigName) {
        visible = false
        transitive = true
        extendsFrom = [compileConfig, implementationConfig]
        canBeConsumed = false
      }
      fc.getAttributes().attribute(
      LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
      project.getObjects().named(LibraryElements, LibraryElements.RESOURCES))
    }
    return fc
  }

  private static void addToIdeSources(Project project, ServiceTalkGrpcExtension extension, boolean isTest, File generatedFile) {
    if (!extension.generateIdeConfiguration) {
      return
    }

    project.plugins.withType(IdeaPlugin) {
      project.idea.module {
        List<File> generatedFiles = [generatedFile]
        if (isTest) {
          testSourceDirs += generatedFiles
        } else {
          sourceDirs += generatedFiles
        }

        generatedSourceDirs += generatedFiles
      }
    }
    project.tasks.withType(GenerateIdeaModule).each {
      it.doFirst {
        // This is required because the intellij plugin does not allow adding source directories
        // that do not exist. The intellij config files should be valid from the start even if a
        // user runs './gradlew idea' before running './gradlew generateProto'.
        generatedFile.mkdirs()
      }
    }

    project.plugins.withType(EclipsePlugin) {
      def addOptionalAttributesNode = { node ->
        def attributesNode = new Node(node, 'attributes')
        def attributeNode = new Node(attributesNode, 'attribute', [name: 'optional', value: 'true'])
        node.append(attributeNode)
        return node
      }

      project.eclipse {
        classpath {
          file {
            withXml {
              def node = it.asNode()
              addOptionalAttributesNode(new Node(node, 'classpathentry', [kind: 'src', path: project.relativePath(generatedFile)]))
            }
          }
        }
      }
    }
  }

  private static boolean createNewScriptFile(File outputFile) throws IOException {
    if (!outputFile.getParentFile().isDirectory() && !outputFile.getParentFile().mkdirs()) {
      throw new IOException("unable to make directories for file: " + outputFile.getCanonicalPath())
    }
    return true
  }

  private static void finalizeScriptFile(File outputFile) throws IOException {
    if (!outputFile.setExecutable(true)) {
      outputFile.delete()
      throw new IOException("unable to set file as executable: " + outputFile.getCanonicalPath())
    }
  }

  private static boolean isVersion(String text) {
    return text != null && !text.isEmpty() && text.charAt(0).isDigit()
  }
}
