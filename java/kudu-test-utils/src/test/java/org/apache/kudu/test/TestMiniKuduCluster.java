// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.Socket;

import com.google.protobuf.ByteString;
import org.junit.Rule;
import org.junit.Test;

import org.apache.kudu.client.AsyncKuduClient;
import org.apache.kudu.client.Client.AuthenticationCredentialsPB;
import org.apache.kudu.client.HostAndPort;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduClient.KuduClientBuilder;
import org.apache.kudu.client.ListTablesResponse;
import org.apache.kudu.client.TimeoutTracker;
import org.apache.kudu.security.Token.JwtRawPB;
import org.apache.kudu.test.cluster.FakeDNS;
import org.apache.kudu.test.cluster.MiniKuduCluster;
import org.apache.kudu.test.cluster.MiniKuduCluster.MiniKuduClusterBuilder;
import org.apache.kudu.test.junit.RetryRule;
import org.apache.kudu.tools.Tool.CreateClusterRequestPB.JwksOptionsPB;
import org.apache.kudu.tools.Tool.CreateClusterRequestPB.MiniOidcOptionsPB;

public class TestMiniKuduCluster {

  private static final int NUM_TABLET_SERVERS = 3;
  private static final int NUM_MASTERS = 1;
  private static final long SLEEP_TIME_MS = 10000;

  @Rule
  public RetryRule retryRule = new RetryRule();

  @Rule
  public KuduTestHarness harness;

  @Test(timeout = 50000)
  public void test() throws Exception {
    try (MiniKuduCluster cluster = new MiniKuduCluster.MiniKuduClusterBuilder()
                                                      .numMasterServers(NUM_MASTERS)
                                                      .numTabletServers(NUM_TABLET_SERVERS)
                                                      .build()) {
      assertEquals(NUM_MASTERS, cluster.getMasterServers().size());
      assertEquals(NUM_TABLET_SERVERS, cluster.getTabletServers().size());

      {
        // Kill the master.
        HostAndPort masterHostPort = cluster.getMasterServers().get(0);
        testHostPort(masterHostPort, true);
        cluster.killMasterServer(masterHostPort);

        testHostPort(masterHostPort, false);

        // Restart the master.
        cluster.startMasterServer(masterHostPort);

        // Test we can reach it.
        testHostPort(masterHostPort, true);

        // Pause master.
        cluster.pauseMasterServer(masterHostPort);
        // Pausing master again doesn't do anything.
        cluster.pauseMasterServer(masterHostPort);

        // Resume master.
        cluster.resumeMasterServer(masterHostPort);
        // Resuming master while it's not paused doesn't do anything.
        cluster.resumeMasterServer(masterHostPort);
      }

      {
        // Kill the first TS.
        HostAndPort tsHostPort = cluster.getTabletServers().get(0);
        testHostPort(tsHostPort, true);
        cluster.killTabletServer(tsHostPort);

        testHostPort(tsHostPort, false);

        // Restart it.
        cluster.startTabletServer(tsHostPort);

        testHostPort(tsHostPort, true);

        // Pause the first TS.
        cluster.pauseTabletServer(tsHostPort);
        // Pausing master again doesn't do anything.
        cluster.pauseTabletServer(tsHostPort);

        // Resume test first TS.
        cluster.resumeTabletServer(tsHostPort);
        // Resuming master while it's not paused doesn't do anything.
        cluster.resumeTabletServer(tsHostPort);
      }
    }
  }

  @Test(timeout = 50000)
  public void testJwt() throws Exception {
    try {
      MiniKuduClusterBuilder clusterBuilder = new MiniKuduCluster.MiniKuduClusterBuilder()
              .numMasterServers(NUM_MASTERS)
              .numTabletServers(0)
              .enableClientJwt()
              .addJwks("account-id", true);

      harness = new KuduTestHarness(clusterBuilder);
      harness.before();
      harness.startAllMasterServers();

      String jwt = harness.createJwtFor("account-id", "subject", true);
      assertNotNull(jwt);
      AuthenticationCredentialsPB credentials = AuthenticationCredentialsPB.newBuilder()
              .setJwt(JwtRawPB.newBuilder()
                      .setJwtData(ByteString.copyFromUtf8(jwt))
                      .build())
              .build();

      AsyncKuduClient c = harness.getAsyncClient();
      c.importAuthenticationCredentials(credentials.toByteArray());
      c.getTablesList();

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test(timeout = 50000)
  public void testKerberos() throws Exception {
    FakeDNS.getInstance().install();
    try (MiniKuduCluster cluster = new MiniKuduCluster.MiniKuduClusterBuilder()
                                                      .numMasterServers(NUM_MASTERS)
                                                      .numTabletServers(NUM_TABLET_SERVERS)
                                                      .enableKerberos()
                                                      .build();
         KuduClient client = new KuduClientBuilder(cluster.getMasterAddressesAsString()).build()) {
      ListTablesResponse resp = client.getTablesList();
      assertTrue(resp.getTablesList().isEmpty());
      assertNull(client.getHiveMetastoreConfig());
    }
  }

  @Test(timeout = 100000)
  public void testHiveMetastoreIntegration() throws Exception {
    try (MiniKuduCluster cluster = new MiniKuduCluster.MiniKuduClusterBuilder()
                                                      .numMasterServers(NUM_MASTERS)
                                                      .numTabletServers(NUM_TABLET_SERVERS)
                                                      .enableHiveMetastoreIntegration()
                                                      .build();
         KuduClient client = new KuduClientBuilder(cluster.getMasterAddressesAsString()).build()) {
      assertNotNull(client.getHiveMetastoreConfig());
    }
  }

  /**
   * Test whether the specified host and port is open or closed, waiting up to a certain time.
   * @param hp the host and port to test
   * @param testIsOpen true if we should want it to be open, false if we want it closed
   */
  private static void testHostPort(HostAndPort hp,
                                   boolean testIsOpen) throws InterruptedException {
    TimeoutTracker tracker = new TimeoutTracker();
    while (tracker.getElapsedMillis() < SLEEP_TIME_MS) {
      try {
        Socket socket = new Socket(hp.getHost(), hp.getPort());
        socket.close();
        if (testIsOpen) {
          return;
        }
      } catch (IOException e) {
        if (!testIsOpen) {
          return;
        }
      }
      Thread.sleep(200);
    }
    fail("HostAndPort " + hp + " is still " + (testIsOpen ? "closed " : "open"));
  }
}
