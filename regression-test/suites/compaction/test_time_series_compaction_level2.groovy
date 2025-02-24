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

import org.codehaus.groovy.runtime.IOGroovyMethods

suite("test_time_series_compaction_level2", "nonConcurrent") {
    def tableName = "test_time_series_compaction_level2"
    def backendId_to_backendIP = [:]
    def backendId_to_backendHttpPort = [:]
    getBackendIpHttpPort(backendId_to_backendIP, backendId_to_backendHttpPort);
 
    def trigger_cumulative_compaction_on_tablets = { tablets ->
        for (def tablet : tablets) {
            String tablet_id = tablet.TabletId
            String backend_id = tablet.BackendId
            int times = 1
            
            String compactionStatus;
            do{
                def (code, out, err) = be_run_cumulative_compaction(backendId_to_backendIP.get(backend_id), backendId_to_backendHttpPort.get(backend_id), tablet_id)
                logger.info("Run compaction: code=" + code + ", out=" + out + ", err=" + err)
                ++times
                sleep(1000)
                compactionStatus = parseJson(out.trim()).status.toLowerCase();
            } while (compactionStatus!="success" && times<=3)
            if (compactionStatus!="success") {
                assertTrue(compactionStatus.contains("2000"))
                continue;
            }
            assertEquals("success", compactionStatus)
        }
    }

    def wait_cumulative_compaction_done = { tablets ->
        for (def tablet in tablets) {
            boolean running = true
            do {
                Thread.sleep(1000)
                String tablet_id = tablet.TabletId
                String backend_id = tablet.BackendId
                def (code, out, err) = be_get_compaction_status(backendId_to_backendIP.get(backend_id), backendId_to_backendHttpPort.get(backend_id), tablet_id)
                logger.info("Get compaction status: code=" + code + ", out=" + out + ", err=" + err)
                assertEquals(code, 0)
                def compactionStatus = parseJson(out.trim())
                assertEquals("success", compactionStatus.status.toLowerCase())
                running = compactionStatus.run_status
            } while (running)
        }
    }

    def get_rowset_count = { tablets ->
        int rowsetCount = 0
        for (def tablet in tablets) {
            def (code, out, err) = curl("GET", tablet.CompactionStatus)
            logger.info("Show tablets status: code=" + code + ", out=" + out + ", err=" + err)
            assertEquals(code, 0)
            def tabletJson = parseJson(out.trim())
            assert tabletJson.rowsets instanceof List
            rowsetCount +=((List<String>) tabletJson.rowsets).size()
        }
        return rowsetCount
    }

    sql """ DROP TABLE IF EXISTS ${tableName}; """
    sql """
        CREATE TABLE ${tableName} (
            `id` int(11) NULL,
            `name` varchar(255) NULL,
            `hobbies` text NULL,
            `score` int(11) NULL
        ) ENGINE=OLAP
        DUPLICATE KEY(`id`)
        COMMENT 'OLAP'
        DISTRIBUTED BY HASH(`id`) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1",
            "disable_auto_compaction" = "true",
            "compaction_policy" = "time_series",
            "time_series_compaction_goal_size_mbytes" = "1024",
            "time_series_compaction_file_count_threshold" = "10",
            "time_series_compaction_time_threshold_seconds" = "3600",
            "time_series_compaction_empty_rowsets_threshold" = "5",
            "time_series_compaction_level_threshold" = "2"
        );
    """

    try {
        GetDebugPoint().enableDebugPointForAllBEs("time_series_level2_file_count")

        def tablets = sql_return_maparray """ show tablets from ${tableName}; """

        int replicaNum = 1
        def dedup_tablets = deduplicate_tablets(tablets)
        if (dedup_tablets.size() > 0) {
            replicaNum = Math.round(tablets.size() / dedup_tablets.size())
            if (replicaNum != 1 && replicaNum != 3) {
                assert(false)
            }
        }

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                sql """ INSERT INTO ${tableName} VALUES (1, "andy", "andy love apple", 100); """
            }
            sql "sync"

            int rowsetCount = get_rowset_count.call(tablets);
            println "rowsetCount: ${rowsetCount}"
            assert (rowsetCount == 10 * replicaNum + i + 1)

            trigger_cumulative_compaction_on_tablets.call(tablets)
            wait_cumulative_compaction_done.call(tablets)
        }

        int rowsetCount = get_rowset_count.call(tablets);
        assert (rowsetCount == 10 * replicaNum + 1)

        trigger_cumulative_compaction_on_tablets.call(tablets)
        wait_cumulative_compaction_done.call(tablets)

        rowsetCount = get_rowset_count.call(tablets);
        assert (rowsetCount == 1 + 1)
    } finally {
        GetDebugPoint().disableDebugPointForAllBEs("time_series_level2_file_count")
    }
}
