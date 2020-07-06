package org.sunbird.jobs.samza.service.util;

import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Batch;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Update;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.ekstep.common.Platform;
import org.ekstep.common.exception.ServerException;
import org.ekstep.graph.cache.util.RedisStoreUtil;
import org.ekstep.jobs.samza.util.JobLogger;
import org.ekstep.searchindex.elasticsearch.ElasticSearchUtil;
import org.ekstep.telemetry.logger.TelemetryManager;
import org.ekstep.telemetry.util.LogTelemetryEventUtil;
import org.sunbird.jobs.samza.task.CourseProgressHandler;
import org.sunbird.jobs.samza.util.CourseBatchParams;
import org.sunbird.jobs.samza.util.ESUtil;
import org.sunbird.jobs.samza.util.SunbirdCassandraUtil;
import redis.clients.jedis.Jedis;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

public class CourseBatchUpdater extends BaseCourseBatchUpdater {

    private static JobLogger LOGGER = new JobLogger(CourseBatchUpdater.class);
    private String keyspace = Platform.config.hasPath("courses.keyspace.name")
            ? Platform.config.getString("courses.keyspace.name")
            : "sunbird_courses";
    private String table = "user_courses";
    private static final String ES_INDEX_NAME = "user-courses";
    private static final String ES_DOC_TYPE = "_doc";
    private int leafNodesTTL = Platform.config.hasPath("content.leafnodes.ttl")
            ? Platform.config.getInt("content.leafnodes.ttl"): 3600;
    private Jedis redisConnect= null;
    private Session cassandraSession = null;
    private SystemStream certificateInstructionStream = null;

    public CourseBatchUpdater(Jedis redisConnect, Session cassandraSession) {
        ElasticSearchUtil.initialiseESClient(ES_INDEX_NAME, Platform.config.getString("search.es_conn_info"));
        this.redisConnect = redisConnect;
        this.cassandraSession = cassandraSession;
    }

    public CourseBatchUpdater(Jedis redisConnect, Session cassandraSession, SystemStream certificateInstructionStream) {
        ElasticSearchUtil.initialiseESClient(ES_INDEX_NAME, Platform.config.getString("search.es_conn_info"));
        this.redisConnect = redisConnect;
        this.cassandraSession = cassandraSession;
        this.certificateInstructionStream = certificateInstructionStream;
    }

    public void updateBatchStatus(Map<String, Object> edata, CourseProgressHandler courseProgressHandler) throws Exception {
        //Get data from content read
        String courseId = (String) edata.get("courseId");
        List<String> leafNodes = getLeafNodes(courseId);
        if(CollectionUtils.isEmpty(leafNodes)){
            LOGGER.info("Content does not have leafNodes : " + courseId);
        } else {
            //Compute status
            updateData(edata, leafNodes, courseProgressHandler);
        }
    }

    private List<String> getLeafNodes(String courseId) throws Exception {
        String key = courseId + ":leafnodes";
        List<String> leafNodes = getStringList(key);
        if (CollectionUtils.isEmpty(leafNodes)) {
            Map<String, Object> content = getContent(courseId, "leafNodes");
            leafNodes = (List<String>) content.getOrDefault("leafNodes", new ArrayList<String>());
            if (CollectionUtils.isNotEmpty(leafNodes))
                RedisStoreUtil.saveStringList(key, leafNodes, leafNodesTTL);
        }
        return leafNodes;
    }

    private void updateData(Map<String, Object> edata, List<String> leafNodes, CourseProgressHandler courseProgressHandler)  throws Exception {
        List<Map<String, Object>> contents = (List<Map<String, Object>>) edata.get("contents");
        String batchId = (String)edata.get("batchId");
        String userId = (String)edata.get("userId");
        String courseId = (String) edata.get("courseId");
        
        if(CollectionUtils.isNotEmpty(contents)) {
            Map<String, Object> contentStatus = new HashMap<>();
            Map<String, Object> contentStatusDelta = new HashMap<>();
            Map<String, Object> lastReadContentStats = new HashMap<>(); 
            String key = courseProgressHandler.getKey(batchId, userId, courseId);
            
            if(courseProgressHandler.containsKey(key)) {// Get Progress from the unprocessed list
                populateContentStatusFromHandler(key, courseProgressHandler, contentStatus, contentStatusDelta, lastReadContentStats);
            } else { // Get progress from cassandra
                populateContentStatusFromDB(batchId, userId, contentStatus, lastReadContentStats);
            }
            
            contents.forEach(c -> {
                String id = (String) c.get("contentId");
                if(contentStatus.containsKey(id)) {
                    contentStatus.put(id, Math.max(((Integer) contentStatus.get(id)), ((Integer)c.get("status"))));
                    contentStatusDelta.put(id, Math.max(((Integer) contentStatus.get(id)), ((Integer)c.get("status"))));
                } else {
                    contentStatus.put(id, c.get("status"));
                    contentStatusDelta.put(id, c.get("status"));
                }
            });

            List<String> completedIds = contentStatus.entrySet().stream()
                    .filter(entry -> (2 == ((Number) entry.getValue()).intValue()))
                    .map(entry -> entry.getKey()).distinct().collect(Collectors.toList());

            int size = CollectionUtils.intersection(completedIds, leafNodes).size();
            double completionPercentage = (((Number)size).doubleValue()/((Number)leafNodes.size()).doubleValue())*100;

            int status = (size == leafNodes.size()) ? 2 : 1;

            Map<String, Object> dataToUpdate =  new HashMap<String, Object>() {{
                put("contentStatus", contentStatus);
                put("contentStatusDelta", contentStatusDelta);
                put("status", status);
                put("completionPercentage", ((Number)completionPercentage).intValue());
                put("progress", size);
                putAll(lastReadContentStats);
                if(status == 2)
                    put("completedOn", new Timestamp(new Date().getTime()));
                
            }};

            if(MapUtils.isNotEmpty(dataToUpdate)) {
                courseProgressHandler.put(key, dataToUpdate);
            }
        }
    }

