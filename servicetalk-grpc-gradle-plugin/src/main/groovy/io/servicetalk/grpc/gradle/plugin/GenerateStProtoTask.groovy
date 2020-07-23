/*
 * Copyright © 2020 Apple Inc. and the ServiceTalk project authors
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

import groovy.transform.CompileDynamic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.LogLevel
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject

@CompileDynamic
@CacheableTask
abstract class GenerateStProtoTask extends DefaultTask {
  // Windows CreateProcess has command line limit of 32768:
  // https://msdn.microsoft.com/en-us/library/windows/desktop/ms682425(v=vs.85).aspx
  static final int WINDOWS_CMD_LENGTH_LIMIT = 32760
  // Extra command line length when added an additional argument on Windows.
  // Two quotes and a space.
  static final int CMD_ARGUMENT_EXTRA_LENGTH = 3
  static final String ST_GRPC_PLUGIN_PREFIX = "servicetalk_grpc"

  // include dirs are passed to the '-I' option of protoc.  They contain protos
  // that may be "imported" from the source protos, but will not be compiled.
  private final ConfigurableFileCollection includeDirs = objectFactory.fileCollection()
  // source files are proto files that will be compiled by protoc
  private final ConfigurableFileCollection sourceFiles = objectFactory.fileCollection()
  @SuppressWarnings("UnnecessaryTransientModifier") // It is not necessary for task to implement Serializable
  transient private SourceSet sourceSet
  private File protocFile
  private File protocPluginFile
  private String outputBaseDir

  @Inject
  abstract ObjectFactory getObjectFactory()

  /**
   * Add a directory to protoc's include path.
   */
  void addIncludeDir(FileCollection dir) {
    includeDirs.from(dir)
  }

  /**
   * Add a collection of proto source files to be compiled.
   */
  void addSourceFiles(FileCollection files) {
    sourceFiles.from(files)
  }

  void setSourceSet(SourceSet sourceSet) {
    this.sourceSet = sourceSet;
  }

  void setProtocFile(File protocFile) {
    this.protocFile = protocFile
  }

  void setProtocPluginFile(File protocPluginFile) {
    this.protocPluginFile = protocPluginFile
  }

  void setOutputBaseDir(String outputBaseDir) {
    this.outputBaseDir = outputBaseDir
  }

  @SkipWhenEmpty
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  FileCollection getSourceFiles() {
    return sourceFiles
  }

  @Internal
  SourceDirectorySet getOutputSourceDirectorySet() {
    String srcSetName = "generate-proto-" + ST_GRPC_PLUGIN_PREFIX
    SourceDirectorySet srcSet = objectFactory.sourceDirectorySet(srcSetName, srcSetName)
    srcSet.srcDir new File(getJavaOutputDir())
    return srcSet
  }

  private String getJavaOutputDir() {
    return "${outputBaseDir}/java"
  }

  @TaskAction
  void compile() {
    // Sort to ensure generated descriptors have a canonical representation
    // to avoid triggering unnecessary rebuilds downstream
    List<File> protoFiles = sourceFiles.files.sort()

    // The source directory designated from sourceSet may not actually exist on disk.
    // "include" it only when it exists, so that Gradle and protoc won't complain.
    List<String> dirs = includeDirs.filter { it.exists() }*.path.collect { "-I${it}".toString() }
    logger.debug "ProtobufCompile using directories ${dirs}"
    logger.debug "ProtobufCompile using files ${protoFiles}"

    List<String> baseCmd = [ protocFile.getAbsolutePath() ]
    baseCmd.addAll(dirs)

    String javaOutDir = getJavaOutputDir()
    new File(javaOutDir).mkdirs()

    // Handle code generation built-ins
    baseCmd += "--java_out=${javaOutDir}"
    baseCmd += "--plugin=protoc-gen-${ST_GRPC_PLUGIN_PREFIX}=${protocPluginFile}"
    baseCmd += "--${ST_GRPC_PLUGIN_PREFIX}_out=${javaOutDir}"

    List<List<String>> cmds = generateCmds(baseCmd, protoFiles, getCmdLengthLimit())
    for (List<String> cmd : cmds) {
      compileFiles(cmd)
    }
  }

  private void compileFiles(List<String> cmd) {
    logger.log(LogLevel.INFO, cmd.toString())

    StringBuffer stdout = new StringBuffer()
    StringBuffer stderr = new StringBuffer()
    Process result = cmd.execute()
    result.waitForProcessOutput(stdout, stderr)
    String output = "protoc: stdout: ${stdout}. stderr: ${stderr}"
    if (result.exitValue() == 0) {
      logger.log(LogLevel.INFO, output)
    } else {
      throw new GradleException(output)
    }
  }

  static int getCmdLengthLimit() {
    return getCmdLengthLimit(System.getProperty("os.name"))
  }

  static int getCmdLengthLimit(String os) {
    if (os != null && os.toLowerCase(Locale.ROOT).indexOf("win") > -1) {
      return WINDOWS_CMD_LENGTH_LIMIT
    }
    return Integer.MAX_VALUE
  }

  static List<List<String>> generateCmds(List<String> baseCmd, List<File> protoFiles, int cmdLengthLimit) {
    List<List<String>> cmds = []
    if (!protoFiles.isEmpty()) {
      int baseCmdLength = baseCmd.sum { it.length() + CMD_ARGUMENT_EXTRA_LENGTH }
      List<String> currentArgs = []
      int currentArgsLength = 0
      for (File proto: protoFiles) {
        String protoFileName = proto
        int currentFileLength = protoFileName.length() + CMD_ARGUMENT_EXTRA_LENGTH
        // Check if appending the next proto string will overflow the cmd length limit
        if (baseCmdLength + currentArgsLength + currentFileLength > cmdLengthLimit) {
          // Add the current cmd before overflow
          cmds.add(baseCmd + currentArgs)
          currentArgs.clear()
          currentArgsLength = 0
        }
        // Append the proto file to the args
        currentArgs.add(protoFileName)
        currentArgsLength += currentFileLength
      }
      // Add the last cmd for execution
      cmds.add(baseCmd + currentArgs)
    }
    return cmds
  }
}
