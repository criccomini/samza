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

package org.apache.samza.coordinator.stream;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.samza.Partition;
import org.apache.samza.SamzaException;
import org.apache.samza.config.Config;
import org.apache.samza.config.MapConfig;
import org.apache.samza.coordinator.stream.CoordinatorStreamMessage.Delete;
import org.apache.samza.coordinator.stream.CoordinatorStreamMessage.SetConfig;
import org.apache.samza.serializers.model.SamzaObjectMapper;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.SystemAdmin;
import org.apache.samza.system.SystemConsumer;
import org.apache.samza.system.SystemStream;
import org.apache.samza.system.SystemStreamMetadata;
import org.apache.samza.system.SystemStreamMetadata.SystemStreamPartitionMetadata;
import org.apache.samza.system.SystemStreamPartition;
import org.apache.samza.system.SystemStreamPartitionIterator;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

/**
 * A wrapper around a SystemConsumer that reads provides helpful methods for
 * dealing with the coordinator stream.
 */
public class CoordinatorStreamSystemConsumer {
  private final SystemStreamPartition coordinatorSystemStreamPartition;
  private final SystemConsumer systemConsumer;
  private final SystemAdmin systemAdmin;
  private final Map<String, String> configMap;
  private final ObjectMapper mapper;
  private boolean isBootstrapped;

  public CoordinatorStreamSystemConsumer(SystemStream coordinatorSystemStream, SystemConsumer systemConsumer, SystemAdmin systemAdmin, ObjectMapper mapper) {
    this.coordinatorSystemStreamPartition = new SystemStreamPartition(coordinatorSystemStream, new Partition(0));
    this.systemConsumer = systemConsumer;
    this.systemAdmin = systemAdmin;
    this.mapper = mapper;
    this.configMap = new HashMap<String, String>();
    this.isBootstrapped = false;
  }

  public CoordinatorStreamSystemConsumer(SystemStream coordinatorSystemStream, SystemConsumer systemConsumer, SystemAdmin systemAdmin) {
    this(coordinatorSystemStream, systemConsumer, systemAdmin, SamzaObjectMapper.getObjectMapper());
  }

  /**
   * Retrieves the earliest offset in the coordinator stream, and registers the
   * coordinator stream with the SystemConsumer using the earliest offset.
   */
  public void register() {
    Set<String> streamNames = new HashSet<String>();
    String streamName = coordinatorSystemStreamPartition.getStream();
    streamNames.add(streamName);
    Map<String, SystemStreamMetadata> systemStreamMetadataMap = systemAdmin.getSystemStreamMetadata(streamNames);
    SystemStreamMetadata systemStreamMetadata = systemStreamMetadataMap.get(streamName);

    if (systemStreamMetadata == null) {
      throw new SamzaException("Expected " + streamName + " to be in system stream metadata.");
    }

    SystemStreamPartitionMetadata systemStreamPartitionMetadata = systemStreamMetadata.getSystemStreamPartitionMetadata().get(coordinatorSystemStreamPartition.getPartition());

    if (systemStreamPartitionMetadata == null) {
      throw new SamzaException("Expected metadata for " + coordinatorSystemStreamPartition + " to exist.");
    }

    String startingOffset = systemStreamPartitionMetadata.getOldestOffset();
    systemConsumer.register(coordinatorSystemStreamPartition, startingOffset);
  }

  /**
   * Starts the underlying SystemConsumer.
   */
  public void start() {
    systemConsumer.start();
  }

  /**
   * Stops the underlying SystemConsumer.
   */
  public void stop() {
    systemConsumer.stop();
  }

  /**
   * Read all messages from the earliest offset, all the way to the latest.
   * Currently, this method only pays attention to config messages.
   */
  public void bootstrap() {
    SystemStreamPartitionIterator iterator = new SystemStreamPartitionIterator(systemConsumer, coordinatorSystemStreamPartition);

    try {
      while (iterator.hasNext()) {
        IncomingMessageEnvelope envelope = iterator.next();
        String keyStr = new String((byte[]) envelope.getKey(), "UTF-8");
        Map<String, Object> keyMap = mapper.readValue(keyStr, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> valueMap = null;
        if (envelope.getMessage() != null) {
          String valueStr = new String((byte[]) envelope.getMessage(), "UTF-8");
          valueMap = mapper.readValue(valueStr, new TypeReference<Map<String, Object>>() {
          });
        }
        CoordinatorStreamMessage coordinatorStreamMessage = new CoordinatorStreamMessage(keyMap, valueMap);
        if (SetConfig.TYPE.equals(coordinatorStreamMessage.getType())) {
          String configKey = coordinatorStreamMessage.getKey();
          if (coordinatorStreamMessage.isDelete()) {
            configMap.remove(configKey);
          } else {
            String configValue = new SetConfig(coordinatorStreamMessage).getConfigValue();
            configMap.put(configKey, configValue);
          }
        }
      }
      isBootstrapped = true;
    } catch (Exception e) {
      throw new SamzaException(e);
    }
  }

  /**
   * @return The bootstrapped configuration that's been read after bootstrap has
   *         been invoked.
   */
  public Config getConfig() {
    if (isBootstrapped) {
      return new MapConfig(configMap);
    } else {
      throw new SamzaException("Must call bootstrap before retrieving config.");
    }
  }
}
