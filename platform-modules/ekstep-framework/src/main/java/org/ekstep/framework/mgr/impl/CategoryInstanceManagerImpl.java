package org.ekstep.framework.mgr.impl;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.ekstep.common.dto.Response;
import org.ekstep.common.exception.ClientException;
import org.ekstep.common.exception.ResponseCode;
import org.ekstep.graph.dac.enums.GraphDACParams;
import org.ekstep.graph.dac.model.Node;
import org.ekstep.learning.common.enums.ContentErrorCodes;
import org.springframework.stereotype.Component;

import org.ekstep.framework.enums.CategoryEnum;
import org.ekstep.framework.mgr.ICategoryInstanceManager;

/**
 * This is the entry point for all CRUD operations related to category Instance
 * API.
 * 
 * @author Rashmi
 *
 */
@Component
public class CategoryInstanceManagerImpl extends BaseFrameworkManager implements ICategoryInstanceManager {

	private static final String CATEGORY_INSTANCE_OBJECT_TYPE = "CategoryInstance";

	private static final String GRAPH_ID = "domain";

	@Override
	public Response createCategoryInstance(String identifier, Map<String, Object> request) throws Exception {
		if (null == request)
			return ERROR("ERR_INVALID_CATEGORY_INSTANCE_OBJECT", "Invalid Request", ResponseCode.CLIENT_ERROR);
		if (null == request.get("code") || StringUtils.isBlank((String) request.get("code")))
			return ERROR("ERR_CATEGORY_INSTANCE_CODE_REQUIRED", "Unique code is mandatory for categoryInstance",
					ResponseCode.CLIENT_ERROR);
		validateCategoryNode((String)request.get("code"));
		String id = generateIdentifier(identifier, (String) request.get("code"));
		if (null != id)
			request.put(CategoryEnum.identifier.name(), id);
		setRelations(identifier, request);
		Response response = create(request, CATEGORY_INSTANCE_OBJECT_TYPE);
		if(response.getResponseCode() == ResponseCode.OK) {
			generateFrameworkHierarchy(id);
		}
		return response;
	}

	@Override
	public Response readCategoryInstance(String identifier, String categoryInstanceId) {
		categoryInstanceId = generateIdentifier(identifier, categoryInstanceId);
		if (validateScopeNode(categoryInstanceId, identifier)) {
			return read(categoryInstanceId, CATEGORY_INSTANCE_OBJECT_TYPE, CategoryEnum.category.name());
		} else {
			throw new ClientException(
					ContentErrorCodes.ERR_CHANNEL_NOT_FOUND.name() + "/"
							+ ContentErrorCodes.ERR_FRAMEWORK_NOT_FOUND.name(),
					"Given channel/framework is not related to given category");
		}
	}

	@Override
	public Response updateCategoryInstance(String identifier, String categoryInstanceId, Map<String, Object> map) throws Exception {
		if (null == map)
			return ERROR("ERR_INVALID_CATEGORY_INSTANCE_OBJECT", "Invalid Request", ResponseCode.CLIENT_ERROR);
		categoryInstanceId = generateIdentifier(identifier, categoryInstanceId);
		if (validateScopeNode(categoryInstanceId, identifier)) {
			Response response = update(categoryInstanceId, CATEGORY_INSTANCE_OBJECT_TYPE, map);
			if(response.getResponseCode() == ResponseCode.OK) {
				generateFrameworkHierarchy(categoryInstanceId);
			}
			return response;
		} else {
			throw new ClientException(
					ContentErrorCodes.ERR_CHANNEL_NOT_FOUND.name() + "/"
							+ ContentErrorCodes.ERR_FRAMEWORK_NOT_FOUND.name(),
					"Given channel/framework is not related to given category");
		}
	}

	@Override
	public Response searchCategoryInstance(String identifier, Map<String, Object> map) {
		if (null == map)
			return ERROR("ERR_INVALID_CATEGORY_INSTANCE_OBJECT", "Invalid Request", ResponseCode.CLIENT_ERROR);
		return search(map, CATEGORY_INSTANCE_OBJECT_TYPE, "categoryInstances", identifier);
	}

	@Override
	public Response retireCategoryInstance(String identifier, String categoryInstanceId) {
		categoryInstanceId = generateIdentifier(identifier, categoryInstanceId);
		if (validateScopeNode(categoryInstanceId, identifier)) {
			return retire(categoryInstanceId, CATEGORY_INSTANCE_OBJECT_TYPE);
		} else {
			throw new ClientException(
					ContentErrorCodes.ERR_CHANNEL_NOT_FOUND.name() + "/"
							+ ContentErrorCodes.ERR_FRAMEWORK_NOT_FOUND.name(),
					"Given channel/framework is not related to given category");
		}

	}

	public boolean validateScopeId(String identifier) {
		if (StringUtils.isNotBlank(identifier)) {
			Response response = getDataNode(GRAPH_ID, identifier);
			if (checkError(response)) {
				return false;
			} else {
				Node node = (Node) response.get(GraphDACParams.node.name());
				if (StringUtils.equalsIgnoreCase(identifier, node.getIdentifier())) {
					return true;
				}
			}
		}
		return false;
	}
	
	private void validateCategoryNode(String code) {
		Response response = getDataNode(GRAPH_ID, code);
		if(checkError(response)) 
			throw new ClientException(ContentErrorCodes.ERR_CATEGORY_NOT_FOUND.name() + "/"
					+ ContentErrorCodes.ERR_CATEGORY_NOT_FOUND.name(),
			"Given category does not belong to master category data");
	}
}