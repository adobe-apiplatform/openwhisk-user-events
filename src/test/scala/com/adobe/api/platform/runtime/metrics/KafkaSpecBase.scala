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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import net.manub.embeddedkafka.EmbeddedKafka
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{FlatSpecLike, Matchers, Suite}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration

abstract class KafkaSpecBase
    extends TestKit(ActorSystem("test"))
    with Suite
    with Matchers
    with ScalaFutures
    with FlatSpecLike
    with EmbeddedKafka
    with IntegrationPatience
    with Eventually { this: Suite =>
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val materializer = ActorMaterializer()

  def sleep(time: FiniteDuration, msg: String = ""): Unit = {
    log.info(s"sleeping $time $msg")
    Thread.sleep(time.toMillis)
  }
}
