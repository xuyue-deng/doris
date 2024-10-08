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


suite("test_compound_reader_fault_injection", "nonConcurrent") {
    // define a sql table
    def testTable = "httplogs"

    sql "DROP TABLE IF EXISTS ${testTable}"
    sql """
        CREATE TABLE ${testTable} (
          `@timestamp` int(11) NULL COMMENT "",
          `clientip` string NULL COMMENT "",
          `request` string NULL COMMENT "",
          `status` string NULL COMMENT "",
          `size` string NULL COMMENT "",
          INDEX request_idx (`request`) USING INVERTED PROPERTIES("parser" = "english", "support_phrase" = "true") COMMENT ''
          ) ENGINE=OLAP
          DUPLICATE KEY(`@timestamp`)
          COMMENT "OLAP"
          DISTRIBUTED BY HASH(`@timestamp`) BUCKETS 1
          PROPERTIES (
          "replication_allocation" = "tag.location.default: 1",
          "inverted_index_storage_format" = "V1"
        );
      """

    sql """ INSERT INTO ${testTable} VALUES (893964617, '40.135.0.0', 'GET /images/hm_bg.jpg HTTP/1.0', 200, 24736); """
    sql """ INSERT INTO ${testTable} VALUES (893964653, '232.0.0.0', 'GET /images/hm_bg.jpg HTTP/1.0', 200, 3781); """
    sql """ INSERT INTO ${testTable} VALUES (893964672, '26.1.0.0', 'GET /images/hm_bg.jpg HTTP/1.0', 304, 0); """
    sql """ INSERT INTO ${testTable} VALUES (893964672, '26.1.0.0', 'GET /images/hm_bg.jpg HTTP/1.0', 304, 0); """
    sql """ INSERT INTO ${testTable} VALUES (893964653, '232.0.0.0', 'GET /images/hm_bg.jpg HTTP/1.0', 200, 3781); """

    sql 'sync'

    try {
        GetDebugPoint().enableDebugPointForAllBEs("construct_DorisCompoundReader_failed")
        try {
          sql """ select count() from ${testTable} where (request match 'HTTP');  """
        } catch (Exception e) {
          log.info(e.getMessage())
          assertTrue(e.getMessage().contains("construct_DorisCompoundReader_failed"))
        }
    } finally {
        GetDebugPoint().disableDebugPointForAllBEs("construct_DorisCompoundReader_failed")
        qt_sql """ select count() from ${testTable} where (request match 'HTTP');  """
    }
}
