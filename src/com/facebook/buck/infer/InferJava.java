/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.infer;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Generate a command line for infer with all required dependencies set.
 *
 * <p>Integrates with {@link com.facebook.buck.android.AndroidLibrary} and {@link JavaLibrary} via a
 * set of flavors.
 */
public final class InferJava extends ModernBuildRule<InferJava.Impl> {
  /**
   * {@code #nullsafe} flavor for a Java rule that performs nullsafe analysis for the rule.
   *
   * <p>The result of the analysis is captured in a JSON file generated by the rule.
   */
  public static final Flavor INFER_NULLSAFE = InternalFlavor.of("nullsafe");

  /**
   * {@code #infer-java-capture} flavor for a Java rule that performs the capture phase (a
   * preliminary to analysis).
   *
   * <p>The result of the capture phase (infer-out) is exposed as the output of the rule.
   */
  public static final Flavor INFER_JAVA_CAPTURE = InternalFlavor.of("infer-java-capture");

  // All flavors provided by the build rule.
  private static final ImmutableList<Flavor> providedFlavors =
      ImmutableList.of(INFER_JAVA_CAPTURE, INFER_NULLSAFE);

  // This is the default name of the JSON report which is suitable for consumption
  // by tools and automation.
  private static final String INFER_JSON_REPORT_FILE = "report.json";

  // This flag instructs infer to run only nullsafe related checks. In practice, we may want to
  // add some other checks to run alongside nullsafe.
  private static final ImmutableList<String> NULLSAFE_DEFAULT_ARGS =
      ImmutableList.of("--eradicate-only");

  /** Supported commands to infer binary. */
  enum Command {
    RUN,
    CAPTURE,
  }

  /**
   * @return first flavor that matches one of the supported InferJava flavors, empty optional
   *     otherwise.
   */
  public static Optional<Flavor> findSupportedFlavor(FlavorSet flavors) {
    for (Flavor aFlavor : providedFlavors) {
      if (flavors.contains(aFlavor)) {
        return Optional.of(aFlavor);
      }
    }

    return Optional.empty();
  }

  private InferJava(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      Impl buildable) {
    super(buildTarget, filesystem, ruleFinder, buildable);
  }

  /**
   * Builds {@link InferJava} rule with a properly setup dependency on base library.
   *
   * <p>Requires {@code javacOptions} to have fully configured {code bootclasspath}. For Android
   * bootclasspath usually provided separately via {@code extraClasspathProviderSupplier}.
   */
  public static InferJava create(
      Flavor flavor,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      JavacOptions javacOptions,
      Optional<ExtraClasspathProvider> extraClasspathProvider,
      UnresolvedInferPlatform unresolvedInferPlatform,
      InferConfig inferConfig,
      DownwardApiConfig downwardApiConfig) {
    BuildTarget unflavored = buildTarget.withoutFlavors();
    JavaLibrary baseLibrary = (JavaLibrary) graphBuilder.requireRule(unflavored);

    ImmutableSortedSet<SourcePath> sources = baseLibrary.getJavaSrcs();
    ImmutableSortedSet<SourcePath> compileTimeClasspath = calcCompilationClasspath(baseLibrary);
    SourcePath generatedClassesJar = baseLibrary.getSourcePathToOutput();

    InferPlatform platform =
        unresolvedInferPlatform.resolve(graphBuilder, buildTarget.getTargetConfiguration());

    Command command;
    ImmutableList<String> args;
    Optional<String> limitToOutputFile;

    if (flavor.equals(INFER_NULLSAFE)) {
      command = Command.RUN;

      ImmutableList<String> nullsafeArgs = inferConfig.getNullsafeArgs();
      // Ensure sensible default behavior
      if (nullsafeArgs.isEmpty()) {
        args = NULLSAFE_DEFAULT_ARGS;
      } else {
        args = nullsafeArgs;
      }

      limitToOutputFile = Optional.of(INFER_JSON_REPORT_FILE);
    } else if (flavor.equals(INFER_JAVA_CAPTURE)) {
      command = Command.CAPTURE;
      // For now we hardcode this, as there's no need for other capture args
      args = ImmutableList.of("--genrule-mode");
      limitToOutputFile = Optional.empty(); // copy the whole infer-out
    } else {
      throw new UnsupportedOperationException("Unsupported flavor: " + flavor.getName());
    }

    return new InferJava(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        new Impl(
            platform,
            command,
            args,
            sources,
            compileTimeClasspath,
            javacOptions,
            extraClasspathProvider,
            generatedClassesJar,
            limitToOutputFile,
            inferConfig.getPrettyPrint(),
            downwardApiConfig.isEnabledForInfer()));
  }

