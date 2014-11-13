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

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.samza.coordinator.stream.CoordinatorStreamMessage.Delete;
import org.apache.samza.coordinator.stream.CoordinatorStreamMessage.SetConfig;
import org.apache.samza.serializers.model.SamzaObjectMapper;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

public class TestCoordinatorStreamMessage {
  @Test
  public void testKeyOrder() throws Exception {
    ObjectMapper mapper = SamzaObjectMapper.getObjectMapper();
    SortedMap<String, Object> sortedMap = new TreeMap<String, Object>();
    Map<String, Object> map = new HashMap<String, Object>();
    map.put("aaa", "AAA");
    map.put("bbb", "BBB");
    map.put("ccc", "CCC");
    map.put("ddd", "DDD");
    sortedMap.putAll(map);
    assertFalse(mapper.writeValueAsString(map).equals(mapper.writeValueAsString(sortedMap)));
    CoordinatorStreamMessage unsorted = new CoordinatorStreamMessage("", map, null);
    CoordinatorStreamMessage sorted = new CoordinatorStreamMessage("", sortedMap, null);
    assertTrue(mapper.writeValueAsString(unsorted.getKeyMap()).equals(mapper.writeValueAsString(sorted.getKeyMap())));
  }

  @Test
  public void testCoordinatorStreamMessage() {
    CoordinatorStreamMessage message = new CoordinatorStreamMessage("source");
    assertEquals("source", message.getSource());
    assertEquals(CoordinatorStreamMessage.VERSION, message.getVersion());
    assertNotNull(message.getUsername());
    assertTrue(message.getTimestamp() > 0);
    assertTrue(!message.isDelete());
    CoordinatorStreamMessage secondMessage = new CoordinatorStreamMessage(message.getKeyMap(), message.getMessageMap());
    assertEquals(secondMessage, message);
  }

  @Test
  public void testCoordinatorStreamMessageIsDelete() {
    CoordinatorStreamMessage message = new CoordinatorStreamMessage(new HashMap<String, Object>(), null);
    assertTrue(message.isDelete());
    assertNull(message.getMessageMap());
  }

  @Test
  public void testSetConfig() {
    SetConfig setConfig = new SetConfig("source", "key", "value");
    assertEquals(SetConfig.TYPE, setConfig.getType());
    assertEquals("key", setConfig.getKey());
    assertEquals("value", setConfig.getConfigValue());
    assertFalse(setConfig.isDelete());
    assertEquals(CoordinatorStreamMessage.VERSION, setConfig.getVersion());
  }

  @Test
  public void testDelete() {
    Delete delete = new Delete("source2", "key", "delete-type");
    assertEquals("delete-type", delete.getType());
    assertEquals("key", delete.getKey());
    assertNull(delete.getMessageMap());
    assertTrue(delete.isDelete());
    assertEquals(CoordinatorStreamMessage.VERSION, delete.getVersion());
  }
}
