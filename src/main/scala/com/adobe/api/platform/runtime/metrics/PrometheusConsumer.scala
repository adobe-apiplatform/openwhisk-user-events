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
import java.io.StringWriter
import java.util
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.{HttpEntity, MessageEntity}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.adobe.api.platform.runtime.metrics.Activation.getNamespaceAndActionName
import com.adobe.api.platform.runtime.metrics.MetricNames._
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Histogram}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

object PrometheusConsumer extends MetricRecorder with PrometheusExporter {

  private val metrics = new TrieMap[String, PrometheusMetrics]
  private val activationCounter = counter(activationMetric, "Activation Count", "namespace", "action")
  private val coldStartCounter = counter(coldStartMetric, "Cold start counts", "namespace", "action")
  private val statusCounter = counter(statusMetric, "Activation failure status type", "namespace", "action", "status")
  private val waitTimeHisto = histogram(waitTimeMetric, "Internal system hold time", "namespace", "action")
  private val initTimeHisto =
    histogram(initTimeMetric, "Time it took to initialize an action, e.g. docker init", "namespace", "action")
  private val durationHisto =
    histogram(durationMetric, "Actual time the action code was running", "namespace", "action")

  private val metricSource = createSource()

  def processEvent(activation: Activation): Unit = {
    lookup(activation.name).record(activation)
  }

  override def getReport(): MessageEntity =
    HttpEntity(PrometheusExporter.textV4, metricSource)

  private def lookup(name: String): PrometheusMetrics = {
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

  private def counter(name: String, help: String, tags: String*) =
    Counter
      .build()
      .name(name)
      .help(help)
      .labelNames(tags: _*)
      .register()

  private def histogram(name: String, help: String, tags: String*) =
    Histogram
      .build()
      .name(name)
      .help(help)
      .labelNames(tags: _*)
      .register()

  /**
   * Enables streaming the prometheus metric data without building the whole report in memory
   */
  private def createSource() =
    Source
      .fromIterator(() => CollectorRegistry.defaultRegistry.metricFamilySamples().asScala)
      .map { sample =>
        //Stream string representation of one sample at a time
        val writer = new StringWriter()
        TextFormat.write004(writer, singletonEnumeration(sample))
        writer.toString
      }
      .map(ByteString(_))

  private def singletonEnumeration[A](value: A) = new util.Enumeration[A] {
    private var done = false
    override def hasMoreElements: Boolean = !done
    override def nextElement(): A = {
      if (done) throw new NoSuchElementException
      done = true
      value
    }
  }

}
