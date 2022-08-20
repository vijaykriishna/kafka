/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.controller;

import java.util.Collections;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.common.errors.StaleBrokerEpochException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.metadata.ProducerIdsRecord;
import org.apache.kafka.common.metadata.RegisterBrokerRecord;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.common.MetadataVersion;
import org.apache.kafka.server.common.ProducerIdsBlock;
import org.apache.kafka.timeline.SnapshotRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ProducerIdControlManagerTest {

    private SnapshotRegistry snapshotRegistry;
    private FeatureControlManager featureControl;
    private ClusterControlManager clusterControl;
    private ProducerIdControlManager producerIdControlManager;

    @BeforeEach
    public void setUp() {
        final MockTime time = new MockTime();
        snapshotRegistry = new SnapshotRegistry(new LogContext());
        featureControl = new FeatureControlManager.Builder().
            setSnapshotRegistry(snapshotRegistry).
            setQuorumFeatures(new QuorumFeatures(0, new ApiVersions(),
                QuorumFeatures.defaultFeatureMap(),
                Collections.singletonList(0))).
            setMetadataVersion(MetadataVersion.latest()).
            build();
        clusterControl = new ClusterControlManager.Builder().
            setTime(time).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            setFeatureControlManager(featureControl).
            build();

        clusterControl.activate();
        for (int i = 0; i < 4; i++) {
            RegisterBrokerRecord brokerRecord = new RegisterBrokerRecord().setBrokerEpoch(100).setBrokerId(i);
            brokerRecord.endPoints().add(new RegisterBrokerRecord.BrokerEndpoint().
                    setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                    setPort((short) 9092).
                    setName("PLAINTEXT").
                    setHost(String.format("broker-%02d.example.org", i)));
            clusterControl.replay(brokerRecord, 100L);
        }

        this.producerIdControlManager = new ProducerIdControlManager(clusterControl, snapshotRegistry);
    }

    @Test
    public void testInitialResult() {
        ControllerResult<ProducerIdsBlock> result =
            producerIdControlManager.generateNextProducerId(1, 100);
        assertEquals(0, result.response().firstProducerId());
        assertEquals(1000, result.response().size());
        ProducerIdsRecord record = (ProducerIdsRecord) result.records().get(0).message();
        assertEquals(1000, record.nextProducerId());
    }

    @Test
    public void testMonotonic() {
        producerIdControlManager.replay(
            new ProducerIdsRecord()
                .setBrokerId(1)
                .setBrokerEpoch(100)
                .setNextProducerId(42));

        ProducerIdsBlock range =
            producerIdControlManager.generateNextProducerId(1, 100).response();
        assertEquals(42, range.firstProducerId());

        // Can't go backwards in Producer IDs
        assertThrows(RuntimeException.class, () -> {
            producerIdControlManager.replay(
                new ProducerIdsRecord()
                    .setBrokerId(1)
                    .setBrokerEpoch(100)
                    .setNextProducerId(40));
        }, "Producer ID range must only increase");
        range = producerIdControlManager.generateNextProducerId(1, 100).response();
        assertEquals(42, range.firstProducerId());

        // Gaps in the ID range are okay.
        producerIdControlManager.replay(
            new ProducerIdsRecord()
                .setBrokerId(1)
                .setBrokerEpoch(100)
                .setNextProducerId(50));
        range = producerIdControlManager.generateNextProducerId(1, 100).response();
        assertEquals(50, range.firstProducerId());
    }

    @Test
    public void testUnknownBrokerOrEpoch() {
        ControllerResult<ProducerIdsBlock> result;

        assertThrows(StaleBrokerEpochException.class, () ->
            producerIdControlManager.generateNextProducerId(99, 0));

        assertThrows(StaleBrokerEpochException.class, () ->
            producerIdControlManager.generateNextProducerId(1, 99));
    }

    @Test
    public void testMaxValue() {
        producerIdControlManager.replay(
            new ProducerIdsRecord()
                .setBrokerId(1)
                .setBrokerEpoch(100)
                .setNextProducerId(Long.MAX_VALUE - 1));

        assertThrows(UnknownServerException.class, () ->
            producerIdControlManager.generateNextProducerId(1, 100));
    }

    @Test
    public void testSnapshotIterator() {
        ProducerIdsBlock range = null;
        for (int i = 0; i < 100; i++) {
            range = generateProducerIds(producerIdControlManager, i % 4, 100);
        }

        Iterator<List<ApiMessageAndVersion>> snapshotIterator = producerIdControlManager.iterator(Long.MAX_VALUE);
        assertTrue(snapshotIterator.hasNext());
        List<ApiMessageAndVersion> batch = snapshotIterator.next();
        assertEquals(1, batch.size(), "Producer IDs record batch should only contain a single record");
        assertEquals(range.firstProducerId() + range.size(), ((ProducerIdsRecord) batch.get(0).message()).nextProducerId());
        assertFalse(snapshotIterator.hasNext(), "Producer IDs iterator should only contain a single batch");

        ProducerIdControlManager newProducerIdManager = new ProducerIdControlManager(clusterControl, snapshotRegistry);
        snapshotIterator = producerIdControlManager.iterator(Long.MAX_VALUE);
        while (snapshotIterator.hasNext()) {
            snapshotIterator.next().forEach(message -> newProducerIdManager.replay((ProducerIdsRecord) message.message()));
        }

        // Verify that after reloading state from this "snapshot", we don't produce any overlapping IDs
        long lastProducerID = range.firstProducerId() + range.size() - 1;
        range = generateProducerIds(producerIdControlManager, 1, 100);
        assertTrue(range.firstProducerId() > lastProducerID);
    }

    static ProducerIdsBlock generateProducerIds(
            ProducerIdControlManager producerIdControlManager, int brokerId, long brokerEpoch) {
        ControllerResult<ProducerIdsBlock> result =
            producerIdControlManager.generateNextProducerId(brokerId, brokerEpoch);
        result.records().forEach(apiMessageAndVersion ->
            producerIdControlManager.replay((ProducerIdsRecord) apiMessageAndVersion.message()));
        return result.response();
    }
}
