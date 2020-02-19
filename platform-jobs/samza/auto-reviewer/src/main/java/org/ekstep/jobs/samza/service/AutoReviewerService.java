package org.ekstep.jobs.samza.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.config.Config;
import org.apache.samza.task.MessageCollector;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Response;
import org.ekstep.common.dto.ResponseParams;
import org.ekstep.common.util.HttpDownloadUtility;
import org.ekstep.common.util.PDFUtil;
import org.ekstep.graph.dac.model.Node;
import org.ekstep.jobs.samza.service.task.JobMetrics;
import org.ekstep.jobs.samza.util.JSONUtils;
import org.ekstep.jobs.samza.util.JobLogger;
import org.ekstep.jobs.samza.util.ReviewerUtil;
import org.ekstep.jobs.samza.util.SamzaCommonParams;
import org.ekstep.learning.router.LearningRequestRouterPool;
import org.ekstep.learning.util.ControllerUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.ekstep.common.util.HttpRestUtil.makeDSPostRequest;

public class AutoReviewerService  implements ISamzaService {

	private static JobLogger LOGGER = new JobLogger(AutoReviewerService.class);
	private Config config = null;
	private Integer MAX_ITERATION_COUNT = null;
	private ControllerUtil util = new ControllerUtil();
	private static Boolean isDummyResponse = false;
	private static String passportKey = "";
	private static String dsUri = "http://50.1.0.13:5000/ML/AutoReview";
	private static Integer pdfSize = 5;
	protected static ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public void initialize(Config config) throws Exception {
		this.config = config;
		JSONUtils.loadProperties(config);
		LOGGER.info("Service config initialized");
		MAX_ITERATION_COUNT = (Platform.config.hasPath("max.iteration.count.samza.job")) ?
				Platform.config.getInt("max.iteration.count.samza.job") : 1;
		LearningRequestRouterPool.init();
		LOGGER.info("Learning Actor System initialized");
		isDummyResponse = StringUtils.equalsIgnoreCase("true",Platform.config.getString("curate.dummy.response"))?true:false;
		passportKey = Platform.config.getString("graph.passport.key.base");
		dsUri = Platform.config.getString("ds.api.url");
		pdfSize = Platform.config.getInt("pdf.size.allowed");
	}

