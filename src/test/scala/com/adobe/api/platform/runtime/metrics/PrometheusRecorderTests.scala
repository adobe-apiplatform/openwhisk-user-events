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
      val initiatorTest = "testNS"
      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$action", kind, memory, initiatorTest).serialize)

      val initiatorGuest = "guest"
      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$action", kind, memory, initiatorGuest).serialize)

      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      counterTotal(initiatorTest, activationMetric) shouldBe 1
      counter(initiatorTest, coldStartMetric) shouldBe 1
      counterStatus(initiatorTest, statusMetric, Activation.statusDeveloperError) shouldBe 1

      histogramCount(initiatorTest, waitTimeMetric) shouldBe 1
      histogramSum(initiatorTest, waitTimeMetric) shouldBe (0.03 +- 0.001)

      histogramCount(initiatorTest, initTimeMetric) shouldBe 1
      histogramSum(initiatorTest, initTimeMetric) shouldBe (433.433 +- 0.01)

      histogramCount(initiatorTest, durationMetric) shouldBe 1
      histogramSum(initiatorTest, durationMetric) shouldBe (1.254 +- 0.01)

      gauge(initiatorTest, memoryMetric) shouldBe 1

      // blacklisted namespace should not be tracked
      counterTotal(initiatorGuest, activationMetric) shouldBe null
    }
  }

  private def newActivationEvent(name: String, kind: String, memory: String, initiator: String) =
    EventMessage(
      "test",
      Activation(name, 2, 1254.millis, 30.millis, 433433.millis, kind, false, memory.toInt, None),
      "testuser",
      initiator,
      "test",
      Activation.typeName)

  private def gauge(initiator: String, name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counter(initiator: String, name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counterTotal(initiator: String, name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "kind", "memory"),
      Array(namespace, initiator, action, kind, memory))

  private def counterStatus(initiator: String, name: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "status"),
      Array(namespace, initiator, action, status))

  private def histogramCount(initiator: String, name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def histogramSum(initiator: String, name: String) =
    CollectorRegistry.defaultRegistry
      .getSampleValue(s"${name}_sum", Array("namespace", "initiator", "action"), Array(namespace, initiator, action))
      .doubleValue()
}
