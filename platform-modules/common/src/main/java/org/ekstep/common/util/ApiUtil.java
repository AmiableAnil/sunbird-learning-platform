package org.ekstep.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Response;
import org.ekstep.common.dto.ResponseParams;
import org.ekstep.common.exception.ServerException;
import org.ekstep.telemetry.logger.TelemetryManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiUtil {

	private static Gson gsonObj = new Gson();
	private static ObjectMapper objMapper = new ObjectMapper();
	private static String LANGUAGEAPIKEY = "";
	private static String KEYWORD_KEY = "";

	private static final String ct = "application/x-www-form-urlencoded";

	static{
		LANGUAGEAPIKEY = Platform.config.getString("language.api.key");
		KEYWORD_KEY = Platform.config.getString("keyword.api.key");
	}

	public static Response makeKeyWordsPostRequest(String identifier, Map<String, Object> requestMap) {
		String uri = "https://api.aylien.com/api/v1/entities";
		TelemetryManager.log("ApiUtil:makePostRequest |  Request Url:" + uri);
		TelemetryManager.log("ApiUtil:makePostRequest |  Request Body:" + requestMap);
		HttpResponse<String> response = null;

		/*Map<String, String> headerParam = new HashMap<String, String>(){{
			put("X-AYLIEN-TextAPI-Application-Key",KEYWORD_KEY);
			put("X-AYLIEN-TextAPI-Application-ID","68f7c606");
			put("Content-Type","application/x-www-form-urlencoded");
		}};*/
		Map<String, String> headerParam = new HashMap<String, String>();
		headerParam.put("X-AYLIEN-TextAPI-Application-Key",KEYWORD_KEY);
		headerParam.put("X-AYLIEN-TextAPI-Application-ID","68f7c606");
		//headerParam.put("Content-Type","application/x-www-form-urlencoded");
		Unirest.setDefaultHeader("Content-Type",ct);

		if (null == requestMap)
			throw new ServerException("ERR_INVALID_REQUEST_BODY", "Request Body is Manadatory");

		try {
			response = Unirest.post(uri).headers(headerParam)
					.field("text", (String)requestMap.get("text")).asString();
		} catch (Exception e) {
			throw new ServerException("ERR_CALL_API",
					"Something Went Wrong While Making API Call | Error is: " + e.getMessage());
		}
		Response resp = getSuccessResponse();
		String body = "";
		Map<String,Object> result = null;
		try {
			body = response.getBody();
			System.out.println("body of makeKeyWordsPostRequest for content id :: "+identifier + " | Body  : "+body);
			if (StringUtils.isNotBlank(body))
				result = objMapper.readValue(body, new TypeReference<Map<String, Object>>() {
				});
		} catch (Exception  e) {
			System.out.println("Exception Occurred While Making Keyword API Call. Exception Msg : "+e.getMessage());
			e.printStackTrace();
			TelemetryManager.info("Exception:::::"+ e);
		}
		List<String> keywords = (List<String>)((Map<String,Object>)result.get("entities")).get("keyword");
		System.out.println("keywords generated for content id : "+keywords);
		if(null!= result && !result.isEmpty())
			resp.getResult().put("keywords",keywords);

		Unirest.setDefaultHeader("Content-Type","application/json");
		return resp;
	}
	
	public static Map<String, Object> languageAnalysisAPI(String text){
		Unirest.setDefaultHeader("Content-Type","application/json");
		//Map<String, Object> requestMap = new HashMap<String, Object>() {{put("request", new HashMap<String, Object>(){{put("language_id", "en");put("text", text);}});}};
		Map<String, Object> req = new HashMap<String, Object>();
		Map<String, Object> req1 = new HashMap<String, Object>();
		req1.put("language_id","en");
		req1.put("text",text);
		req.put("request",req1);
		Map<String, Object> languageAnalysisMap = null;
		try {
			//String body = objMapper.writeValueAsString(request);
			String body = gsonObj.toJson(req);
			System.out.println("LANGUAGEAPIKEY :: " + LANGUAGEAPIKEY);
			System.out.println("language api call request body : "+body);
			HttpResponse<String> httpResponse = Unirest.post("https://api.ekstep.in/language/v3/tools/text/analysis").header("Authorization", "Bearer "+ LANGUAGEAPIKEY).body(body).asString();
			
			if(httpResponse.getStatus() == 200) {
				Map<String, Object> responseMap = objMapper.readValue(httpResponse.getBody(), Map.class);
				if (MapUtils.isEmpty(responseMap)) {
					return languageAnalysisMap;
				}
				Map<String, Object> result = (Map<String, Object>) responseMap.get("result");
				if (MapUtils.isEmpty(result)) {
					return languageAnalysisMap;
				}
				Map<String, Object> text_complexity = (Map<String, Object>) result.get("text_complexity");
				if (MapUtils.isEmpty(text_complexity)) {
					return languageAnalysisMap;
				}
				
				Map<String, Object> thresholdVocabulary = (Map<String, Object>) text_complexity.get("thresholdVocabulary");
				Integer totalWordCount = (Integer) text_complexity.get("totalWordCount");
				Map<String, Object> partsOfSpeech = (Map<String, Object>) text_complexity.get("partsOfSpeech");
				Map<String, Object> nonThresholdVocabulary = (Map<String, Object>) text_complexity.get("nonThresholdVocabulary");
				
				languageAnalysisMap = new HashMap<String, Object>() {{
					put("thresholdVocabulary", thresholdVocabulary);
					put("totalWordCount", totalWordCount);
					put("partsOfSpeech", partsOfSpeech);
					put("nonThresholdVocabulary", nonThresholdVocabulary);
				}};
				System.out.println("languageAnalysisMap:: " + languageAnalysisMap);
			}else {
				System.out.println("Error:: " + httpResponse.getStatus());
				System.out.println("Error:: " + httpResponse.getBody());
			}
		}catch(Exception e) {
			e.printStackTrace();
		}
		System.out.println("End languageAnalysisMap:: " + languageAnalysisMap);
		Unirest.setDefaultHeader("Content-Type","application/json");
		return languageAnalysisMap;
	}

	private static Response getSuccessResponse() {
		Response resp = new Response();
		ResponseParams respParam = new ResponseParams();
		respParam.setStatus("successful");
		resp.setParams(respParam);
		return resp;
	}

	/*public static void main(String[] args) {
		Map<String, Object> requestMap = new HashMap<String, Object>() {{put("request", new HashMap<String, Object>(){{put("language_id", "en");put("text", "Hello World");}});}};
		Map<String, Object> req = new HashMap<String, Object>();
		Map<String, Object> req1 = new HashMap<String, Object>();
		req1.put("language_id","en");
		req1.put("text","Hello World");
		req.put("request",req1);
		String body = gsonObj.toJson(req);
		System.out.println(body);
	}*/

	/*public void delay(long time){
		try{
			Thread.sleep(time);
		}catch(Exception e){

		}
	}*/

}
