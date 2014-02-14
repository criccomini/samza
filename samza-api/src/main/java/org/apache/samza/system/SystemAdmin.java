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

/*
 * ++1. Add SystemAdmin.getStreamMetadata().
 * ++3. Use stream metadata for bootstrap chooser.
 * 4. Convert SystemConsumer.register to nextOffset.
 * 5. Update KafkaSystemConsumer and BrokerProxy to use nextOffset.
 * 6. Default GetOffset to fail if offset out of range is found.
 * 7. Add systems.kafka.streams.foo.samza.offset.default=(largest|smallest) and systems.kafka.samza.offset.default.
 * 8. Update TaskInstance to use offset defaults when no checkpointed offset exists.
 * 2. Add SystemAdmin and SystemStreamPartitionMetadata docs.
 */

public interface SystemAdmin {
  Map<SystemStreamPartition, SystemStreamPartitionMetadata> getSystemStreamPartitionMetadata(Set<String> streamNames);
}