  /**
   * Calculate compile time classpath from general dependencies of {@link JavaLibrary}, by filtering
   * only those deps that {@link HasJavaAbi}. This includes full libraries and/or ABI libraries
   * depending on the java compilation settings.
   *
   * <p>The resulting classpath can be not as minimal as the one from {@link
   * com.facebook.buck.jvm.java.DefaultJavaLibrary#getCompileTimeClasspathSourcePaths()} which gives
   * a more accurate list. But is pretty close to it and works for all kinds of {@link JavaLibrary}s
   * and not only those that inherit from DefaultJavaLibrary.
   *
   * <p>Note: this method plays nicely with the way libraries are commonly built in the project,
   * since it takes whatever jar (full or ABI) is available during regular build, but it is not
   * suited for situations when you actually need to access full jars of dependencies.
   */
  private static ImmutableSortedSet<SourcePath> calcCompilationClasspath(JavaLibrary baseLibrary) {
    return baseLibrary.getBuildDeps().stream()
        .filter(HasJavaAbi.class::isInstance) // take only deps that produce jars
        .filter(x -> !areBuildingInterfaceForSameLibrary(baseLibrary, x))
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
  }

  /**
   * With ABI compilation, a {@link JavaLibrary} rule usually has as a dependency a {@link
   * CalculateAbi} rule that builds the interface for the same library. We **should exclude** such
   * dependencies since dumping into the classpath an ABI jar for the library under analysis will
   * mess up nullsafe.
   */
  private static boolean areBuildingInterfaceForSameLibrary(
      JavaLibrary baseLibrary, BuildRule other) {
    return (other instanceof CalculateAbi || other instanceof JavaLibrary)
        && other.getBuildTarget().withoutFlavors().equals(baseLibrary.getBuildTarget());
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    if (getBuildable().producesOutput()) {
      return getSourcePath(getBuildable().output);
    } else {
      return null;
    }
  }

  /** {@link Buildable} that is responsible for running Nullsafe. */
  static class Impl implements Buildable {
    private static final String INFER_DEFAULT_RESULT_DIR = "infer-out";

    @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> classpath;

    @AddToRuleKey private final JavacOptions javacOptions;
    @AddToRuleKey private final Optional<ExtraClasspathProvider> extraClasspathProvider;

    @AddToRuleKey private final InferPlatform inferPlatform;
    @AddToRuleKey private final Command command;
    @AddToRuleKey private final ImmutableList<String> args;
    @AddToRuleKey @Nullable private final SourcePath generatedClasses;

    // A non-empty limitToOutputFile allows to pick a particular file from infer-out as output of
    // the build rule. When not set the whole infer-out is going to be exposed as build rule output.
    // Ideally, we should be able to expose a subset of infer-out as output (because we don't really
    // want tmp/ or something like that in the output).
    @AddToRuleKey private final Optional<String> limitToOutputFile;
    @AddToRuleKey private final OutputPath output;

    // Whether to pretty print a list of issues to console and report.txt.
    // Pretty printing every time during build is distracting and incurs
    // ~10% overhead, so we don't want to do it, unless explicitly asked.
    @AddToRuleKey private final Boolean prettyPrint;

    @AddToRuleKey private final boolean withDownwardApi;

    Impl(
        InferPlatform inferPlatform,
        Command command,
        ImmutableList<String> args,
        ImmutableSortedSet<SourcePath> sources,
        ImmutableSortedSet<SourcePath> classpath,
        JavacOptions javacOptions,
        Optional<ExtraClasspathProvider> extraClasspathProvider,
        @Nullable SourcePath generatedClasses,
        Optional<String> limitToOutputFile,
        Boolean prettyPrint,
        boolean withDownwardApi) {
      this.inferPlatform = inferPlatform;
      this.command = command;
      this.args = args;
      this.sources = sources;
      this.classpath = classpath;
      this.javacOptions = javacOptions;
      this.extraClasspathProvider = extraClasspathProvider;
      this.generatedClasses = generatedClasses;
      this.limitToOutputFile = limitToOutputFile;
      this.output = limitToOutputFile.map(OutputPath::new).orElseGet(() -> new OutputPath("."));
      this.prettyPrint = prettyPrint;
      this.withDownwardApi = withDownwardApi;
    }

