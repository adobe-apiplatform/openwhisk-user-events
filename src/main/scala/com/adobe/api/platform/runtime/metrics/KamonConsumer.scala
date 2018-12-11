/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.api.platform.runtime.metrics

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.concurrent.Future

case class KamonConsumer(settings: ConsumerSettings[String, String])(implicit system: ActorSystem,
                                                                     materializer: ActorMaterializer) {
  import KamonConsumer._

  def shutdown(): Future[Done] = {
    control.drainAndShutdown()(system.dispatcher)
  }

  //TODO Use RestartSource
  private val control: DrainingControl[Done] = Consumer
    .committableSource(settings, Subscriptions.topics(userEventTopic))
    .map { msg =>
      processEvent(msg.record.value())
      msg.committableOffset
    }
    .batch(max = 20, CommittableOffsetBatch(_))(_.updated(_))
    .mapAsync(3)(_.commitScaladsl())
    .toMat(Sink.ignore)(Keep.both)
    .mapMaterializedValue(DrainingControl.apply)
    .run()
}

object KamonConsumer {
  val userEventTopic = "events"

  private[metrics] def processEvent(value: String): Unit = {
    EventMessage
      .parse(value)
      .collect { case e if e.eventType == Activation.typeName => e } //Look for only Activations
      .foreach { e =>
        val a = e.body.asInstanceOf[Activation]
        val (namespace, action) = getNamespaceAction(a.name)

        Kamon.counter("openwhisk.counter.container.activations").refine(("namespace" -> namespace),("action" -> action),("kind" -> a.kind),("memory" -> a.memory.toString),("status" -> a.statusCode.toString)).increment()
        Kamon.histogram("openwhisk.histogram.container.memory").refine(("namespace" -> namespace),("action" -> action),("kind" -> a.kind)).record(a.memory)
        Kamon.histogram("openwhisk.histogram.container.waitTime", MeasurementUnit.time.milliseconds).refine(("namespace" -> namespace),("action" -> action),("kind" -> a.kind),("memory" -> a.memory.toString)).record(a.waitTime)
        Kamon.histogram("openwhisk.histogram.container.initTime", MeasurementUnit.time.milliseconds).refine(("namespace" -> namespace),("action" -> action),("kind" -> a.kind),("memory" -> a.memory.toString)).record(a.initTime)
        Kamon.histogram("openwhisk.histogram.container.duration", MeasurementUnit.time.milliseconds).refine(("namespace" -> namespace),("action" -> action),("kind" -> a.kind),("memory" -> a.memory.toString)).record(a.duration)
      }
  }

  /**
    * Extract namespace and action from name
    * ex. whisk.system/apimgmt/createApi -> (whisk.system, apimgmt/createApi)
    *
    * @param name
    * @return namespace, action
    */
  private def getNamespaceAction(name: String): (String, String) = {
    val nameArr = name.split("/", 2)
    return (nameArr(0), nameArr(1))
  }

}


