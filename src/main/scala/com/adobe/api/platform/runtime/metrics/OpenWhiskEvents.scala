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
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.server.Route
import akka.kafka.ConsumerSettings
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object OpenWhiskEvents extends SLF4JLogging {
  val textV4 = ContentType.parse("text/plain; version=0.0.4; charset=utf-8").right.get

  case class MetricConfig(port: Int)

  def main(args: Array[String]): Unit = {
    val prometheus = new PrometheusReporter()
    Kamon.addReporter(prometheus)
    val metricConfig = loadConfigOrThrow[MetricConfig]("user-events")
    implicit val system: ActorSystem = ActorSystem("runtime-actor-system")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val port = metricConfig.port
    val kamonConsumer = KamonConsumer(eventConsumerSettings(defaultConsumerConfig(system)))
    val api = new EventsApi(kamonConsumer, prometheus)
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdownConsumer") { () =>
      kamonConsumer.shutdown()
    }

    startHttpService(api.routes, port)
    log.info(s"Started the http server on http://localhost:$port")
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
    implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
    sys.addShutdownHook {
      Await.result(binding.map(_.unbind()), 30.seconds)
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }

}
