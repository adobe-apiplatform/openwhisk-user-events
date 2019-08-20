/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics
import java.net.ServerSocket

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.adobe.api.platform.runtime.metrics.OpenWhiskEvents.MetricConfig
import com.typesafe.config.Config
import kamon.prometheus.PrometheusReporter
import pureconfig.loadConfigOrThrow

trait EventsTestHelper {

  protected def createConsumer(kport: Int,
                               globalConfig: Config,
                               recorder: MetricRecorder = PrometheusRecorder(new PrometheusReporter))(
    implicit system: ActorSystem,
    materializer: ActorMaterializer) = {
    val settings = OpenWhiskEvents
      .eventConsumerSettings(OpenWhiskEvents.defaultConsumerConfig(globalConfig))
      .withBootstrapServers(s"localhost:$kport")
    val metricConfig = loadConfigOrThrow[MetricConfig](globalConfig, "user-events")
    EventConsumer(settings, Seq(recorder), metricConfig)
  }

  protected def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally if (socket != null) socket.close()
  }
}
