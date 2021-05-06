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

package com.facebook.buck.workertool;

import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.worker.WorkerProcess;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Interface for WorkerTool executor that implements that WTv2 protocol. It executes commands by
 * sending it to worker tool using created command's named pipe file.
 */
public interface WorkerToolExecutor extends WorkerProcess {

  /** Send an execution command to a worker tool instance and wait till the command executed. */
  ResultEvent executeCommand(String actionId, AbstractMessage executeCommandMessage)
      throws IOException, ExecutionException, InterruptedException;

  /**
   * Send an execution pipelining command to a worker tool instance and immediately return a list of
   * result event's futures that represent the result.
   */
  ImmutableList<Future<ResultEvent>> executePipeliningCommand(
      ImmutableList<String> actionIds, AbstractMessage executeCommandMessage)
      throws IOException, ExecutionException, InterruptedException;
}
