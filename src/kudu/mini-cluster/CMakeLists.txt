# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set(MINI_CLUSTER_SRCS
  external_mini_cluster.cc
  internal_mini_cluster.cc
  mini_cluster.cc
  webui_checker.cc
)

set(MINI_CLUSTER_LIBS
  gflags
  glog
  gmock
  gtest
  gutil
  krpc
  kudu_client
  kudu_common
  kudu_test_util
  kudu_util
  master
  master_proto
  mini_hms
  mini_kdc
  mini_oidc
  mini_ranger
  mini_ranger_kms
  server_base_proto
  tablet_proto
  tserver
  tserver_proto
  tserver_service_proto
  wire_protocol_proto)

if (NOT NO_CHRONY)
  set(MINI_CLUSTER_LIBS ${MINI_CLUSTER_LIBS} mini_chronyd)
endif()

add_library(mini_cluster ${MINI_CLUSTER_SRCS})
target_link_libraries(mini_cluster ${MINI_CLUSTER_LIBS})
add_dependencies(mini_cluster
  kudu-tserver
  kudu-master)

# Tests
SET_KUDU_TEST_LINK_LIBS(mini_cluster kudu_hms)
ADD_KUDU_TEST(external_mini_cluster-test PROCESSORS 3)
