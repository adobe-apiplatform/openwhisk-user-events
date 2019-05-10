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

import akka.kafka.testkit.scaladsl.{EmbeddedKafkaLike, ScalatestKafkaSpec}
import akka.stream.ActorMaterializer
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

abstract class KafkaSpecBase
    extends ScalatestKafkaSpec(6061)
    with Matchers
    with ScalaFutures
    with FlatSpecLike
    with EmbeddedKafka
    with EmbeddedKafkaLike
    with IntegrationPatience
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Eventually
    with EventsTestHelper { this: Suite =>
  implicit val timeoutConfig = PatienceConfig(1.minute)

  implicit val materializer = ActorMaterializer()

  override def createKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort, zooKeeperPort)

  override def sleepAfterProduce: FiniteDuration = 10.seconds

  def waitForMessages(count: Int = 1) = {
    sleep(sleepAfterProduce, "sleeping post produce")
    consumeNumberMessagesFromTopics(Set(EventConsumer.userEventTopic), count, timeout = 60.seconds)(
      createKafkaConfig,
      new StringDeserializer)(EventConsumer.userEventTopic)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    shutdown()
  }
}
