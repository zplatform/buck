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

package com.facebook.buck.util.environment;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

public final class NetworkInfo {
  private static final Logger LOG = Logger.get(NetworkInfo.class);
  public static class Event extends AbstractBuckEvent {
    Network network;

    public Event(Network network) {
      super(EventKey.unique());
      this.network = network;
    }

    public Network getNetwork() {
      return network;
    }

    @Override
    public String getEventName() {
      return "NetworkInfoEvent";
    }

    @Override
    protected String getValueString() {
      return network.toString();
    }
  }

  // Buck's own integration tests will run with this system property
  // set to false.
  //
  // Otherwise, we would need to add libjcocoa.dylib to
  // java.library.path, which could interfere with external Java
  // tests' own C library dependencies.
  private static final boolean ENABLE_OBJC = Boolean.getBoolean("buck.enable_objc");

  private NetworkInfo() {}

  public static void generateActiveNetworkAsync(
      ExecutorService executorService, BuckEventBus buckEventBus) {
    executorService.submit(
        () -> {
          buckEventBus.post(new Event(getLikelyActiveNetwork()));
        });
  }

  public static Network getLikelyActiveNetwork() {
    if (ENABLE_OBJC) {
      // The ObjC bridge's native library is not universal/arm64 on every machine (e.g. an
      // arm64 JVM cannot dlopen an x86_64-only libjcocoa.dylib). This is best-effort telemetry,
      // so degrade to UNKNOWN instead of taking down the whole command.
      try {
        return MacNetworkConfiguration.getLikelyActiveNetwork();
      } catch (Throwable t) {
        LOG.warn(t, "Failed to determine active network via ObjC bridge; reporting UNKNOWN");
      }
    }
    return new Network(NetworkMedium.UNKNOWN);
  }

  public static Optional<String> getWifiSsid() {
    // TODO(royw): Support Linux and Windows.
    if (ENABLE_OBJC) {
      // See getLikelyActiveNetwork() above: same native-library caveat, same degrade-not-crash
      // handling.
      try {
        return MacWifiSsidFinder.findCurrentSsid();
      } catch (Throwable t) {
        LOG.warn(t, "Failed to determine wifi SSID via ObjC bridge; reporting none");
      }
    }
    return Optional.empty();
  }
}
