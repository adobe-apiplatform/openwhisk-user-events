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
import java.util.concurrent.TimeUnit

import com.adobe.api.platform.runtime.metrics.Activation.getNamespaceAndActionName
import com.adobe.api.platform.runtime.metrics.MetricNames._
import io.prometheus.client.{Counter, Histogram}

import scala.collection.concurrent.TrieMap

object PrometheusConsumer extends MetricRecorder {

  private val metrics = new TrieMap[String, PrometheusMetrics]
  private val activationCounter = counter(activationMetric, "namespace", "action")
  private val coldStartCounter = counter(coldStartMetric, "namespace", "action")
  private val statusCounter = counter(statusMetric, "namespace", "action", "status")
  private val waitTimeHisto = histogram(waitTimeMetric, "namespace", "action")
  private val initTimeHisto = histogram(initTimeMetric, "namespace", "action")
  private val durationHisto = histogram(durationMetric, "namespace", "action")

  def processEvent(activation: Activation): Unit = {
    lookup(activation.name).record(activation)
  }

  def lookup(name: String): PrometheusMetrics = {
    //TODO Unregister unused actions
    metrics.getOrElseUpdate(name, {
      val (namespace, action) = getNamespaceAndActionName(name)
      PrometheusMetrics(namespace, action)
    })
  }

  case class PrometheusMetrics(namespace: String, action: String) {
    private val activations = activationCounter.labels(namespace, action)
    private val coldStarts = coldStartCounter.labels(namespace, action)
    private val waitTime = waitTimeHisto.labels(namespace, action)
    private val initTime = initTimeHisto.labels(namespace, action)
    private val duration = durationHisto.labels(namespace, action)

    def record(a: Activation): Unit = {
      activations.inc()

      if (a.initTime > 0) {
        coldStarts.inc()
        initTime.observe(seconds(a.initTime))
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.observe(seconds(a.waitTime))
      duration.observe(seconds(a.duration))

      if (a.statusCode != 0) {
        statusCounter.labels(namespace, action, a.status).inc()
      }
    }
  }

  private def seconds(timeInMillis: Long) = TimeUnit.MILLISECONDS.toSeconds(timeInMillis)

  private def counter(name: String, tags: String*) =
    Counter
      .build()
      .name(name)
      .labelNames(tags: _*)
      .register()

  private def histogram(name: String, tags: String*) =
    Histogram
      .build()
      .name(name)
      .labelNames(tags: _*)
      .register()
}
