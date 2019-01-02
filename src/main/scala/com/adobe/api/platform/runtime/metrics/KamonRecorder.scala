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

import com.adobe.api.platform.runtime.metrics.Activation.getNamespaceAndActionName
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.collection.concurrent.TrieMap

trait KamonMetricNames extends MetricNames {
  val activationMetric = "openwhisk.action.activations"
  val coldStartMetric = "openwhisk.action.coldStarts"
  val waitTimeMetric = "openwhisk.action.waitTime"
  val initTimeMetric = "openwhisk.action.initTime"
  val durationMetric = "openwhisk.action.duration"
  val statusMetric = "openwhisk.action.status"
}

object KamonRecorder extends MetricRecorder with KamonMetricNames {
  private val metrics = new TrieMap[String, KamonMetrics]

  def processEvent(activation: Activation): Unit = {
    lookup(activation.name).record(activation)
  }

  def lookup(name: String): KamonMetrics = {
    metrics.getOrElseUpdate(name, {
      val (namespace, action) = getNamespaceAndActionName(name)
      KamonMetrics(namespace, action)
    })
  }

  case class KamonMetrics(namespace: String, action: String) {
    private val tags = Map(`actionNamespace` -> namespace, `actionName` -> action)

    private val activations = Kamon.counter(activationMetric).refine(tags)
    private val coldStarts = Kamon.counter(coldStartMetric).refine(tags)
    private val waitTime = Kamon.histogram(waitTimeMetric, MeasurementUnit.time.milliseconds).refine(tags)
    private val initTime = Kamon.histogram(initTimeMetric, MeasurementUnit.time.milliseconds).refine(tags)
    private val duration = Kamon.histogram(durationMetric, MeasurementUnit.time.milliseconds).refine(tags)

    def record(a: Activation): Unit = {
      activations.increment()

      if (a.initTime > 0) {
        coldStarts.increment()
        initTime.record(a.initTime)
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.record(a.waitTime)
      duration.record(a.duration)

      if (a.statusCode != 0) {
        Kamon.counter(statusMetric).refine(tags + ("status" -> a.status)).increment()
      }
    }
  }
}