    private void populateContentStatusFromDB(String batchId, String userId, Map<String, Object> contentStatus, Map<String, Object> lastReadContentStats) {
        Map<String, Object> dataToSelect = new HashMap<String, Object>() {{
            put("batchid", batchId);
            put("userid", userId);
        }};
        ResultSet resultSet =  SunbirdCassandraUtil.read(cassandraSession, keyspace, table, dataToSelect);
        List<Row> rows = resultSet.all();
        if(CollectionUtils.isNotEmpty(rows)){
            Map<String, Integer> contentStatusMap =  rows.get(0).getMap("contentStatus", String.class, Integer.class);
            lastReadContentStats.put("lastReadContentId", rows.get(0).getString("lastreadcontentid"));
            lastReadContentStats.put("lastReadContentStatus", rows.get(0).getInt("lastreadcontentstatus"));
            if(MapUtils.isNotEmpty(contentStatusMap))
                contentStatus.putAll(contentStatusMap);
        }
    }

    private void populateContentStatusFromHandler(String key, CourseProgressHandler courseProgressHandler, Map<String, Object> contentStatus, Map<String, Object> contentStatusDelta, Map<String, Object> lastReadContentStats) {
        Map<String, Object> enrollmentDetails = (Map<String, Object>) courseProgressHandler.get(key);
        if(MapUtils.isNotEmpty(enrollmentDetails)) {
            Map<String, Integer> contentStatusMap = (Map<String, Integer>) enrollmentDetails.get("contentStatus");
            Map<String, Integer> contentStatusDeltaMap = (Map<String, Integer>) enrollmentDetails.getOrDefault("contentStatusDelta", new HashMap<>());
            lastReadContentStats.put("lastReadContentId", (String) enrollmentDetails.get("lastReadContentId"));
            lastReadContentStats.put("lastReadContentStatus", (int) enrollmentDetails.get("lastReadContentStatus"));
            if(MapUtils.isNotEmpty(contentStatusMap)){
                contentStatus.putAll(contentStatusMap);
                contentStatusDelta.putAll(contentStatusDeltaMap);
            }
        }
    }


    public List<String> getStringList(String key) {
        try {
            Set<String> set = redisConnect.smembers(key);
            List<String> list = new ArrayList<String>(set);
            return list;
        } catch (Exception e) {
            throw new ServerException("ERR_CACHE_GET_PROPERTY_ERROR", e.getMessage());
        }
    }

