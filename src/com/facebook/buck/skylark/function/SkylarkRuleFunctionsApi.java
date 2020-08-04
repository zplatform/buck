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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.rules.providers.impl.UserDefinedProvider;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.StarlarkFunction;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkConstructor;
import net.starlark.java.annot.StarlarkGlobalLibrary;
import net.starlark.java.annot.StarlarkMethod;

/**
 * Interface for a global Skylark library containing rule-related helper and registration functions.
 */
@StarlarkGlobalLibrary
public interface SkylarkRuleFunctionsApi {
  @StarlarkMethod(
      name = "Label",
      doc =
          "Creates a Label referring to a BUILD target. Use "
              + "this function only when you want to give a default value for the label "
              + "attributes. The argument must refer to an absolute label. "
              + "Example: <br><pre class=language-python>Label(\"//tools:default\")</pre>",
      parameters = {
        @Param(name = "label_string", type = String.class, doc = "the label string."),
      },
      useStarlarkThread = true)
  @StarlarkConstructor(objectType = Label.class)
  Label label(String labelString, StarlarkThread env) throws EvalException;

  @StarlarkMethod(
      name = "rule",
      doc =
          "Declares a User Defined Rule. The result is a callable that, when called by user, "
              + "adds a new target to the build. When that target is eventually evaluated, the "
              + "provided implementation function is called. This function creates actions for "
              + "Buck to execute, and handles linking inputs and outputs to the target via the "
              + "functions in `ctx`.",
      parameters = {
        @Param(
            name = "implementation",
            type = StarlarkFunction.class,
            noneable = false,
            positional = true,
            named = true,
            doc =
                "The function that implements the rule. It must take a single argument 'ctx' "
                    + "and if it returns anything, it must return a list of ProviderInfo objects."),
        @Param(
            name = "attrs",
            type = Dict.class,
            positional = false,
            named = true,
            doc =
                "A mapping of parameter names to `Attribute` instances (see `attr`). This controls "
                    + "what parameters are able to be passed by users into the callable that "
                    + "`rule()` returns, and what fields are available on `ctx.attr` in the "
                    + "implementation function"),
        @Param(
            name = "infer_run_info",
            type = Boolean.class,
            positional = false,
            named = true,
            defaultValue = "False",
            doc =
                "Whether a RunInfo provider should be inferred for this rule. If false, "
                    + "`implementation` must return a RunInfo provider in order to make the target "
                    + "executable. If true, the `implementation` function MUST not return a RunInfo "
                    + "provider. One will be created based on DefaultInfo. If a RunInfo instance "
                    + "cannot be inferred (e.g. if more than one default output was declared), "
                    + "an error will occur. "),
        @Param(
            name = "test",
            type = Boolean.class,
            positional = false,
            named = true,
            defaultValue = "False",
            doc =
                "Whether this rule is a test rule or not. If true, a TestInfo and RunInfo provider "
                    + "must be returned. If a TestInfo provider is not returned, Buck will attempt "
                    + "to create one from various implicit parameters.")
      },
      useStarlarkThread = true)
  SkylarkUserDefinedRule rule(
      StarlarkFunction implementation,
      Dict<String, AttributeHolder> attrs,
      boolean executable,
      boolean test,
      StarlarkThread env)
      throws EvalException;

  @StarlarkMethod(
      name = "provider",
      doc =
          "Creates a declared provider, which is both an identifier of, and constructor "
              + "used to create, \"struct-like\" values called Infos. Note that unlike other "
              + "build systems, a list of fields *must* be provided. If a schemaless struct is "
              + "desired, use the struct() function. If a less-schemaful provider is required, "
              + "a dictionary can be used for one of the fields. Example:<br>"
              + "<pre class=\"language-python\">DataInfo = provider(fields=[\"x\", \"y\", \"z\"])\n"
              + "d = DataInfo(x = 2, y = 3)\n"
              + "print(d.x + d.y) # prints 5"
              + "print(d.z == None) # prints True, as Z was not specified</pre>",
      parameters = {
        @Param(
            name = "doc",
            type = String.class,
            named = true,
            defaultValue = "''",
            doc =
                "A description of the provider that can be extracted by documentation generating tools."),
        @Param(
            name = "fields",
            doc =
                "Restricts the set of allowed fields. <br>"
                    + "Possible values are:"
                    + "<ul>"
                    + "  <li> list of fields:<br>"
                    + "       <pre class=\"language-python\">provider(fields = ['a', 'b'])</pre><p>"
                    + "  <li> dictionary field name -> documentation:<br>"
                    + "       <pre class=\"language-python\">provider(\n"
                    + "       fields = { 'a' : 'Documentation for a', 'b' : 'Documentation for b' })</pre>"
                    + "</ul>"
                    + "All fields are optional, and have the value None if not specified.<br>"
                    + "Documentation strings provided for a field in the dictionary form are not "
                    + "currently used by Buck itself, however they can be used by external "
                    + "documentation generating tools.",
            allowedTypes = {
              @ParamType(type = StarlarkList.class, generic1 = String.class),
              @ParamType(type = Dict.class)
            },
            noneable = false,
            named = true,
            positional = false,
            defaultValue = "[]")
      },
      useStarlarkThread = true)
  UserDefinedProvider provider(String doc, Object fields, StarlarkThread thread)
      throws EvalException;
}
