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
package org.apache.kafka.raft;

import org.apache.kafka.common.errors.NotLeaderForPartitionException;
import org.apache.kafka.common.message.BeginQuorumEpochRequestData;
import org.apache.kafka.common.message.EndQuorumEpochRequestData;
import org.apache.kafka.common.message.FetchQuorumRecordsRequestData;
import org.apache.kafka.common.message.FetchQuorumRecordsResponseData;
import org.apache.kafka.common.message.FindQuorumRequestData;
import org.apache.kafka.common.message.FindQuorumResponseData;
import org.apache.kafka.common.message.LeaderChangeMessage;
import org.apache.kafka.common.message.LeaderChangeMessage.Voter;
import org.apache.kafka.common.message.VoteRequestData;
import org.apache.kafka.common.message.VoteResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.ControlRecordType;
import org.apache.kafka.common.record.ControlRecordUtils;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KafkaRaftClientTest {
    private final int localId = 0;
    private final int electionTimeoutMs = 10000;
    private final int retryBackoffMs = 50;
    private final int requestTimeoutMs = 5000;
    private final int electionJitterMs = 100;
    private final MockTime time = new MockTime();
    private final MockLog log = new MockLog();
    private final MockNetworkChannel channel = new MockNetworkChannel();
    private final Random random = Mockito.spy(new Random());

    private QuorumStateStore quorumStateStore = new MockQuorumStateStore();

    @After
    public void cleanUp() throws IOException {
        quorumStateStore.clear();
    }

    private InetSocketAddress mockAddress(int id) {
        return new InetSocketAddress("localhost", 9990 + id);
    }

    private KafkaRaftClient buildClient(Set<Integer> voters) throws IOException {
        LogContext logContext = new LogContext();
        QuorumState quorum = new QuorumState(localId, voters, quorumStateStore, logContext);

        List<InetSocketAddress> bootstrapServers = voters.stream()
            .map(this::mockAddress)
            .collect(Collectors.toList());

        KafkaRaftClient client = new KafkaRaftClient(channel, log, quorum, time,
            mockAddress(localId), bootstrapServers, electionTimeoutMs, electionJitterMs,
            retryBackoffMs, requestTimeoutMs, logContext, random);
        client.initialize(new NoOpStateMachine());
        return client;
    }

    @Test
    public void testInitializeSingleMemberQuorum() throws IOException {
        KafkaRaftClient client = buildClient(Collections.singleton(localId));
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());
        client.poll();
        assertEquals(0, channel.drainSendQueue().size());
    }

    @Test
    public void testInitializeAsCandidateAndBecomeLeader() throws Exception {
        long now = time.milliseconds();
        final int otherNodeId = 1;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withVotedCandidate(1, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        pollUntilSend(client);

        int correlationId = assertSentVoteRequest(1, 0, 0L);
        VoteResponseData voteResponse = voteResponse(true, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(correlationId, voteResponse, otherNodeId));

        // Become leader after receiving the vote
        client.poll();
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());

        // Leader change record appended
        assertEquals(1, log.endOffset());

        // Send BeginQuorumEpoch to voters
        client.poll();
        assertBeginQuorumEpochRequest(1);

        Records records = log.read(0, OptionalLong.of(1));
        RecordBatch batch = records.batches().iterator().next();
        assertTrue(batch.isControlBatch());

        Record record = batch.iterator().next();
        assertEquals(now, record.timestamp());
        verifyLeaderChangeMessage(localId, Collections.singletonList(otherNodeId),
            record.key(), record.value());
    }

    @Test
    public void testVoteRequestTimeout() throws Exception {
        int epoch = 1;
        int otherNodeId = 1;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withVotedCandidate(epoch, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(localId, epoch, voters), -1));

        pollUntilSend(client);

        int correlationId = assertSentVoteRequest(epoch, 0, 0L);

        time.sleep(requestTimeoutMs);
        client.poll();
        int retryId = assertSentVoteRequest(epoch, 0, 0L);

        // Even though we have resent the request, we should still accept the response to
        // the first request if it arrives late.
        VoteResponseData voteResponse = voteResponse(true, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(correlationId, voteResponse, otherNodeId));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, localId), quorumStateStore.readElectionState());

        // If the second request arrives later, it should have no effect
        VoteResponseData retryResponse = voteResponse(true, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(retryId, retryResponse, otherNodeId));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, localId), quorumStateStore.readElectionState());
    }

    @Test
    public void testRetryElection() throws Exception {
        int otherNodeId = 1;
        int epoch = 1;

        int jitterMs = 85;
        Mockito.doReturn(jitterMs).when(random).nextInt(Mockito.anyInt());

        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withVotedCandidate(epoch, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, epoch, voters), -1));

        pollUntilSend(client);

        // Quorum size is two. If the other member rejects, then we need to schedule a revote.
        int correlationId = assertSentVoteRequest(epoch, 0, 0L);
        VoteResponseData voteResponse = voteResponse(false, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(correlationId, voteResponse, otherNodeId));

        client.poll();

        // All nodes have rejected our candidacy, but we should still remember that we had voted
        ElectionState latest = quorumStateStore.readElectionState();
        assertEquals(epoch, latest.epoch);
        assertTrue(latest.hasVoted());
        assertEquals(localId, latest.votedId());

        // Even though our candidacy was rejected, we need to await the expiration of the election
        // timeout (plus jitter) before we bump the epoch and start a new election.
        time.sleep(electionTimeoutMs + jitterMs - 1);
        client.poll();
        assertEquals(epoch, quorumStateStore.readElectionState().epoch);

        // After jitter expires, we become a candidate again
        time.sleep(1);
        client.poll();
        assertEquals(ElectionState.withVotedCandidate(epoch + 1, localId), quorumStateStore.readElectionState());
        assertSentVoteRequest(epoch + 1, 0, 0L);
    }

    @Test
    public void testInitializeAsFollowerEmptyLog() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(otherNodeId, epoch, voters), -1));

        pollUntilSend(client);

        assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);
    }

    @Test
    public void testInitializeAsFollowerNonEmptyLog() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        int lastEpoch = 3;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        log.appendAsLeader(Collections.singleton(new SimpleRecord("foo".getBytes())), lastEpoch);

        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(otherNodeId, epoch, voters), -1));

        pollUntilSend(client);

        assertSentFetchQuorumRecordsRequest(epoch, 1L, lastEpoch);
    }

    @Test
    public void testBecomeCandidateAfterElectionTimeout() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        int lastEpoch = 3;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        log.appendAsLeader(Collections.singleton(new SimpleRecord("foo".getBytes())), lastEpoch);

        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(otherNodeId, epoch, voters), -1));

        pollUntilSend(client);

        assertSentFetchQuorumRecordsRequest(epoch, 1L, lastEpoch);

        time.sleep(electionTimeoutMs);

        client.poll();
        assertSentVoteRequest(epoch + 1, lastEpoch, 1L);
    }

    @Test
    public void testInitializeObserverNoPreviousState() throws IOException {
        int leaderId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(leaderId);
        KafkaRaftClient client = buildClient(voters);

        client.poll();
        int correlationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(correlationId,
            findQuorumResponse(leaderId, epoch, voters), -1));

        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, leaderId), quorumStateStore.readElectionState());
    }

    @Test
    public void testObserverFindQuorumFailure() throws IOException {
        int leaderId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(leaderId);
        KafkaRaftClient client = buildClient(voters);

        client.poll();
        int correlationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(correlationId, findQuorumFailure(Errors.UNKNOWN_SERVER_ERROR), -1));

        client.poll();
        assertEquals(0, channel.drainSendQueue().size());

        time.sleep(retryBackoffMs);

        client.poll();
        int retryId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(retryId,
            findQuorumResponse(leaderId, epoch, voters), -1));

        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, leaderId), quorumStateStore.readElectionState());
    }

    @Test
    public void testObserverFindQuorumAfterElectionTimeout() throws IOException {
        int leaderId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(leaderId);
        KafkaRaftClient client = buildClient(voters);

        client.poll();
        int correlationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(correlationId,
            findQuorumResponse(leaderId, epoch, voters), -1));

        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, leaderId), quorumStateStore.readElectionState());

        time.sleep(electionTimeoutMs);

        client.poll();
        assertSentFindQuorumRequest();
    }

    @Test
    public void testFetchResponseIgnoredAfterBecomingCandidate() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;

        // The other node starts out as the leader
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        pollUntilSend(client);
        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        // Wait until we have a Fetch inflight to the leader
        pollUntilSend(client);
        int fetchCorrelationId = assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);

        // Now await the election timeout and become a candidate
        time.sleep(electionTimeoutMs);
        client.poll();
        assertEquals(ElectionState.withVotedCandidate(epoch + 1, localId), quorumStateStore.readElectionState());

        // The fetch response from the old leader returns, but it should be ignored
        Records records = MemoryRecords.withRecords(0L, CompressionType.NONE,
            3, new SimpleRecord("a".getBytes()), new SimpleRecord("b".getBytes()));
        FetchQuorumRecordsResponseData response = fetchRecordsResponse(epoch, otherNodeId, records, 0L, Errors.NONE);
        channel.mockReceive(new RaftResponse.Inbound(fetchCorrelationId, response, otherNodeId));

        client.poll();
        assertEquals(0, log.endOffset());
        assertEquals(ElectionState.withVotedCandidate(epoch + 1, localId), quorumStateStore.readElectionState());
    }

    @Test
    public void testFetchResponseIgnoredAfterBecomingFollowerOfDifferentLeader() throws Exception {
        int voter1 = localId;
        int voter2 = localId + 1;
        int voter3 = localId + 2;
        int epoch = 5;

        // Start out with `voter2` as the leader
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, voter2));
        Set<Integer> voters = Utils.mkSet(voter1, voter2, voter3);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, voter2), quorumStateStore.readElectionState());

        pollUntilSend(client);
        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        // Wait until we have a Fetch inflight to the leader
        pollUntilSend(client);
        int fetchCorrelationId = assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);

        // Now receive a BeginEpoch from `voter3`
        BeginQuorumEpochRequestData beginEpochRequest = beginEpochRequest(epoch + 1, voter3);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), beginEpochRequest, time.milliseconds()));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch + 1, voter3), quorumStateStore.readElectionState());

        // The fetch response from the old leader returns, but it should be ignored
        Records records = MemoryRecords.withRecords(0L, CompressionType.NONE,
            3, new SimpleRecord("a".getBytes()), new SimpleRecord("b".getBytes()));
        FetchQuorumRecordsResponseData response = fetchRecordsResponse(epoch, voter2, records, 0L, Errors.NONE);
        channel.mockReceive(new RaftResponse.Inbound(fetchCorrelationId, response, voter2));

        client.poll();
        assertEquals(0, log.endOffset());
        assertEquals(ElectionState.withElectedLeader(epoch + 1, voter3), quorumStateStore.readElectionState());
    }

    @Test
    public void testVoteResponseIgnoredAfterBecomingFollower() throws Exception {
        int voter1 = localId;
        int voter2 = localId + 1;
        int voter3 = localId + 2;
        int epoch = 5;

        // This node initializes as a candidate
        quorumStateStore.writeElectionState(ElectionState.withVotedCandidate(epoch, voter1));
        Set<Integer> voters = Utils.mkSet(voter1, voter2, voter3);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withVotedCandidate(epoch, voter1), quorumStateStore.readElectionState());

        pollUntilSend(client);
        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        // Wait until the vote requests are inflight
        pollUntilSend(client);
        List<RaftRequest.Outbound> voteRequests = collectVoteRequests(epoch, 0, 0);
        assertEquals(2, voteRequests.size());

        // While the vote requests are still inflight, we receive a BeginEpoch for the same epoch
        BeginQuorumEpochRequestData beginEpochRequest = beginEpochRequest(epoch, voter3);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), beginEpochRequest, time.milliseconds()));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, voter3), quorumStateStore.readElectionState());

        // The vote requests now return and should be ignored
        VoteResponseData voteResponse1 = voteResponse(false, Optional.empty(), epoch);
        channel.mockReceive(new RaftResponse.Inbound(voteRequests.get(0).correlationId, voteResponse1, voter2));

        VoteResponseData voteResponse2 = voteResponse(false, Optional.of(voter3), epoch);
        channel.mockReceive(new RaftResponse.Inbound(voteRequests.get(0).correlationId, voteResponse2, voter3));

        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, voter3), quorumStateStore.readElectionState());
    }

    @Test
    public void testObserverLeaderRediscoveryAfterBrokerNotAvailableError() throws IOException {
        int leaderId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(leaderId);
        KafkaRaftClient client = buildClient(voters);

        client.poll();
        int correlationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(correlationId,
            findQuorumResponse(leaderId, epoch, voters), -1));

        client.poll();
        assertEquals(ElectionState.withElectedLeader(epoch, leaderId), quorumStateStore.readElectionState());

        client.poll();
        int fetchCorrelationId = assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);

        FetchQuorumRecordsResponseData response = fetchRecordsResponse(epoch, leaderId, MemoryRecords.EMPTY, 0L,
                Errors.BROKER_NOT_AVAILABLE);
        channel.mockReceive(new RaftResponse.Inbound(fetchCorrelationId, response, leaderId));
        client.poll();

        assertEquals(ElectionState.withUnknownLeader(epoch), quorumStateStore.readElectionState());
        client.poll();
        assertSentFindQuorumRequest();
    }

    @Test
    public void testObserverLeaderRediscoveryAfterRequestTimeout() throws Exception {
        int leaderId = 1;
        int epoch = 5;
        Set<Integer> voters = Utils.mkSet(leaderId);
        KafkaRaftClient client = buildClient(voters);

        client.poll();
        int correlationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(correlationId,
            findQuorumResponse(leaderId, epoch, voters), -1));

        pollUntilSend(client);
        assertEquals(ElectionState.withElectedLeader(epoch, leaderId), quorumStateStore.readElectionState());
        assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);

        time.sleep(requestTimeoutMs);
        client.poll();

        assertEquals(ElectionState.withUnknownLeader(epoch), quorumStateStore.readElectionState());
        client.poll();
        assertSentFindQuorumRequest();
    }

    @Test
    public void testLeaderHandlesFindQuorum() throws IOException {
        KafkaRaftClient client = buildClient(Collections.singleton(localId));
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());

        int observerId = 1;
        FindQuorumRequestData request = new FindQuorumRequestData().setReplicaId(observerId);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), request, time.milliseconds()));

        client.poll();
        assertSentFindQuorumResponse(1, Optional.of(localId));
    }

    @Test
    public void testLeaderGracefulShutdown() throws Exception {
        int otherNodeId = 1;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);

        // Elect ourselves as the leader
        assertEquals(ElectionState.withVotedCandidate(1, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        pollUntilSend(client);

        int voteCorrelationId = assertSentVoteRequest(1, 0, 0L);
        VoteResponseData voteResponse = voteResponse(true, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(voteCorrelationId, voteResponse, otherNodeId));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());

        // Now shutdown
        int shutdownTimeoutMs = 5000;
        client.shutdown(shutdownTimeoutMs);

        // We should still be running until we have had a chance to send EndQuorumEpoch
        assertTrue(client.isRunning());

        // Send EndQuorumEpoch request to the other vote
        client.poll();
        assertTrue(client.isRunning());
        assertSentEndQuorumEpochRequest(1, localId);

        // Graceful shutdown completes when the epoch is bumped
        VoteRequestData newVoteRequest = voteRequest(2, otherNodeId, 0, 0L);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), newVoteRequest, time.milliseconds()));

        client.poll();
        assertFalse(client.isRunning());
    }

    @Test
    public void testLeaderGracefulShutdownTimeout() throws Exception {
        int otherNodeId = 1;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);

        // Elect ourselves as the leader
        assertEquals(ElectionState.withVotedCandidate(1, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(-1, 1, voters), -1));

        pollUntilSend(client);

        int voteCorrelationId = assertSentVoteRequest(1, 0, 0L);
        VoteResponseData voteResponse = voteResponse(true, Optional.empty(), 1);
        channel.mockReceive(new RaftResponse.Inbound(voteCorrelationId, voteResponse, otherNodeId));
        client.poll();
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());

        // Now shutdown
        int shutdownTimeoutMs = 5000;
        client.shutdown(shutdownTimeoutMs);

        // We should still be running until we have had a chance to send EndQuorumEpoch
        assertTrue(client.isRunning());

        // Send EndQuorumEpoch request to the other vote
        client.poll();
        assertTrue(client.isRunning());
        assertSentEndQuorumEpochRequest(1, localId);

        // The shutdown timeout is hit before we receive any requests or responses indicating an epoch bump
        time.sleep(shutdownTimeoutMs);

        client.poll();
        assertFalse(client.isRunning());
    }

    @Test
    public void testFollowerGracefulShutdown() throws IOException {
        int otherNodeId = 1;
        int epoch = 5;
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        KafkaRaftClient client = buildClient(Utils.mkSet(localId, otherNodeId));
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        client.poll();

        int shutdownTimeoutMs = 5000;
        client.shutdown(shutdownTimeoutMs);
        assertTrue(client.isRunning());
        client.poll();
        assertFalse(client.isRunning());
    }

    @Test
    public void testGracefulShutdownSingleMemberQuorum() throws IOException {
        KafkaRaftClient client = buildClient(Collections.singleton(localId));
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());
        client.poll();
        assertEquals(0, channel.drainSendQueue().size());
        int shutdownTimeoutMs = 5000;
        client.shutdown(shutdownTimeoutMs);
        assertTrue(client.isRunning());
        client.poll();
        assertFalse(client.isRunning());
    }

    @Test
    public void testFollowerReplication() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(otherNodeId, epoch, voters), -1));

        pollUntilSend(client);

        int fetchQuorumCorrelationId = assertSentFetchQuorumRecordsRequest(epoch, 0L, 0);
        Records records = MemoryRecords.withRecords(0L, CompressionType.NONE,
            3, new SimpleRecord("a".getBytes()), new SimpleRecord("b".getBytes()));
        FetchQuorumRecordsResponseData response = fetchRecordsResponse(epoch, otherNodeId, records, 0L, Errors.NONE);
        channel.mockReceive(new RaftResponse.Inbound(fetchQuorumCorrelationId, response, otherNodeId));

        client.poll();
        assertEquals(2L, log.endOffset());
    }

    @Test
    public void testAppendToNonLeaderFails() throws IOException {
        int otherNodeId = 1;
        int epoch = 5;
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());

        SimpleRecord[] appendRecords = new SimpleRecord[] {
            new SimpleRecord("a".getBytes()),
            new SimpleRecord("b".getBytes()),
            new SimpleRecord("c".getBytes())
        };
        Records records = MemoryRecords.withRecords(0L, CompressionType.NONE, 1, appendRecords);

        CompletableFuture<OffsetAndEpoch> future = client.append(records);
        client.poll();

        assertTrue(future.isCompletedExceptionally());
        TestUtils.assertFutureThrows(future, NotLeaderForPartitionException.class);
    }

    @Test
    public void testFetchShouldBeTreatedAsLeaderEndorsement() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, localId));
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        KafkaRaftClient client = buildClient(voters);
        assertEquals(ElectionState.withElectedLeader(epoch, localId), quorumStateStore.readElectionState());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(localId, epoch, voters), -1));

        pollUntilSend(client);

        // We send BeginEpoch, but it gets lost and the destination finds the leader through the FindQuorum API
        assertBeginQuorumEpochRequest(epoch);

        FetchQuorumRecordsRequestData fetchRequest = fetchQuorumRecordsRequest(epoch, otherNodeId, 0L);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), fetchRequest, time.milliseconds()));

        client.poll();

        // The BeginEpoch request eventually times out. We should not send another one.
        assertFetchQuorumRecordsResponse(epoch, localId);
        time.sleep(requestTimeoutMs);

        client.poll();

        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(0, sentMessages.size());
    }

    @Test
    public void testLeaderAppendSingleMemberQuorum() throws IOException {
        long now = time.milliseconds();

        KafkaRaftClient client = buildClient(Collections.singleton(localId));
        assertEquals(ElectionState.withElectedLeader(1, localId), quorumStateStore.readElectionState());

        SimpleRecord[] appendRecords = new SimpleRecord[] {
            new SimpleRecord("a".getBytes()),
            new SimpleRecord("b".getBytes()),
            new SimpleRecord("c".getBytes())
        };
        Records records = MemoryRecords.withRecords(1L, CompressionType.NONE, 1, appendRecords);

        // First poll has no high watermark advance
        client.poll();
        assertEquals(OptionalLong.of(0L), client.highWatermark());

        client.append(records);

        // Then poll the appended data with leader change record
        client.poll();
        assertEquals(OptionalLong.of(4L), client.highWatermark());

        // Now try reading it
        int otherNodeId = 1;
        FetchQuorumRecordsRequestData fetchRequest = fetchQuorumRecordsRequest(1, otherNodeId, 0L);
        channel.mockReceive(new RaftRequest.Inbound(channel.newCorrelationId(), fetchRequest, time.milliseconds()));

        client.poll();

        MemoryRecords fetchedRecords = assertFetchQuorumRecordsResponse(1, localId);
        List<MutableRecordBatch> batches = Utils.toList(fetchedRecords.batchIterator());
        assertEquals(2, batches.size());

        MutableRecordBatch leaderChangeBatch = batches.get(0);
        assertTrue(leaderChangeBatch.isControlBatch());
        List<Record> readRecords = Utils.toList(leaderChangeBatch.iterator());
        assertEquals(1, readRecords.size());

        Record record = readRecords.get(0);
        assertEquals(now, record.timestamp());
        verifyLeaderChangeMessage(localId, Collections.emptyList(),
            record.key(), record.value());

        MutableRecordBatch batch = batches.get(1);
        assertEquals(1, batch.partitionLeaderEpoch());
        readRecords = Utils.toList(batch.iterator());
        assertEquals(3, readRecords.size());

        for (int i = 0; i < appendRecords.length; i++) {
            assertEquals(appendRecords[i].value(), readRecords.get(i).value());
        }
    }

    @Test
    public void testFollowerLogReconciliation() throws Exception {
        int otherNodeId = 1;
        int epoch = 5;
        int lastEpoch = 3;
        Set<Integer> voters = Utils.mkSet(localId, otherNodeId);
        quorumStateStore.writeElectionState(ElectionState.withElectedLeader(epoch, otherNodeId));
        log.appendAsLeader(Arrays.asList(
                new SimpleRecord("foo".getBytes()),
                new SimpleRecord("bar".getBytes())), lastEpoch);
        log.appendAsLeader(Arrays.asList(
            new SimpleRecord("baz".getBytes())), lastEpoch);

        KafkaRaftClient client = buildClient(voters);

        assertEquals(ElectionState.withElectedLeader(epoch, otherNodeId), quorumStateStore.readElectionState());
        assertEquals(3L, log.endOffset());

        pollUntilSend(client);

        int findQuorumCorrelationId = assertSentFindQuorumRequest();
        channel.mockReceive(new RaftResponse.Inbound(findQuorumCorrelationId,
            findQuorumResponse(otherNodeId, epoch, voters), -1));

        pollUntilSend(client);

        int correlationId = assertSentFetchQuorumRecordsRequest(epoch, 3L, lastEpoch);

        FetchQuorumRecordsResponseData response = outOfRangeFetchRecordsResponse(epoch, otherNodeId, 2L,
            lastEpoch, 1L);
        channel.mockReceive(new RaftResponse.Inbound(correlationId, response, otherNodeId));

        // Poll again to complete truncation
        client.poll();
        assertEquals(2L, log.endOffset());

        // Now we should be fetching
        client.poll();
        assertSentFetchQuorumRecordsRequest(epoch, 2L, lastEpoch);
    }

    private void verifyLeaderChangeMessage(int leaderId,
                                           List<Integer> voters,
                                           ByteBuffer recordKey,
                                           ByteBuffer recordValue) {
        assertEquals(ControlRecordType.LEADER_CHANGE, ControlRecordType.parse(recordKey));

        LeaderChangeMessage leaderChangeMessage = ControlRecordUtils.deserializeLeaderChangeMessage(recordValue);
        assertEquals(leaderId, leaderChangeMessage.leaderId());
        assertEquals(voters.stream().map(voterId -> new Voter().setVoterId(voterId)).collect(Collectors.toList()),
            leaderChangeMessage.voters());
    }

    private int assertSentFindQuorumResponse(int epoch, Optional<Integer> leaderId) {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue(raftMessage.data() instanceof FindQuorumResponseData);
        FindQuorumResponseData response = (FindQuorumResponseData) raftMessage.data();
        assertEquals(Errors.NONE, Errors.forCode(response.errorCode()));
        assertEquals(epoch, response.leaderEpoch());
        assertEquals(leaderId.orElse(-1).intValue(), response.leaderId());
        return raftMessage.correlationId();
    }

    private MemoryRecords assertFetchQuorumRecordsResponse(int epoch, int leaderId) {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue(raftMessage.data() instanceof FetchQuorumRecordsResponseData);
        FetchQuorumRecordsResponseData response = (FetchQuorumRecordsResponseData) raftMessage.data();
        assertEquals(Errors.NONE, Errors.forCode(response.errorCode()));
        assertEquals(epoch, response.leaderEpoch());
        assertEquals(leaderId, response.leaderId());
        return MemoryRecords.readableRecords(response.records());
    }

    private int assertSentEndQuorumEpochRequest(int epoch, int leaderId) {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue(raftMessage.data() instanceof EndQuorumEpochRequestData);
        EndQuorumEpochRequestData request = (EndQuorumEpochRequestData) raftMessage.data();
        assertEquals(epoch, request.leaderEpoch());
        assertEquals(leaderId, request.leaderId());
        assertEquals(localId, request.replicaId());
        return raftMessage.correlationId();
    }

    private int assertSentFindQuorumRequest() {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue(raftMessage.data() instanceof FindQuorumRequestData);
        FindQuorumRequestData request = (FindQuorumRequestData) raftMessage.data();
        assertEquals(localId, request.replicaId());
        return raftMessage.correlationId();
    }

    private int assertSentVoteRequest(int epoch, int lastEpoch, long lastEpochOffset) {
        List<RaftRequest.Outbound> voteRequests = collectVoteRequests(epoch, lastEpoch, lastEpochOffset);
        assertEquals(1, voteRequests.size());
        return voteRequests.iterator().next().correlationId();
    }

    private List<RaftRequest.Outbound> collectVoteRequests(int epoch, int lastEpoch, long lastEpochOffset) {
        List<RaftRequest.Outbound> voteRequests = new ArrayList<>();
        for (RaftMessage raftMessage : channel.drainSendQueue()) {
            if (raftMessage.data() instanceof VoteRequestData) {
                VoteRequestData request = (VoteRequestData) raftMessage.data();
                assertEquals(epoch, request.candidateEpoch());
                assertEquals(localId, request.candidateId());
                assertEquals(lastEpoch, request.lastEpoch());
                assertEquals(lastEpochOffset, request.lastEpochEndOffset());
                voteRequests.add((RaftRequest.Outbound) raftMessage);
            }
        }
        return voteRequests;
    }

    private int assertBeginQuorumEpochRequest(int epoch) {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue(raftMessage.data() instanceof BeginQuorumEpochRequestData);
        BeginQuorumEpochRequestData request = (BeginQuorumEpochRequestData) raftMessage.data();
        assertEquals(epoch, request.leaderEpoch());
        assertEquals(localId, request.leaderId());
        return raftMessage.correlationId();
    }

    private int assertSentFetchQuorumRecordsRequest(
        int epoch,
        long fetchOffset,
        int lastFetchedEpoch
    ) {
        List<RaftMessage> sentMessages = channel.drainSendQueue();
        assertEquals(1, sentMessages.size());
        RaftMessage raftMessage = sentMessages.get(0);
        assertTrue("Unexpected request type " + raftMessage.data(),
            raftMessage.data() instanceof FetchQuorumRecordsRequestData);
        FetchQuorumRecordsRequestData request = (FetchQuorumRecordsRequestData) raftMessage.data();
        assertEquals(epoch, request.leaderEpoch());
        assertEquals(fetchOffset, request.fetchOffset());
        assertEquals(lastFetchedEpoch, request.lastFetchedEpoch());
        assertEquals(localId, request.replicaId());
        return raftMessage.correlationId();
    }

    private FetchQuorumRecordsResponseData fetchRecordsResponse(
        int epoch,
        int leaderId,
        Records records,
        long highWatermark,
        Errors error
    ) throws IOException {
        return new FetchQuorumRecordsResponseData()
                .setErrorCode(error.code())
                .setHighWatermark(highWatermark)
                .setLeaderEpoch(epoch)
                .setLeaderId(leaderId)
                .setRecords(RaftUtil.serializeRecords(records));
    }

    private FetchQuorumRecordsResponseData outOfRangeFetchRecordsResponse(
        int epoch,
        int leaderId,
        long nextFetchOffset,
        int nextFetchEpoch,
        long highWatermark
    ) {
        return new FetchQuorumRecordsResponseData()
            .setErrorCode(Errors.OFFSET_OUT_OF_RANGE.code())
            .setHighWatermark(highWatermark)
            .setNextFetchOffset(nextFetchOffset)
            .setNextFetchOffsetEpoch(nextFetchEpoch)
            .setLeaderEpoch(epoch)
            .setLeaderId(leaderId)
            .setRecords(ByteBuffer.wrap(new byte[0]));
    }

    private VoteResponseData voteResponse(boolean voteGranted, Optional<Integer> leaderId, int epoch) {
        return new VoteResponseData()
                .setVoteGranted(voteGranted)
                .setLeaderId(leaderId.orElse(-1))
                .setLeaderEpoch(epoch)
                .setErrorCode(Errors.NONE.code());
    }

    private VoteRequestData voteRequest(int epoch, int candidateId, int lastEpoch, long lastEpochOffset) {
        return new VoteRequestData()
                .setCandidateEpoch(epoch)
                .setCandidateId(candidateId)
                .setLastEpoch(lastEpoch)
                .setLastEpochEndOffset(lastEpochOffset);
    }

    private BeginQuorumEpochRequestData beginEpochRequest(int epoch, int leaderId) {
        return new BeginQuorumEpochRequestData()
            .setLeaderId(leaderId)
            .setLeaderEpoch(epoch);
    }

    private FindQuorumResponseData findQuorumResponse(int leaderId, int epoch, Collection<Integer> voters) {
        return new FindQuorumResponseData()
                .setErrorCode(Errors.NONE.code())
                .setLeaderEpoch(epoch)
                .setLeaderId(leaderId)
            .setVoters(voters.stream().map(voterId -> {
                InetSocketAddress address = mockAddress(voterId);
                return new FindQuorumResponseData.Voter()
                    .setVoterId(voterId)
                    .setBootTimestamp(0)
                    .setHost(address.getHostString())
                    .setPort(address.getPort());
            }).collect(Collectors.toList()));
    }

    private FindQuorumResponseData findQuorumFailure(Errors error) {
        return new FindQuorumResponseData()
                .setErrorCode(error.code())
                .setLeaderEpoch(-1)
                .setLeaderId(-1);
    }

    private FetchQuorumRecordsRequestData fetchQuorumRecordsRequest(int epoch, int replicaId, long fetchOffset) {
        return new FetchQuorumRecordsRequestData()
                .setLeaderEpoch(epoch)
                .setFetchOffset(fetchOffset)
                .setReplicaId(replicaId);
    }

    private void pollUntilSend(KafkaRaftClient client) throws InterruptedException {
        TestUtils.waitForCondition(() -> {
            client.poll();
            return channel.hasSentMessages();
        }, "Condition failed to be satisfied before timeout");
    }

}