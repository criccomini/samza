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

package org.apache.samza.system;

import java.util.Map;
import java.util.Set;

/**
 * Helper interface attached to an underlying system to fetch
 * information about streams, partitions, offsets, etc. This interface is useful
 * for providing utility methods that Samza needs in order to interact with a
 * system.
 */
public interface SystemAdmin {

  /**
   * Fetches the offsets for the messages immediately after the supplied offsets
   * for a group of SystemStreamPartitions.
   * 
   * @param offsets
   *          Map from SystemStreamPartition to current offsets.
   * @return Map from SystemStreamPartition to offsets immediately after the
   *         current offsets.
   */
  Map<SystemStreamPartition, String> getOffsetsAfter(Map<SystemStreamPartition, String> offsets);

  /**
   * Fetch metadata from a system for a set of streams.
   * 
   * @param streamNames
   *          The streams to to fetch metadata for.
   * @return A map from stream name to SystemStreamMetadata for each stream
   *         requested in the parameter set.
   */
  Map<String, SystemStreamMetadata> getSystemStreamMetadata(Set<String> streamNames);
}