	@Override
	public void processMessage(Map<String, Object> message, JobMetrics metrics, MessageCollector collector) throws Exception {
		LOGGER.info("AutoReviewerService ---> processMessage ---->>> start");
		if (null == message) {
			LOGGER.info("Null Event Received. So Skipped Processing.");
			return;
		}
		Map<String, Object> edata = (Map<String, Object>) message.get(SamzaCommonParams.edata.name());
		Map<String, Object> object = (Map<String, Object>) message.get(SamzaCommonParams.object.name());
		if (!validateEvent(edata, object)) {
			LOGGER.info("Event Ignored. Event Validation Failed for post-publish-processor operations.");
			return;
		}
		LOGGER.info("Event Received: "+message);
		List<String> tasks = (List<String>) edata.get("tasks");
		LOGGER.info("Tasks Received: "+tasks);
		String identifier = (String) object.get("id");
		String artifactUrl = (String) edata.get("artifactUrl");
		Node node = util.getNode("domain",identifier);
		LOGGER.info("=============== DEBUG INFO ============");
		LOGGER.info("identifier : "+identifier);
		LOGGER.info("artifactUrl : "+artifactUrl);
		if(null!=node)
			LOGGER.info("Node is not null for "+identifier);
		LOGGER.info("isDummyResponse : "+isDummyResponse);
		LOGGER.info("passportKey : "+passportKey);
		LOGGER.info("dsUri : "+dsUri);
		LOGGER.info("pdfSize : "+pdfSize);
		LOGGER.info("=============== DEBUG INFO ============");

		if(CollectionUtils.isNotEmpty(tasks) && (tasks.contains("profanity") || tasks.contains("audio") || tasks.contains("tags"))){
			if(isDummyResponse){
				LOGGER.info("Inserting Dummy Metadata For ML API Call : "+identifier);
				delay(10000);
				updateSuccessMeta(identifier, node);
			}
			else{
				//TODO: Make ML API Call
				try{
					Map<String, Object> request = new HashMap<String, Object>(){{
						put("content_id",identifier);
						put("artifactUrl",artifactUrl);
						put("mimeType",(String)node.getMetadata().get("mimeType"));
						put("version","1.0");
						put("timestamp",(String)node.getMetadata().get("lastUpdatedOn"));
					}};
					Response response = makeDSPostRequest(dsUri,request,new HashMap<String, String>());
					if(checkError(response)){
						LOGGER.info("ML API Call Failed For Content Id : "+identifier);
					}else{
						LOGGER.info("ML API Call Successful For Content Id : "+identifier);
					}
				}catch(Exception e){
					LOGGER.info("ML API Call Failed For Content Id : "+identifier);
				}

			}
		}

		// kp metadata update
		if(CollectionUtils.isNotEmpty(tasks) && tasks.contains("size") && StringUtils.equalsIgnoreCase("application/pdf",(String)node.getMetadata().get("mimeType"))){
			try{
				LOGGER.info("Computing Size For "+identifier);
				//String directory = "/tmp/"+identifier+File.separator+getFieNameFromURL(artifactUrl);
				File file = HttpDownloadUtility.downloadFile(artifactUrl,"/tmp");
				int count = PDFUtil.getPageCount(file);
				LOGGER.info("count :: "+count);
				String sizeSt = count<=pdfSize?"Passed":"Failed";
				Map<String, Object> meta = new HashMap<String, Object>(){{
					put("name","Size");
					put("type", "size");
					put("status",sizeSt);
					put("result",String.valueOf(count));
				}};
				Node n = util.getNode("domain", identifier);
				n.getMetadata().put("ckp_size",meta);
				n.getMetadata().put("versionKey",passportKey);
				Response response = util.updateNode(n);

				if(checkError(response)){
					LOGGER.info("Error Occurred While Performing Curation. Error in updating content with size metadata");
					LOGGER.info("Error Response | Result : "+response.getResult()+" | Params :"+response.getParams() + " | Code :"+response.getResponseCode().toString());
				}else{
					LOGGER.info("size metadata updated for "+identifier);
				}
			}catch(Exception e){
				LOGGER.info("size metadata computation Failed For Content Id : "+identifier);
			}


		}
		if(CollectionUtils.isNotEmpty(tasks) && tasks.contains("translation") && StringUtils.equalsIgnoreCase("application/vnd.ekstep.ecml-archive",(String)node.getMetadata().get("mimeType"))){
			try{
				String translatedId = callCopyAPI(identifier.replace(".img", ""));
				String st_trns = (StringUtils.isNotBlank(translatedId))?"Passed":"Failed";
				Map<String, Object> st_res = new HashMap<>();
				if(StringUtils.isNotBlank(translatedId))
					st_res.put("translated_id",translatedId);
				Map<String, Object> ckp_translation = new HashMap<String, Object>() {{
					put("name","Hindi Translation");
					put("type", "translation");
					put("status",st_trns);
					put("result",st_res);
				}};

				Node n_ckp_translation = util.getNode("domain", identifier);
				n_ckp_translation.getMetadata().put("ckp_translation",ckp_translation);
				n_ckp_translation.getMetadata().put("versionKey",passportKey);
				Response response = util.updateNode(n_ckp_translation);

				if(checkError(response)){
					LOGGER.info("Error Occurred While Performing Curation. Error in updating content with Translation metadata");
					LOGGER.info("Error Response | Result : "+response.getResult()+" | Params :"+response.getParams() + " | Code :"+response.getResponseCode().toString());
				}else{
					LOGGER.info("Translation metadata updated for "+identifier+" | Metadata : "+ckp_translation);
				}
			}catch(Exception e){
				LOGGER.info("translation metadata computation Failed For Content Id : "+identifier);
			}
		}

		//TODO: add for pdf also,
		if(CollectionUtils.isNotEmpty(tasks) && tasks.contains("keywords") && StringUtils.equalsIgnoreCase("application/vnd.ekstep.ecml-archive",(String)node.getMetadata().get("mimeType"))){
			try{
				List<String> text = new ArrayList<>();
				if(StringUtils.equalsIgnoreCase("application/vnd.ekstep.ecml-archive",(String)node.getMetadata().get("mimeType"))){
					text.addAll(ReviewerUtil.getECMLText(identifier));
				}else if(StringUtils.equalsIgnoreCase("application/pdf",(String)node.getMetadata().get("mimeType"))){
					//TODO: add the text
				}
				List<String> keywords = ReviewerUtil.getKeywords(identifier, text);
				String st_keywords = (CollectionUtils.isNotEmpty(keywords))?"Passed":"Failed";
				Map<String, Object> ckp_keywords = new HashMap<String, Object>() {{
					put("name","Suggested Keywords");
					put("type", "keywords");
					put("status",st_keywords);
					put("result",keywords);
				}};

				Node node_keywords = util.getNode("domain", identifier);
				node_keywords.getMetadata().put("ckp_keywords",ckp_keywords);
				node_keywords.getMetadata().put("versionKey",passportKey);
				Response response = util.updateNode(node_keywords);

				if(checkError(response)){
					LOGGER.info("Error Occurred While Performing Curation. Error in updating content with  Suggested Keywords metadata");
					LOGGER.info("Error Response | Result : "+response.getResult()+" | Params :"+response.getParams() + " | Code :"+response.getResponseCode().toString());
				}else{
					LOGGER.info("Suggested Keywords metadata updated for "+identifier + "| Metadata : "+ckp_keywords );
				}
			}catch(Exception e){
				LOGGER.info("keywords metadata computation Failed For Content Id : "+identifier);
			}

		}

		//end of process;
	}
	
	
	private String callCopyAPI(String identifier) throws UnirestException, IOException {
        String request = "{\"request\":{\"content\":{\"createdBy\":\"devcon\",\"createdFor\":[\"devcon\"],\"organisation\":[\"devcon\"],\"framework\":\"DevCon-NCERT\"}}}";
        HttpResponse<String> httpResponse = Unirest.post("http://50.1.0.5:8080/learning-service/content/v3/copy/" + identifier).header("Content-Type", "application/json").body(request).asString();
        if(httpResponse.getStatus() == 200) {
            Map<String, Object> responseMap = objectMapper.readValue(httpResponse.getBody(), Map.class);
            Map<String, Object> result = (Map<String, Object>)responseMap.get("result");
            if (MapUtils.isNotEmpty(result)) {
            	String translatedString = (String)((Map<String, Object>)result.get("node_id")).get(identifier);
                return translatedString;
			}
            return "";
            
        }else {
        	LOGGER.info("Error Occurred While Making Content Copy API Call For Translation. Error Code Is : "+httpResponse.getStatus());
        	LOGGER.info("Response Body: "+httpResponse.getBody());
            return "";
        }
    }
	
	

