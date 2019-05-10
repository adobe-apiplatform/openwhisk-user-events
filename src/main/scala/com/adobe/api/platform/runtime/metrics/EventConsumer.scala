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

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import kamon.Kamon

import scala.concurrent.Future

trait MetricRecorder {
  def processEvent(activation: Activation): Unit
}

case class EventConsumer(settings: ConsumerSettings[String, String], recorders: Seq[MetricRecorder])(
  implicit system: ActorSystem,
  materializer: ActorMaterializer) {
  import EventConsumer._

  //Record the rate of events received
  private val activationCounter = Kamon.counter("openwhisk.userevents.activations")
  private val metricCounter = Kamon.counter("openwhisk.userevents.metric")

  def shutdown(): Future[Done] = {
    control.drainAndShutdown()(system.dispatcher)
  }

  def isRunning: Boolean = !control.isShutdown.isCompleted

  //TODO Use RestartSource
  private val control: DrainingControl[Done] = Consumer
    .committableSource(settings, Subscriptions.topics(userEventTopic))
    .map { msg =>
      processEvent(msg.record.value())
      msg.committableOffset
    }
    .toMat(Committer.sink(CommitterSettings(system)))(Keep.both)
    .mapMaterializedValue(DrainingControl.apply)
    .run()

  private def processEvent(value: String): Unit = {
    EventMessage
      .parse(value)
      .map { e =>
        e.eventType match {
          case Activation.typeName => activationCounter.increment()
          case Metric.typeName     => metricCounter.increment()
        }
        e
      }
      .collect { case e if e.eventType == Activation.typeName => e } //Look for only Activations
      .foreach { e =>
        val a = e.body.asInstanceOf[Activation]
        recorders.foreach(_.processEvent(a))
      }
  }
}

object EventConsumer {
  val userEventTopic = "events"
}
