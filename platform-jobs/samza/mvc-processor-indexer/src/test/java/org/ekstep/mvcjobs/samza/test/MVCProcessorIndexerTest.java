package org.ekstep.mvcjobs.samza.test;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import javafx.geometry.Pos;
import org.ekstep.mvcjobs.samza.service.MVCProcessorService;
import org.ekstep.mvcjobs.samza.service.util.MVCProcessorCassandraIndexer;
import org.ekstep.mvcjobs.samza.service.util.MVCProcessorESIndexer;
import org.ekstep.mvcjobs.samza.task.Postman;
import org.ekstep.mvcsearchindex.elasticsearch.ElasticSearchUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.apache.samza.config.Config;
import java.io.IOException;
import java.util.Map;

import static org.mockito.Mockito.stub;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ElasticSearchUtil.class, Config.class, MVCProcessorESIndexer.class,Postman.class, MVCProcessorService.class,MVCProcessorCassandraIndexer.class})
@PowerMockIgnore({"javax.management.*", "sun.security.ssl.*", "javax.net.ssl.*" , "javax.crypto.*"})
public class MVCProcessorIndexerTest {
    ObjectMapper mapper = new ObjectMapper();
    private String KEYSPACE_CREATE_SCRIPT = "CREATE KEYSPACE IF NOT EXISTS sunbirddev_content_store WITH replication = {'class': 'SimpleStrategy','replication_factor': '1'};";
    private  String table_script = "CREATE TABLE IF NOT EXISTS content_data ( content_id text PRIMARY KEY, body blob, last_updated_on timestamp, oldbody blob, stageicons blob ) WITH bloom_filter_fp_chance = 0.01 AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'} AND comment = '' AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'} AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'} AND crc_check_chance = 1.0 AND dclocal_read_repair_chance = 0.1 AND default_time_to_live = 0 AND gc_grace_seconds = 864000 AND max_index_interval = 2048 AND memtable_flush_period_in_ms = 0 AND min_index_interval = 128 AND read_repair_chance = 0.0 AND speculative_retry = '99PERCENTILE';";
    private  String altertablescript = "ALTER TABLE content_data ADD (textbook_name list<text>, level1_name list<text>, level1_concept list<text>, level2_name list<text>, level2_concept list<text>, level3_name list<text>, level3_concept list<text>, ml_content_text list<text>, ml_keywords list<text>, ml_content_text_vector frozen <set<decimal>>, source list<text>, source_url text, label text);";
   private String usekeyspace = "use sunbirddev_content_store;";
    private String uniqueId = "do_113041248230580224116";
    private String eventData = "{\"identifier\":\"do_113041248230580224116\",\"action\":\"update-es-index\",\"stage\":1}";
    private String eventData2 = "{\"action\":\"update-ml-keywords\",\"stage\":\"2\",\"ml_Keywords\":[\"maths\",\"addition\",\"add\"],\"ml_contentText\":\"This is the content text for addition of two numbers.\"}";
    private String eventData3 = "{\"action\":\"update-ml-contenttextvector\",\"stage\":3,\"ml_contentTextVector\":[[0.2961231768131256, 0.13621050119400024, 0.655802309513092, -0.33641257882118225]]}";
    private String case3data = "{\"eid\":\"MVC_JOB_PROCESSOR\",\"ets\":1591603456223,\"mid\":\"LP.1591603456223.a5d1c6f0-a95e-11ea-b80d-b75468d19fe4\",\"actor\":{\"id\":\"UPDATE ML CONTENT TEXT VECTOR\",\"type\":\"System\"},\"context\":{\"pdata\":{\"ver\":\"1.0\",\"id\":\"org.ekstep.platform\"},\"channel\":\"01285019302823526477\"},\"object\":{\"ver\":\"1.0\",\"id\":\"do_113025640118272000173\"},\"eventData\":{\"action\":\"update-ml-contenttextvector\",\"stage\":3,\"ml_contentTextVector\":[[1.1,2.1,7.4,68.2]]}}";
   private String case1data = "{\"eid\":\"MVC_JOB_PROCESSOR\",\"ets\":1591603456223,\"mid\":\"LP.1591603456223.a5d1c6f0-a95e-11ea-b80d-b75468d19fe4\",\"actor\":{\"id\":\"UPDATE ES INDEX\",\"type\":\"System\"},\"context\":{\"pdata\":{\"ver\":\"1.0\",\"id\":\"org.ekstep.platform\"},\"channel\":\"01285019302823526477\"},\"object\":{\"ver\":\"1.0\",\"id\":\"do_113041248230580224116\"},\"eventData\":{\"identifier\":\"do_113041248230580224116\",\"action\":\"update-es-index\",\"stage\":1}}";
    private Config configMock;
    private Session session = null;
    private Cluster cluster;
    @Before
    public void setup(){
        MockitoAnnotations.initMocks(this);
        configMock = Mockito.mock(Config.class);
        stub(configMock.get("nested.fields")).toReturn("badgeAssertions,targets,badgeAssociations,plugins,me_totalTimeSpent,me_totalPlaySessionCount,me_totalTimeSpentInSec,batches");
    }

    @Test
    public void testUpsertDocumentTrue() throws Exception {
        PowerMockito.mockStatic(ElasticSearchUtil.class);
        PowerMockito.doNothing().when(ElasticSearchUtil.class);
        ElasticSearchUtil.addDocumentWithId(Mockito.anyString(),Mockito.anyString(),Mockito.anyString());
        MVCProcessorESIndexer mvcProcessorESIndexer = new MVCProcessorESIndexer();
        mvcProcessorESIndexer.upsertDocument(uniqueId,getEvent(eventData));
}
    @Test
    public void testUpsertDocumentFalse() throws Exception {
        PowerMockito.mockStatic(ElasticSearchUtil.class);
        PowerMockito.doNothing().when(ElasticSearchUtil.class);
        ElasticSearchUtil.updateDocument(Mockito.anyString(),Mockito.anyString(),Mockito.anyString());
        MVCProcessorESIndexer mvcProcessorESIndexer = new MVCProcessorESIndexer();
        mvcProcessorESIndexer.upsertDocument(uniqueId,getEvent(eventData2));
    }

    @Test
    public void testInsertToCassandraForStage1() throws Exception  {
        MVCProcessorCassandraIndexer cassandraManager = new MVCProcessorCassandraIndexer();
        cassandraManager.insertintoCassandra(getEvent(eventData),uniqueId);
    }
    @Test
    public void testInsertToCassandraForStage2() throws Exception  {
        MVCProcessorCassandraIndexer cassandraManager = new MVCProcessorCassandraIndexer();
        cassandraManager.insertintoCassandra(getEvent(eventData2),uniqueId);
    }
    @Test
    public void testInsertToCassandraForStage3() throws Exception  {
        MVCProcessorCassandraIndexer cassandraManager = new MVCProcessorCassandraIndexer();
        cassandraManager.insertintoCassandra(getEvent(eventData3),uniqueId);
    }

    public  Map<String, Object> getEvent(String message) throws IOException {
      return  new Gson().fromJson(message, Map.class);
    }

}