	private String  getFieNameFromURL(String fileUrl) {
		String fileName = FilenameUtils.getBaseName(fileUrl) + "_" + System.currentTimeMillis();
		if (!FilenameUtils.getExtension(fileUrl).isEmpty()) fileName += "." + FilenameUtils.getExtension(fileUrl);
		return fileName;
	}

	private void delay(int time) {
		try{
			Thread.sleep(time);
		}catch(Exception e){

		}
	}

	private void updateSuccessMeta(String identifier, Node node) {
//		Map<String, Object> curationMetadata = new HashMap<>();
		Map<String, Object> curationMetadata = new HashMap<>();
		curationMetadata.put("cml_tags",new HashMap<String, Object>(){{
			put("name","Suggested Tags");
			put("type", "tags");
			put("status","Passed");
			put("result",new ArrayList<String>(){{
				add("Science");
			}});
		}});
		curationMetadata.put("cml_profanity",new HashMap<String, Object>(){{
			put("name","Profanity");
			put("type", "quality");
			put("status","Pending");
			put("result",new ArrayList<String>());
		}});
		curationMetadata.put("cml_audio",new HashMap<String, Object>(){{
			put("name","Audio");
			put("type", "quality");
			put("status","Passed");
			put("result",new HashMap<String, Object>(){{
				put("score","8/10");
				put("percentile","80%");
			}});
		}});
		curationMetadata.put("ckp_profanity",new HashMap<String, Object>(){{
			put("name","Profanity");
			put("type", "quality");
			put("status","Passed");
			put("result",new ArrayList<String>());
		}});
		curationMetadata.put("ckp_audio",new HashMap<String, Object>(){{
			put("name","Audio");
			put("type", "quality");
			put("status","Passed");
			put("result",new HashMap<String, Object>(){{
				put("score","8/10");
				put("percentile","80%");
			}});
		}});
		curationMetadata.put("ckp_image",new HashMap<String, Object>(){{
			put("name","Image");
			put("type", "quality");
			put("status","Passed");
			put("result",new ArrayList<String>());
		}});

		curationMetadata.put("ckp_lng_analysis",new HashMap<String, Object>(){{
			put("name","Language Analysis");
			put("type", "ln_analysis");
			put("status","Passed");
			put("result",new HashMap<String,Object>(){{
				put("totalWordCount","80");
				put("partsOfSpeech",new HashMap<String,Object>(){{
					put("adjective","9.62%");
					put("conjunction","1.92%");
					put("preposition","3.85%");
					put("verb","5.77%");
					put("noun","30.77%");
					put("adverb","15.38%");
					put("article","1.92%");
				}});
				put("thresholdVocabulary", new HashMap<String, Object>(){{
					put("wordCount","3");
					put("%OfWords","5.77");
				}});
				put("nonThresholdVocabulary", new HashMap<String, Object>(){{
					put("wordCount","49");
					put("%OfWords","94.23");
				}});
			}});
		}});
		curationMetadata.put("ckp_translation",new HashMap<String, Object>(){{
			put("name","Hindi Translation");
			put("type", "translation");
			put("status","Pending");
			put("result",new HashMap<String, Object>());
		}});
		/*curationMetadata.put("cml_tags",new HashMap<String, Object>(){{
			put("name","Suggested Tags");
			put("type", "tags");
			put("status","Passed");
			put("result",new ArrayList<String>(){{
				add("Science");
				add("Physics");
			}});
		}});
		curationMetadata.put("cml_keywords",new HashMap<String, Object>(){{
			put("name","Suggested Keywords");
			put("type", "keywords");
			put("status","Passed");
			put("result",new ArrayList<String>(){{
				add("Force");
				add("Newton's Law");
				add("Motion");
			}});
		}});
		curationMetadata.put("cml_quality",new HashMap<String, Object>(){{
			put("name","Content Quality Check");
			put("type", "quality");
			put("status","Passed");
			put("result",new HashMap<String, Object>(){{
				put("audio",new HashMap<String, Object>(){{
					put("status","Passed");
					put("score","8/10");
					put("percentile","80%");
				}});
				put("image",new HashMap<String, Object>(){{
					put("status","Passed");
					put("score","7/10");
					put("percentile","70%");
				}});
				put("language_complexity",new HashMap<String, Object>(){{
					put("status","Passed");
					put("ph_score","20");
					put("og_score","15.5");
					put("percentile","10%");
				}});
				put("profanity",new HashMap<String, Object>(){{
					put("status","Passed");
					put("result",new ArrayList<String>());
				}});
			}});
		}});*/

		node.getMetadata().putAll(curationMetadata);
		node.getMetadata().put("versionKey",passportKey);
		Response response = util.updateNode(node);
		if(checkError(response)){
			LOGGER.info("Error Occurred While Performing Curation. Error in updating content with dummy metadata for ml");
		}else{
			LOGGER.info("Updated Dummy ML Metadata for "+identifier);
		}
	}

	private boolean validateEvent(Map<String, Object> edata, Map<String, Object> object) {
		if (MapUtils.isEmpty(object) || StringUtils.isBlank((String) object.get("id")) ||
				MapUtils.isEmpty(edata) || StringUtils.isBlank((String) edata.get("action")))
			return false;
		String action= (String) edata.get("action");
		Integer iteration = (Integer) edata.get(SamzaCommonParams.iteration.name());
		String contentType = (String) edata.get("contentType");
		return (StringUtils.isNotEmpty(action) && iteration <= MAX_ITERATION_COUNT);
	}

	public boolean checkError(Response response) {
		ResponseParams params = response.getParams();
		if (null != params) {
			if (StringUtils.equals(ResponseParams.StatusType.failed.name(), params.getStatus())) {
				return true;
			}
		}
		return false;
	}
}
