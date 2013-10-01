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

package org.apache.samza.system.chooser

import scala.collection.JavaConversions._
import org.apache.samza.SamzaException
import org.apache.samza.config.Config
import org.apache.samza.config.SystemConfig._
import org.apache.samza.config.WrappedChooserConfig._
import org.apache.samza.config.TaskConfig._
import org.apache.samza.system.IncomingMessageEnvelope
import org.apache.samza.system.SystemFactory
import org.apache.samza.system.SystemStream
import org.apache.samza.system.SystemStreamPartition
import org.apache.samza.util.Util
import org.apache.samza.system.SystemAdmin

object WrappedChooser {
  def apply(systemAdmins: Map[String, SystemAdmin], chooserFactory: MessageChooserFactory, config: Config) = {
    val batchSize = config.getChooserBatchSize match {
      case Some(batchSize) => Some(batchSize.toInt)
      case _ => None
    }

    // Normal streams default to priority 0.
    val defaultPrioritizedStreams = config
      .getInputStreams
      .map((_, 0))
      .toMap

    // Bootstrap streams default to Int.MaxValue priority.
    val prioritizedBootstrapStreams = config
      .getBootstrapStreams
      .map((_, Int.MaxValue))
      .toMap

    // Explicitly prioritized streams are set to whatever they were configured to.
    val prioritizedStreams = config.getPriorityStreams

    // Only wire in what we need.
    val useBootstrapping = prioritizedBootstrapStreams.size > 0
    val usePriority = useBootstrapping || prioritizedStreams.size > 0

    // Build a map from SSP -> lastReadOffset for each bootstrap stream.
    val latestMessageOffsets = prioritizedBootstrapStreams
      .keySet
      // Group streams by system (system -> [streams])
      .groupBy(_.getSystem())
      // Get the SystemAdmin for each system, and get all lastReadOffsets for 
      // each stream. Flatten into a simple SSP -> lastReadOffset map.
      .flatMap {
        case (systemName, streams) =>
          systemAdmins
            .getOrElse(systemName, throw new SamzaException("Trying to fetch system factory for system %s, which isn't defined in config." format systemName))
            .getLastOffsets(streams.map(_.getStream))
      }

    val priorities = if (usePriority) {
      // Ordering is important here. Overrides Int.MaxValue default for 
      // bootstrap streams with explicitly configured values, in cases where 
      // users have defined a bootstrap stream's priority in config.
      defaultPrioritizedStreams ++ prioritizedBootstrapStreams ++ prioritizedStreams
    } else {
      Map[SystemStream, Int]()
    }

    val prioritizedChoosers = priorities
      .values
      .toSet
      .map((_: Int, chooserFactory.getChooser(config)))
      .toMap

    new WrappedChooser(
      chooserFactory.getChooser(config),
      batchSize,
      priorities,
      prioritizedChoosers,
      latestMessageOffsets)
  }
}

/**
 * WrappedChooser adds additional functionality to an existing MessageChooser.
 *
 * The following behaviors are currently supported:
 *
 * 1. Batching.
 * 2. Prioritized streams.
 * 3. Bootstrapping.
 *
 * By default, this chooser will not do any of this. It will simply default to
 * a RoundRobinChooser.
 *
 * To activate batching, you define must define:
 *
 *   task.chooser.batch.size
 *
 * To define a priority for a stream, you must define:
 *
 *   task.chooser.prioriites.<system>.<stream>
 *
 * To declare a bootstrap stream, you must define:
 *
 *   task.chooser.bootstrap.<system>.<stream>
 *
 * When batching is activated, the WrappedChooserFactory will allow the
 * initial strategy to be executed once (by default, this is RoundRobin). It
 * will then keep picking the SystemStreamPartition that the RoundRobin
 * chooser selected, up to the batch size, provided that there are messages
 * available for this SystemStreamPartition. If the batch size is reached, or
 * there are no messages available, the RoundRobinChooser will be executed
 * again, and the batching process will repeat itself.
 *
 * When a stream is defined with a priority, it is preferred over all lower
 * priority streams in cases where there are messages available from both
 * streams. If two envelopes exist for two SystemStreamPartitions that have
 * the same priority, the default strategy is used to determine which envelope
 * to use (RoundRobinChooser, by default). If a stream doesn't have a
 * configured priority, its priority is 0. Higher priority streams are
 * preferred over lower priority streams.
 *
 * When a stream is defined as a bootstrap stream, it is prioritized with a
 * default priority of Int.MaxValue. This priority can be overridden using the
 * same priority configuration defined above (task.chooser.priorites.*). The
 * WrappedChooserFactory guarantees that the wrapped MessageChooser will have
 * at least one envelope from each bootstrap stream whenever the wrapped
 * MessageChooser must make a decision about which envelope to process next.
 * If a stream is defined as a bootstrap stream, and is prioritized higher
 * than all other streams, it means that all messages in the stream will be
 * processed (up to head) before any other messages are processed. Once all of
 * a bootstrap stream's partitions catch up to head, the stream is marked as
 * fully bootstrapped, and it is then treated like a normal prioritized stream.
 *
 * Valid configurations include:
 *
 *   task.chooser.batch.size=100
 *
 * This configuration will just batch up to 100 messages from each
 * SystemStreamPartition. It will use a RoundRobinChooser whenever it needs to
 * find the next SystemStreamPartition to batch.
 *
 *   task.chooser.prioriites.kafka.mystream=1
 *
 * This configuration will prefer messages from kafka.mystream over any other
 * input streams (since other input streams will default to priority 0).
 *
 *   task.chooser.bootstrap.kafka.profiles=true
 *
 * This configuration will process all messages from kafka.profiles up to the
 * current head of the profiles stream before any other messages are processed.
 * From then on, the profiles stream will be preferred over any other stream in
 * cases where incoming envelopes are ready to be processed from it.
 *
 *   task.chooser.batch.size=100
 *   task.chooser.bootstrap.kafka.profiles=true
 *   task.chooser.prioriites.kafka.mystream=1
 *
 * This configuration will read all messages from kafka.profiles from the last
 * checkpointed offset, up to head. It will then prefer messages from profiles
 * over mystream, and prefer messages from mystream over any other stream. In
 * cases where there is more than one envelope available with the same priority
 * (e.g. two envelopes from different partitions in the profiles stream),
 * RoundRobinChooser will be used to break the tie. Once the tie is broken, up
 * to 100 messages will be read from the envelope's SystemStreamPartition,
 * before RoundRobinChooser is consulted again to break the next tie.
 *
 *   task.chooser.bootstrap.kafka.profiles=true
 *   systems.kafka.streams.profiles.samza.reset.offset=true
 *
 * This configuration will bootstrap the profiles stream the same way as the
 * last example, except that it will always start from offset zero, which means
 * that it will always read all messages in the topic from oldest to newest.
 */
