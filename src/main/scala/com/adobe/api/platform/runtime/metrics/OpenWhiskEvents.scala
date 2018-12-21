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

import java.nio.charset.StandardCharsets.UTF_8

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity
import akka.kafka.ConsumerSettings
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import pureconfig.loadConfigOrThrow

import scala.concurrent.Future

object OpenWhiskEvents extends SLF4JLogging {

  case class MetricConfig(port: Int)

  def start(config: Config)(implicit system: ActorSystem,
                            materializer: ActorMaterializer): Future[Http.ServerBinding] = {
    val metricConfig = loadConfigOrThrow[MetricConfig](config, "user-events")
    val prometheus = new PrometheusReporter()
    Kamon.addReporter(prometheus)
    val port = metricConfig.port
    val kamonConsumer = EventConsumer(eventConsumerSettings(defaultConsumerConfig(config)), Seq(KamonConsumer))
    val api = new EventsApi(kamonConsumer, getExporter(prometheus))
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdownConsumer") { () =>
      kamonConsumer.shutdown()
    }
    val httpBinding = Http().bindAndHandle(api.routes, "0.0.0.0", port)
    httpBinding.foreach(_ => log.info(s"Started the http server on http://localhost:$port"))(system.dispatcher)
    httpBinding
  }

  def eventConsumerSettings(config: Config): ConsumerSettings[String, String] =
    ConsumerSettings(config, new StringDeserializer, new StringDeserializer)
      .withGroupId("kamon")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  def defaultConsumerConfig(globalConfig: Config): Config = globalConfig.getConfig("akka.kafka.consumer")

  private def getExporter(reporter: PrometheusReporter): PrometheusExporter =
    () => HttpEntity(PrometheusExporter.textV4, reporter.scrapeData().getBytes(UTF_8))

}
