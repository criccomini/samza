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

import org.junit.Assert._
import org.junit.Test
import org.apache.samza.system.IncomingMessageEnvelope
import scala.collection.immutable.Queue
import org.apache.samza.system.SystemStreamPartition
import org.apache.samza.Partition

class TestBootstrappingChooser {
  val envelope1 = new IncomingMessageEnvelope(new SystemStreamPartition("kafka", "stream", new Partition(0)), null, null, 1);
  val envelope2 = new IncomingMessageEnvelope(new SystemStreamPartition("kafka", "stream1", new Partition(1)), null, null, 2);
  val envelope3 = new IncomingMessageEnvelope(new SystemStreamPartition("kafka", "stream1", new Partition(0)), null, null, 3);
  val envelope4 = new IncomingMessageEnvelope(new SystemStreamPartition("kafka", "stream", new Partition(0)), "123", null, 4);

  @Test
  def testChooserShouldIgnoreStreamsThatArentInOffsetMap {
    val mock = new MockMessageChooser
    val chooser = new BootstrappingChooser(mock)

    chooser.register(envelope1.getSystemStreamPartition, null)
    chooser.update(envelope1)
    assertEquals(envelope1, chooser.choose)
    assertEquals(null, chooser.choose)
  }

  @Test
  def testChooserShouldEliminateCaughtUpStreamsOnRegister {
    val mock = new MockMessageChooser
    val chooser = new BootstrappingChooser(mock, Map(envelope1.getSystemStreamPartition -> "123"))

    // Even though envelope1's SSP is registered as a bootstrap stream, since 
    // 123=123, it should be marked as "caught up" and treated like a normal 
    // stream. This means that non-bootstrap stream envelope should be allowed 
    // to be chosen.
    chooser.register(envelope1.getSystemStreamPartition, "123")
    chooser.register(envelope2.getSystemStreamPartition, "321")
    chooser.update(envelope2)
    assertEquals(envelope2, chooser.choose)
    assertEquals(null, chooser.choose)
  }

  @Test
  def testChooserShouldEliminateCaughtUpStreamsAfterRegister {
    val mock = new MockMessageChooser
    val chooser = new BootstrappingChooser(mock, Map(envelope1.getSystemStreamPartition -> "123"))

    // Even though envelope1's SSP is registered as a bootstrap stream, since 
    // 123=123, it should be marked as "caught up" and treated like a normal 
    // stream. This means that non-bootstrap stream envelope should be allowed 
    // to be chosen.
    chooser.register(envelope1.getSystemStreamPartition, "1")
    chooser.register(envelope2.getSystemStreamPartition, null)
    chooser.update(envelope2)
    // Choose should not return anything since bootstrapper is blocking 
    // wrapped.choose until it gets an update from envelope1's SSP.
    assertEquals(null, chooser.choose)
    chooser.update(envelope1)
    // Now that we have an update from the required SSP, the mock chooser 
    // should be called, and return.
    assertEquals(envelope2, chooser.choose)
    // The chooser still has an envelope from envelope1's SSP, so it should 
    // return.
    assertEquals(envelope1, chooser.choose)
    // No envelope for envelope1's SSP has been given, so it should block.
    chooser.update(envelope2)
    assertEquals(null, chooser.choose)
    // Now we're giving an envelope with the proper last offset (123), so no
    // envelope1's SSP should be treated no differently than envelope2's.
    chooser.update(envelope4)
    assertEquals(envelope2, chooser.choose)
    assertEquals(envelope4, chooser.choose)
    assertEquals(null, chooser.choose)
    // Should not block here since there are no more lagging bootstrap streams.
    chooser.update(envelope2)
    assertEquals(envelope2, chooser.choose)
    assertEquals(null, chooser.choose)
    chooser.update(envelope2)
    assertEquals(envelope2, chooser.choose)
    assertEquals(null, chooser.choose)
  }

  @Test
  def testChooserShouldWorkWithTwoBootstrapStreams {
    val mock = new MockMessageChooser
    val chooser = new BootstrappingChooser(mock, Map(
      envelope1.getSystemStreamPartition -> "123",
      envelope2.getSystemStreamPartition -> "321"))

    chooser.register(envelope1.getSystemStreamPartition, "1")
    chooser.register(envelope2.getSystemStreamPartition, "1")
    chooser.register(envelope3.getSystemStreamPartition, "1")
    chooser.update(envelope1)
    assertEquals(null, chooser.choose)
    chooser.update(envelope3)
    assertEquals(null, chooser.choose)
    chooser.update(envelope2)

    // Fully loaded now.
    assertEquals(envelope1, chooser.choose)
    // Can't pick again because envelope1's SSP is missing.
    assertEquals(null, chooser.choose)
    chooser.update(envelope1)
    // Can pick again.
    assertEquals(envelope3, chooser.choose)
    // Can still pick since envelope3.SSP isn't being tracked.
    assertEquals(envelope2, chooser.choose)
    // Can't pick since envelope2.SSP needs an envelope now.
    assertEquals(null, chooser.choose)
    chooser.update(envelope2)
    // Now we get envelope1 again.
    assertEquals(envelope1, chooser.choose)
    // Can't pick again.
    assertEquals(null, chooser.choose)
    // Now use envelope4, to trigger "all caught up" for envelope1.SSP.
    chooser.update(envelope4)
    // Chooser's contents is currently: e2, e4 (System.err.println(mock.getEnvelopes))
    // Add envelope3, whose SSP isn't being tracked.
    chooser.update(envelope3)
    assertEquals(envelope2, chooser.choose)
    assertEquals(null, chooser.choose)
    chooser.update(envelope2)
    // Chooser's contents is currently: e4, e3, e2 (System.err.println(mock.getEnvelopes))
    assertEquals(envelope4, chooser.choose)
    // This should be allowed, even though no message from envelope1.SSP is 
    // available, since envelope4 triggered "all caught up" because its offset 
    // matches the offset map for this SSP, and we still have an envelope for 
    // envelope2.SSP in the queue.
    assertEquals(envelope3, chooser.choose)
    assertEquals(envelope2, chooser.choose)
    assertEquals(null, chooser.choose)
    // Fin.
  }
}