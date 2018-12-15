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

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.kafka.ConsumerSettings
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object OpenWhiskEvents {

  def main(args: Array[String]): Unit = {
    Kamon.addReporter(new PrometheusReporter())
    implicit val system = ActorSystem("runtime-actor-system")
    implicit val materializer = ActorMaterializer()

    val kamonConsumer = KamonConsumer(eventConsumerSettings(defaultConsumerConfig(system)))
    val route = get {
      path("ping") {
        complete("pong")
      }
    }

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdownConsumer") { () =>
      kamonConsumer.shutdown()
    }

    startHttpService(route, 9096) //TODO Make port configurable
  }

  def eventConsumerSettings(config: Config): ConsumerSettings[String, String] =
    ConsumerSettings(config, new StringDeserializer, new StringDeserializer)
      .withGroupId("kamon")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  def defaultConsumerConfig(system: ActorSystem): Config = system.settings.config.getConfig("akka.kafka.consumer")

  /**
   * Starts an HTTP(S) route handler on given port and registers a shutdown hook.
   */
  private def startHttpService(route: Route, port: Int)(implicit actorSystem: ActorSystem,
                                                        materializer: ActorMaterializer): Unit = {
    val httpBinding = Http().bindAndHandle(route, "0.0.0.0", port)
    addShutdownHook(httpBinding)
  }

  private def addShutdownHook(binding: Future[Http.ServerBinding])(implicit actorSystem: ActorSystem,
                                                                   materializer: ActorMaterializer): Unit = {
    implicit val executionContext = actorSystem.dispatcher
    sys.addShutdownHook {
      Await.result(binding.map(_.unbind()), 30.seconds)
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }

}