    private static void addNotEmpty(
        ImmutableList.Builder<String> argBuilder, String argName, String argValue) {
      if (!argValue.isEmpty()) {
        argBuilder.add(argName, argValue);
      }
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      if (!producesOutput()) {
        return ImmutableList.of();
      }

      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();

      AbsPath scratchDir = filesystem.resolve(outputPathResolver.getTempPath());
      RelPath argFilePath = filesystem.relativize(scratchDir.resolve("args.txt"));
      RelPath inferOutPath = filesystem.relativize(scratchDir.resolve(INFER_DEFAULT_RESULT_DIR));

      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      // Prepare infer command line arguments and write them to args.txt
      ImmutableList<String> argsBuilder =
          buildArgs(filesystem, inferOutPath, sourcePathResolverAdapter);
      steps.add(
          WriteFileStep.of(
              filesystem.getRootPath(),
              Joiner.on(System.lineSeparator()).join(argsBuilder),
              argFilePath.getPath(),
              false));

      // Prepare and invoke cmd with appropriate environment
      ImmutableList<String> cmd = buildCommand(argFilePath, sourcePathResolverAdapter);
      ImmutableMap<String, String> cmdEnv = buildEnv(sourcePathResolverAdapter);
      steps.add(
          new DefaultShellStep(filesystem.getRootPath(), withDownwardApi, cmd, cmdEnv) {
            @Override
            protected boolean shouldPrintStdout(Verbosity verbosity) {
              return verbosity.shouldPrintBinaryRunInformation();
            }

            @Override
            protected boolean shouldPrintStderr(Verbosity verbosity) {
              return verbosity.shouldPrintBinaryRunInformation();
            }
          });

      if (this.limitToOutputFile.isPresent()) {
        Path outputTarget = inferOutPath.resolve(this.limitToOutputFile.get());
        steps.add(
            CopyStep.forFile(outputTarget, outputPathResolver.resolvePath(this.output).getPath()));
      } else {
        steps.add(
            CopyStep.forDirectory(
                inferOutPath,
                outputPathResolver.resolvePath(this.output),
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      }

      return steps.build();
    }

    public boolean producesOutput() {
      return generatedClasses != null && !sources.isEmpty();
    }

    private ImmutableMap<String, String> buildEnv(
        SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableMap.Builder<String, String> cmdEnv = ImmutableMap.builder();
      inferPlatform.getInferVersion().ifPresent(v -> cmdEnv.put("INFERVERSION", v));
      inferPlatform
          .getInferConfig()
          .map(sourcePathResolverAdapter::getAbsolutePath)
          .map(Objects::toString)
          .ifPresent(c -> cmdEnv.put("INFERCONFIG", c));

      return cmdEnv.build();
    }

    private ImmutableList<String> buildCommand(
        RelPath argFilePath, SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableList.Builder<String> cmd = ImmutableList.builder();
      cmd.addAll(inferPlatform.getInferBin().getCommandPrefix(sourcePathResolverAdapter));
      cmd.add(command.name().toLowerCase());
      cmd.add("@" + argFilePath.toString());

      return cmd.build();
    }

    private ImmutableList<String> buildArgs(
        ProjectFilesystem filesystem,
        RelPath inferOutPath,
        SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
      argsBuilder.addAll(args);
      argsBuilder.add(
          "--project-root",
          filesystem.getRootPath().toString(),
          "--jobs",
          "1",
          "--results-dir",
          inferOutPath.toString());

      if (!prettyPrint) {
        argsBuilder.add("--quiet");
      }

      sources.stream()
          .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
          .map(RelPath::toString)
          .forEach(s -> argsBuilder.add("--sources", s));

      addNotEmpty(
          argsBuilder,
          "--classpath",
          classpath.stream()
              .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
              .map(RelPath::toString)
              .collect(Collectors.joining(File.pathSeparator)));

      JavacOptions buildTimeOptions =
          extraClasspathProvider
              .map(javacOptions::withBootclasspathFromContext)
              .orElse(javacOptions);

      Optional<String> bootClasspathOverride = buildTimeOptions.getBootclasspath();
      ImmutableList<SourcePath> bootClasspath = buildTimeOptions.getSourceLevelBootclasspath();

      addNotEmpty(
          argsBuilder,
          "--bootclasspath",
          bootClasspathOverride.orElseGet(
              () ->
                  bootClasspath.stream()
                      .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
                      .map(RelPath::toString)
                      .collect(Collectors.joining(File.pathSeparator))));

      argsBuilder.add(
          "--generated-classes",
          filesystem
              .relativize(sourcePathResolverAdapter.getAbsolutePath(generatedClasses))
              .toString());

      // Ideally, this should go up top to InferJava.create, but having it here for non-nullsafe
      // use-cases is not a big deal, so let's leave it for now.
      inferPlatform
          .getNullsafeThirdPartySignatures()
          .map(x -> sourcePathResolverAdapter.getAbsolutePath(x).toString())
          .ifPresent(dir -> addNotEmpty(argsBuilder, "--nullsafe-third-party-signatures", dir));

      return argsBuilder.build();
    }
  }
}