class WrappedChooser(
  /**
   * The wrapped chooser serves two purposes. In cases where bootstrapping or
   * prioritization is enabled, wrapped chooser serves as the default for
   * envelopes that have no priority defined.
   *
   * When prioritization and bootstrapping are not enabled, but batching is,
   * wrapped chooser is used as the strategy to determine which
   * SystemStreamPartition to batch next.
   *
   * When nothing is enabled, WrappedChooser just acts as a pass through for
   * the wrapped chooser.
   */
  wrappedChooser: MessageChooser = new RoundRobinChooser,

  /**
   * If defined, enables batching, and defines a max message size for a given
   * batch. Once the batch size is exceeded (at least batchSize messages have
   * been processed from a single system stream), the wrapped chooser is used
   * to determine the next system stream to process.
   */
  batchSize: Option[Int] = None,

  /**
   * Defines a mapping from SystemStream to a priority tier. Envelopes from
   * higher priority SystemStreams are processed before envelopes from lower
   * priority SystemStreams.
   *
   * If multiple envelopes exist within a single tier, the prioritized chooser
   * (defined below) is used to break the tie.
   *
   * If this map is empty, prioritization will not happen.
   */
  prioritizedStreams: Map[SystemStream, Int] = Map(),

  /**
   * Defines the tie breaking strategy to be used at each tier of the priority
   * chooser. This chooser is used to break the tie when more than one envelope
   * exists with the same priority.
   */
  prioritizedChoosers: Map[Int, MessageChooser] = Map(),

  /**
   * Defines a mapping from SystemStreamPartition to the offset of the last
   * message in each SSP. Bootstrap streams are marked as "behind" until all
   * SSPs for the SystemStream have been read. Once the bootstrap stream has
   * been "caught up" it is removed from the bootstrap set, and treated as a
   * normal stream.
   *
   * If this map is empty, no streams will be treated as bootstrap streams.
   *
   * Using bootstrap streams automatically enables stream prioritization.
   * Bootstrap streams default to a priority of Int.MaxValue.
   */
  bootstrapStreamOffsets: Map[SystemStreamPartition, String] = Map()) extends MessageChooser {

  val chooser = {
    val useBatching = batchSize.isDefined
    val useBootstrapping = bootstrapStreamOffsets.size > 0
    val usePriority = useBootstrapping || prioritizedStreams.size > 0
    val maybePrioritized = if (usePriority) {
      new TieredPriorityChooser(prioritizedStreams, prioritizedChoosers, wrappedChooser)
    } else if (wrappedChooser == null) {
      // Null wrapped chooser without a priority chooser is not allowed 
      // because WrappedChooser needs an underlying message chooser.
      throw new SamzaException("A null chooser was given to the WrappedChooser. This is not allowed unless you are using prioritized/bootstrap streams, which you're not.")
    } else {
      wrappedChooser
    }

    val maybeBatching = if (useBatching) {
      new BatchingChooser(maybePrioritized, batchSize.get)
    } else {
      maybePrioritized
    }

    if (useBootstrapping) {
      new BootstrappingChooser(maybeBatching, bootstrapStreamOffsets)
    } else {
      maybeBatching
    }
  }

  def update(envelope: IncomingMessageEnvelope) {
    chooser.update(envelope)
  }

  def choose = chooser.choose

  def start = chooser.start

  def stop = chooser.stop

  def register(systemStreamPartition: SystemStreamPartition, lastReadOffset: String) = chooser.register(systemStreamPartition, lastReadOffset)
}