    public void updateBatchProgress(Session cassandraSession, CourseProgressHandler courseProgressHandler, MessageCollector collector) {
        if (courseProgressHandler.isNotEmpty()) {
            List<Update.Where> updateQueryList = new ArrayList<>();

            List<Map<String, Object>> courseCompletedEvent = new ArrayList<>();

            courseProgressHandler.getMap().entrySet().forEach(event -> {
                try {
                    Map<String, Object> dataToSelect = new HashMap<String, Object>() {{
                        put("batchid", event.getKey().split("_")[0]);
                        put("userid", event.getKey().split("_")[1]);
                    }};
                    Map<String, Object> dataToUpdate = new HashMap<>();
                    dataToUpdate.putAll((Map<String, Object>) event.getValue());
                    //Update cassandra
                    updateQueryList.add(updateQuery(keyspace, table, dataToUpdate, dataToSelect));
                    if(((Number) dataToUpdate.get("status")).intValue() == 2) {
                        LOGGER.info("CourseBatchUpdater:updateBatchProgress: event.getkey() : " + event.getKey().split("_"));
                        Map<String, Object> idDetails = new HashMap<String, Object>() {{
                            put("batchId", event.getKey().split("_")[0]);
                            put("userId", event.getKey().split("_")[1]);
                            put("courseId", event.getKey().split("_")[2]+ "_" +event.getKey().split("_")[3]);
                        }};
                        dataToUpdate.putAll(idDetails);
                        LOGGER.info("CourseBatchUpdater:updateBatchProgress: dataToUpdate : " + mapper.writeValueAsString(dataToUpdate));
                        courseCompletedEvent.add(dataToUpdate);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            //TODO: enhance this to contain only 65k queries in a batch. It is the batch limit from cassandra.
            if(CollectionUtils.isNotEmpty(updateQueryList)){
                Batch batch = QueryBuilder.batch(updateQueryList.toArray(new RegularStatement[updateQueryList.size()]));
                cassandraSession.execute(batch);
            }
            try {
                ESUtil.updateBatches(ES_INDEX_NAME, ES_DOC_TYPE, courseProgressHandler.getMap());
            } catch (Exception e) {
                e.printStackTrace();
            }

          try{
              LOGGER.info("CourseBatchUpdater:updateBatchProgress: certificate issue called : ");
              if (CollectionUtils.isNotEmpty(courseCompletedEvent)) {
                  courseCompletedEvent.stream().forEach(certificateEvent -> {
                      try {
                          String updatedCertificateEvent =  generateInstructionEvent(certificateEvent);
                          LOGGER.info("CourseBatchUpdater:updateBatchProgress: Kafka topic instruction event started: " + updatedCertificateEvent);
                          collector.send(new OutgoingMessageEnvelope(certificateInstructionStream, updatedCertificateEvent));
                          LOGGER.info("CourseBatchUpdater:updateBatchProgress: Kafka topic instruction event success.");
                      } catch (Exception e) {
                          e.printStackTrace();
                          TelemetryManager.error("CourseBatchUpdater:updateBatchProgress: Kafka topic instruction event failed: " + e);
                      }
                  });
              }
          } catch (Exception e){
              e.printStackTrace();
              TelemetryManager.error("CourseBatchUpdater:updateBatchProgress: courseCompletedEvent failed: " + e);
          }
        }
    }

    public void processBatchProgress(Map<String, Object> message, CourseProgressHandler courseProgressHandler) {
        try {
            Map<String, Object> eData = (Map<String, Object>) message.get(CourseBatchParams.edata.name());
            updateBatchStatus(eData, courseProgressHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public Update.Where updateQuery(String keyspace, String table, Map<String, Object> propertiesToUpdate, Map<String, Object> propertiesToSelect) {
        Update.Where updateQuery = QueryBuilder.update(keyspace, table).where();
        updateQuery.with(QueryBuilder.putAll("contentStatus", (Map<String, Object>)propertiesToUpdate.getOrDefault("contentStatusDelta", new HashMap<>())));
        propertiesToUpdate.entrySet().stream().filter(entry -> !entry.getKey().contains("contentStatus"))
                .forEach(entry -> updateQuery.with(QueryBuilder.set(entry.getKey(), entry.getValue())));
        propertiesToSelect.entrySet().forEach(entry -> {
            if (entry.getValue() instanceof List)
                updateQuery.and(QueryBuilder.in(entry.getKey(), (List) entry.getValue()));
            else
                updateQuery.and(QueryBuilder.eq(entry.getKey(), entry.getValue()));
        });
        return updateQuery;
    }

    private String generateInstructionEvent(Map<String, Object> certificateEvent) {
        Map<String, Object> actor = new HashMap<>();
        Map<String, Object> context = new HashMap<>();
        Map<String, Object> object = new HashMap<>();
        Map<String, Object> edata = new HashMap<>();

        actor.put(CourseBatchParams.id.name(), "Course Certificate Generator");
        actor.put(CourseBatchParams.type.name(), "System");

        String id = (String) certificateEvent.get("batchId") + "_" + (String) certificateEvent.get("courseId");

        context.put(
                CourseBatchParams.pdata.name(),
                new HashMap<String, Object>() {
                    {
                        put(CourseBatchParams.id.name(), "org.sunbird.platform");
                        put(CourseBatchParams.ver.name(), "1.0");
                    }
                });

        object.put(CourseBatchParams.id.name(), id);
        object.put(CourseBatchParams.type.name(), "CourseCertificateGeneration");

        edata.putAll(
                new HashMap<String, Object>() {
                    {
                        put(CourseBatchParams.userIds.name(), Arrays.asList((String) certificateEvent.get("userId")));
                        put(CourseBatchParams.batchId.name(), (String) certificateEvent.get("batchId"));
                        put(CourseBatchParams.courseId.name(), (String) certificateEvent.get("courseId"));
                        put(CourseBatchParams.action.name(), "issue-certificate");
                        put(CourseBatchParams.reIssue.name(), false);
                    }
                });
        return LogTelemetryEventUtil.logInstructionEvent(actor, context, object, edata);
    }
}
