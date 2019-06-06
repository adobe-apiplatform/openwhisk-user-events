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

import io.prometheus.client.CollectorRegistry
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PrometheusRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with PrometheusMetricNames {
  val sleepAfterProduce: FiniteDuration = 4.seconds

  behavior of "PrometheusConsumer"
  val namespace = "whisk.system"
  val action = "apimgmt/createApi"
  val kind = "nodejs:10"
  val memory = "256"

  it should "push user events to kamon" in {
    val kconfig = EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)
    withRunningKafkaOnFoundPort(kconfig) { implicit actualConfig =>
      createCustomTopic(EventConsumer.userEventTopic)

      val consumer = createConsumer(actualConfig.kafkaPort, system.settings.config)
      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$action", kind, memory).serialize)

      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      counterTotal(activationMetric) shouldBe 1
      counter(coldStartMetric) shouldBe 1
      counterStatus(statusMetric, Activation.statusDeveloperError) shouldBe 1

      histogramCount(waitTimeMetric) shouldBe 1
      histogramSum(waitTimeMetric) shouldBe 0.03

      histogramCount(initTimeMetric) shouldBe 1
      histogramSum(initTimeMetric) shouldBe 433.433

      histogramCount(durationMetric) shouldBe 1
      histogramSum(durationMetric) shouldBe 1.254

      gauge(memoryMetric) shouldBe 1
    }
  }

  private def newActivationEvent(name: String, kind: String, memory: String) =
    EventMessage(
      "test",
      Activation(name, 2, 1254, 30, 433433, kind, false, memory.toInt, None),
      "testuser",
      "testNS",
      "test",
      Activation.typeName)

  private def gauge(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "action"),
      Array(namespace, action))

  private def counter(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(name, Array("namespace", "action"), Array(namespace, action))

  private def counterTotal(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "action", "kind", "memory"),
      Array(namespace, action, kind, memory))

  private def counterStatus(name: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "action", "status"),
      Array(namespace, action, status))

  private def histogramCount(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "action"),
      Array(namespace, action))

  private def histogramSum(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_sum",
      Array("namespace", "action"),
      Array(namespace, action))


}
