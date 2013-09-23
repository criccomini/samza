/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.metrics

import org.apache.samza.container.SamzaContainerMetrics

/**
 * MetricsHelper is a little helper class to make it easy to register and
 * manage counters and gauges.
 */
trait MetricsHelper {
  val group = this.getClass.getName
  val registry: MetricsRegistry

  def newCounter(name: String) = {
    registry.newCounter(group, (getPrefix + name).toLowerCase)
  }

  def newGauge[T](name: String, value: T) = {
    registry.newGauge(group, new Gauge((getPrefix + name).toLowerCase, value))
  }

  def newGauge[T](name: String, value: () => T) = {
    registry.newGauge(group, new Gauge((getPrefix + name).toLowerCase, value()) {
      override def getValue = value()
    })
  }

  /**
   * Returns a prefix for metric names.
   */
  def getPrefix = ""
}