package kr.re.keti.sc.ingestinterface.datamodel.service;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.re.keti.sc.ingestinterface.common.code.IngestInterfaceCode.AttributeType;
import kr.re.keti.sc.ingestinterface.common.code.IngestInterfaceCode.AttributeValueType;
import kr.re.keti.sc.ingestinterface.common.code.IngestInterfaceCode.DefaultAttributeKey;
import kr.re.keti.sc.ingestinterface.common.code.IngestInterfaceCode.ErrorCode;
import kr.re.keti.sc.ingestinterface.common.code.IngestInterfaceCode.PropertyKey;
import kr.re.keti.sc.ingestinterface.common.exception.BadRequestException;
import kr.re.keti.sc.ingestinterface.common.exception.BaseException;
import kr.re.keti.sc.ingestinterface.datamodel.DataModelManager;
import kr.re.keti.sc.ingestinterface.datamodel.dao.DataModelDAO;
import kr.re.keti.sc.ingestinterface.datamodel.vo.Attribute;
import kr.re.keti.sc.ingestinterface.datamodel.vo.ContextVO;
import kr.re.keti.sc.ingestinterface.datamodel.vo.DataModelBaseVO;
import kr.re.keti.sc.ingestinterface.datamodel.vo.DataModelVO;
import kr.re.keti.sc.ingestinterface.datamodel.vo.ObjectMember;
import kr.re.keti.sc.ingestinterface.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Data model Service class
 */
@Service
@Slf4j
public class DataModelSVC {
	
	@Autowired
    private DataModelManager dataModelManager;
    @Autowired
    private DataModelDAO dataModelDAO;
    @Autowired
    private ObjectMapper objectMapper;
    
    public static final String URI_PATTERN_CREATE_DATA_MODEL = "/datamodels";
    public static final Pattern URI_PATTERN_DATA_MODEL = Pattern.compile("/datamodels/(?<id>.+)");


    public List<DataModelBaseVO> getDataModelBaseVOList() {
        return dataModelDAO.getDataModelBaseVOList();
    }

    public DataModelBaseVO getDataModelBaseVOById(String dataModelId) {
        DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
        dataModelBaseVO.setId(dataModelId);
        return dataModelDAO.getDataModelBaseVOById(dataModelBaseVO);
    }

    public void processCreate(String to, String requestBody, String requestId, Date eventTime) {
    	
    	if(URI_PATTERN_CREATE_DATA_MODEL.equals(to)) {
    		createDataModel(requestBody, requestId, eventTime);

    	// 404
    	} else {
    		throw new BadRequestException(ErrorCode.NOT_EXIST_ID);
    	}
    }

    private void createDataModel(String requestBody, String requestId, Date eventTime) throws BaseException {

    	// 1. ?????? ????????? ??????
    	DataModelVO dataModelVO = null;
		try {
			dataModelVO = objectMapper.readValue(requestBody, DataModelVO.class);
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Invalid Parameter. body=" + requestBody);
		}

		// 2. ????????? ??????
		// 2-1) get @context ?????? ?????? ??????
		List<String> contextUriList = dataModelVO.getContext();
		if(ValidateUtil.isEmptyData(contextUriList)) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "Not exists @Context.");
		}
		// 2-2) attribute??? ????????? ?????? ??????
		checkAttributeName(dataModelVO.getAttributes());
		// 2-3) attributeTyp ??? valueType ??????
		checkAttributeTypeAndValueType(dataModelVO.getAttributes());
		// 2-4) context ?????? ??????
		Map<String, String> contextMap = dataModelManager.contextToFlatMap(contextUriList);
		// 2-5) type ????????? @context ?????? ???????????? ??? ?????? ??????
		boolean validType = false;
 		// type ????????? full uri ??? ??? ??????
 		if(dataModelVO.getType().startsWith("http")) {
 	 		
 	 		for(Map.Entry<String, String> entry : contextMap.entrySet()) {
 	 			String shortType = entry.getKey();
 	 			String fullUriType = entry.getValue();
 	 			
 	 			if(fullUriType.equals(dataModelVO.getType())) {
 	 				dataModelVO.setTypeUri(fullUriType);
 	 				dataModelVO.setType(shortType);
 	 				validType = true;
 	 			}
 	 		}
 		// type ????????? short name ??? ??????
 		} else {
 			if(contextMap.get(dataModelVO.getType()) != null) {
 				dataModelVO.setTypeUri(contextMap.get(dataModelVO.getType()));
 				validType = true;
 			}
 		}
 		if(!validType) {
 			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "Not exists type '" + dataModelVO.getType() + "' in @context=" + dataModelVO.getContext());
 		}
		// 2-6) attribute?????? context ?????? ???????????? ??? ??????
		checkAttributeNameByContext(dataModelVO.getAttributes(), contextMap);

		// 3. set attribute context uri
		setAttributeContextUri(dataModelVO.getAttributes(), contextMap);
		
		DataModelBaseVO retrieveDataModelBaseVO = getDataModelBaseVOById(dataModelVO.getId());

		if (retrieveDataModelBaseVO != null) {
			
			// 4. ??????????????? ?????? ?????? IngestInterface ?????????????????? DB ?????? ????????? ??????
			if(alreadyProcessByOtherInstance(requestId, eventTime, retrieveDataModelBaseVO)) {
	        	// ?????? Instance?????? DB ???????????? ?????? ?????????????????? ????????? ???????????? ?????? ??????
	            dataModelManager.putDataModelCache(retrieveDataModelBaseVO);
	            return;
	        } else {
        		// ?????? ??????????????? ????????????
	        	updateDataModel(retrieveDataModelBaseVO.getId(), requestBody, requestId, eventTime);
	        	return;
        	}
        }

		// 5. dataModel ?????? ??????
        DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
        dataModelBaseVO.setId(dataModelVO.getId());
        dataModelBaseVO.setType(dataModelVO.getType());
        dataModelBaseVO.setTypeUri(dataModelVO.getTypeUri());
        dataModelBaseVO.setName(dataModelVO.getName());
        dataModelBaseVO.setDescription(dataModelVO.getDescription());
        dataModelBaseVO.setEnabled(true);
        try {
        	dataModelBaseVO.setDataModel(objectMapper.writeValueAsString(dataModelVO));
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.UNKNOWN_ERROR, "DataModel parsing error. body=" + requestBody);
		}
        dataModelBaseVO.setProvisioningRequestId(requestId);
        dataModelBaseVO.setProvisioningEventTime(eventTime);

    	dataModelDAO.createDataModelBaseVO(dataModelBaseVO);

        // 6. Cache ?????? ??????
        dataModelManager.putDataModelCache(dataModelBaseVO);
    }

    public int fullUpdateDataModel(DataModelBaseVO dataModelBaseVO) {
        return dataModelDAO.updateDataModelBase(dataModelBaseVO);
    }

    public void processUpdate(String to, String dataModels, String requestId, Date eventTime) throws BaseException {
    	Matcher matcherForUpdate = URI_PATTERN_DATA_MODEL.matcher(to);

		if(matcherForUpdate.find()) {
			String id = matcherForUpdate.group("id");

			updateDataModel(id, dataModels, requestId, eventTime);
			
	    // 404
		} else {
			throw new BadRequestException(ErrorCode.NOT_EXIST_ID);
		}
    }

    private void updateDataModel(String dataModelId, String requestBody, String requestId, Date eventTime) {
    	// 1. ?????? ????????? ??????
    	DataModelVO requestDataModelVO = null;
		try {
			requestDataModelVO = objectMapper.readValue(requestBody, DataModelVO.class);
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Invalid Parameter. body=" + requestBody);
		}
		
		// 2. ????????? ??????
		// 2-1) get @context ?????? ?????? ??????
		List<String> contextUriList = requestDataModelVO.getContext();
		if(ValidateUtil.isEmptyData(contextUriList)) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "Not exists @Context.");
		}
		// 2-2) attribute??? ????????? ?????? ??????
		checkAttributeName(requestDataModelVO.getAttributes());
		// 2-3) attributeTyp ??? valueType ??????
		checkAttributeTypeAndValueType(requestDataModelVO.getAttributes());
		// 2-4) context ?????? ??????
		Map<String, String> contextMap = dataModelManager.contextToFlatMap(contextUriList);
		// 2-5) type ????????? @context ?????? ???????????? ??? ?????? ??????
		boolean validType = false;
 		// type ????????? full uri ??? ??? ??????
 		if(requestDataModelVO.getType().startsWith("http")) {
 	 		
 	 		for(Map.Entry<String, String> entry : contextMap.entrySet()) {
 	 			String shortType = entry.getKey();
 	 			String fullUriType = entry.getValue();
 	 			
 	 			if(fullUriType.equals(requestDataModelVO.getType())) {
 	 				requestDataModelVO.setTypeUri(fullUriType);
 	 				requestDataModelVO.setType(shortType);
 	 				validType = true;
 	 			}
 	 		}
 		// type ????????? short name ??? ??????
 		} else {
 			if(contextMap.get(requestDataModelVO.getType()) != null) {
 				requestDataModelVO.setTypeUri(contextMap.get(requestDataModelVO.getType()));
 				validType = true;
 			}
 		}
 		if(!validType) {
 			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "Not exists type '" + requestDataModelVO.getType() + "' in @context=" + requestDataModelVO.getContext());
 		}
		// 2-6) attribute?????? context ?????? ???????????? ??? ??????
		checkAttributeNameByContext(requestDataModelVO.getAttributes(), contextMap);

		// 3. set attribute context uri
		setAttributeContextUri(requestDataModelVO.getAttributes(), contextMap);

		// 4. ??????????????? ???????????? ??????
		DataModelBaseVO retrieveDataModelBaseVO = getDataModelBaseVOById(dataModelId);
		if (retrieveDataModelBaseVO == null) {
			log.info("Create(Upsert) DataModel. requestId={}, requestBody={}", requestId, requestBody);
			createDataModel(requestBody, requestId, eventTime);
			return;
        }

        // 5. ??????????????? ?????? ?????? IngestInterface ?????????????????? DB ?????? ????????? ??????
		if(alreadyProcessByOtherInstance(requestId, eventTime, retrieveDataModelBaseVO)) {
        	// ?????? Instance?????? DB ???????????? ?????? ?????????????????? ????????? ???????????? ?????? ??????
            dataModelManager.putDataModelCache(retrieveDataModelBaseVO);
            return;
        }

		// 6. dataModel ?????? ????????????
        DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
        dataModelBaseVO.setId(requestDataModelVO.getId());
        dataModelBaseVO.setName(requestDataModelVO.getName());
        dataModelBaseVO.setDescription(requestDataModelVO.getDescription());
        dataModelBaseVO.setEnabled(true);
        dataModelBaseVO.setProvisioningRequestId(requestId);
        dataModelBaseVO.setProvisioningEventTime(eventTime);
        try {
        	dataModelBaseVO.setDataModel(objectMapper.writeValueAsString(requestDataModelVO));
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.UNKNOWN_ERROR, "DataModel parsing error. body=" + requestBody);
		}
        dataModelDAO.updateDataModelBase(dataModelBaseVO);

        // 7. Cache ?????? ?????????
        dataModelManager.putDataModelCache(dataModelBaseVO);
    }

    public void processDelete(String to, String dataModels, String requestId, Date eventTime) throws BaseException {
    	Matcher matcherForDelete = URI_PATTERN_DATA_MODEL.matcher(to);
		
    	if(matcherForDelete.find()) {
			String id = matcherForDelete.group("id");

			deleteDataModel(id);

	    // 404
		} else {
			throw new BadRequestException(ErrorCode.NOT_EXIST_ID);
		}
    }

	private void deleteDataModel(String id) {
		
		// 1. ???????????? ?????? ??? ????????? ??????
		DataModelBaseVO retrieveDataModelBaseVO = getDataModelBaseVOById(id);

		if (retrieveDataModelBaseVO != null) {
			// 2. dataModel ?????? ??????
			DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
			dataModelBaseVO.setId(id);
			dataModelDAO.deleteDataModelBaseVO(dataModelBaseVO);
		}

		// 3. Cache ?????? ??????
        dataModelManager.removeDataModelCache(id);
	}

	/**
     * Attribute name ??????
     * @param attributes
     */
    private void checkAttributeName(List<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            checkAttributeName(attribute);
        }
    }

    /**
     * Attribute name ??????
     * @param attribute
     */
    private void checkAttributeName(Attribute attribute) {
		String attributeName = attribute.getName();
		if(DefaultAttributeKey.CONTEXT.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.ID.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.DATASET_ID.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.CREATED_AT.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.MODIFIED_AT.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.OPERATION.getCode().equalsIgnoreCase(attributeName)
				|| DefaultAttributeKey.TYPE.getCode().equalsIgnoreCase(attributeName)) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
					"Invalid attribute name. '" + attributeName + "' is a reserved word");
		}
		
		List<ObjectMember> objectMembers = attribute.getObjectMembers();
		if(objectMembers != null) {
			for(ObjectMember objectMember : objectMembers) {
				String objectMemberName = objectMember.getName();
				if(PropertyKey.OBSERVED_AT.getCode().equalsIgnoreCase(objectMemberName)
						|| PropertyKey.CREATED_AT.getCode().equalsIgnoreCase(objectMemberName)
						|| PropertyKey.MODIFIED_AT.getCode().equalsIgnoreCase(objectMemberName)
						|| PropertyKey.UNIT_CODE.getCode().equalsIgnoreCase(objectMemberName)) {
					throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
		        			"Invalid attribute name. '" + attributeName + "." + objectMemberName +"' is a reserved word");
				}
			}
		}
	}

    /**
     * INTEGER, DOUBLE??? ????????? ?????? ??????
     *
     * @param attribute
     */
    private void checkAttributeInequality(Attribute attribute) {

        AttributeValueType attributeValueType = attribute.getValueType();

        if (attributeValueType.equals(AttributeValueType.INTEGER)
                || attributeValueType.equals(AttributeValueType.LONG)
                || attributeValueType.equals(AttributeValueType.DOUBLE)
                || attributeValueType.equals(AttributeValueType.ARRAY_INTEGER)
                || attributeValueType.equals(AttributeValueType.ARRAY_LONG)
                || attributeValueType.equals(AttributeValueType.ARRAY_DOUBLE)) {

            if (attribute.getGreaterThan() != null && attribute.getGreaterThanOrEqualTo() != null) {
                throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "You should have only greaterThan or greaterThanOrEqualTo");
            }

            if (attribute.getLessThan() != null && attribute.getLessThanOrEqualTo() != null) {
                throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "You should have only lessThan or lessThanOrEqualTo");
            }

        } else {

            if (attribute.getGreaterThan() != null
                    || attribute.getGreaterThanOrEqualTo() != null
                    || attribute.getGreaterThan() != null
                    || attribute.getGreaterThanOrEqualTo() != null) {
                throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "You should have only lessThan or lessThanOrEqualTo");
            }
        }
    }

    /**
     * AttributeType??? valueType ??????
     * @param attributes
     */
    private void checkAttributeTypeAndValueType(List<Attribute> attributes) {

        for (Attribute attribute : attributes) {

            String name = attribute.getName();
            AttributeValueType attributeValueType = attribute.getValueType();
            AttributeType attributeType = attribute.getAttributeType();

            if (attributeType.equals(AttributeType.PROPERTY)) {

                if (attributeValueType != null) {

                    if (attributeValueType.equals(AttributeValueType.STRING)
                            || attributeValueType.equals(AttributeValueType.INTEGER)
                            || attributeValueType.equals(AttributeValueType.LONG)
                            || attributeValueType.equals(AttributeValueType.DOUBLE)
                            || attributeValueType.equals(AttributeValueType.OBJECT)
                            || attributeValueType.equals(AttributeValueType.DATE)
                            || attributeValueType.equals(AttributeValueType.BOOLEAN)
                            || attributeValueType.equals(AttributeValueType.ARRAY_STRING)
                            || attributeValueType.equals(AttributeValueType.ARRAY_INTEGER)
                            || attributeValueType.equals(AttributeValueType.ARRAY_LONG)
                            || attributeValueType.equals(AttributeValueType.ARRAY_DOUBLE)
                            || attributeValueType.equals(AttributeValueType.ARRAY_BOOLEAN)
                            || attributeValueType.equals(AttributeValueType.ARRAY_OBJECT)) {
                    	
                    	checkAttributeInequality(attribute);
                    }

                    // ObjectMember ?????? ??????
                    if(attributeValueType.equals(AttributeValueType.ARRAY_OBJECT)
                    		|| attributeValueType.equals(AttributeValueType.OBJECT)) {
                    	if(ValidateUtil.isEmptyData(attribute.getObjectMembers())) {
                    		throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
                                    "Not exists ObjectMember. " + "name=" + name + ", attributeType=" + attributeType + ", attributeValueType=" + attributeValueType);
                    	}

                    } else {
                    	if(attribute.getObjectMembers() != null) {
                    		throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
                    				"'" + attributeValueType.getCode() + "' type cannot have ObjectMember. " + "name=" + name);
                    		
                    	}
                    }

                    // arrayObject ??? ?????? ?????? objectMember??? array ????????? valueType??? ??? ??? ??????
                    // rdb ?????? ????????? ????????????
                    if(attributeValueType.equals(AttributeValueType.ARRAY_OBJECT)) {
                    	checkArrayAttribute(attribute.getObjectMembers());
                    }
                    continue;
                }

            } else if (attributeType.equals(AttributeType.RELATIONSHIP)) {

                if (attributeValueType != null && attributeValueType.equals(AttributeValueType.STRING)) {
                    continue;
                }
            } else if (attributeValueType != null && attributeType.equals(AttributeType.GEO_PROPERTY)) {
                if (attributeValueType.equals(AttributeValueType.GEO_JSON)) {
                    continue;
                }
            }
            throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Mismatch attributeType and valueType. " + "name=" + name + ", attributeType=" + attributeType + ", attributeValueType=" + attributeValueType);
        }
    }

    /**
     * ArrayObject ????????? Array????????? valueType??? ???????????? ??????
     * @param objectMemberList ArrayObject ?????? objectMember
     */
    private void checkArrayAttribute(List<ObjectMember> objectMemberList) {

    	if(objectMemberList == null || objectMemberList.size() == 0) {
    		return;
    	}

    	for(ObjectMember objectMember : objectMemberList) {
    		if(objectMember.getValueType() == AttributeValueType.ARRAY_BOOLEAN
    				|| objectMember.getValueType() == AttributeValueType.ARRAY_DOUBLE
    				|| objectMember.getValueType() == AttributeValueType.ARRAY_INTEGER
    				|| objectMember.getValueType() == AttributeValueType.ARRAY_LONG
    				|| objectMember.getValueType() == AttributeValueType.ARRAY_OBJECT
    				|| objectMember.getValueType() == AttributeValueType.ARRAY_STRING) {
    			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, 
    					"Not supported ArrayObject in array attributeValueType. name=" + objectMember.getName());
    		}
    	}
    }


    private boolean alreadyProcessByOtherInstance(String requestId, Date eventTime, DataModelBaseVO retrieveDataModelBaseVO) {
		// ??????????????? ?????? ?????? IngestInterface ?????????????????? DB ?????? ????????? ??????
    	if(requestId.equals(retrieveDataModelBaseVO.getProvisioningRequestId())
    			&& eventTime.getTime() >= retrieveDataModelBaseVO.getProvisioningEventTime().getTime()) {
    		return true;
    	}
    	return false;
	}
    
    
    private void checkAttributeNameByContext(List<Attribute> attributes, Map<String, String> contextMap) {
		for(Attribute attribute : attributes) {

			List<Attribute> childAttributes = attribute.getChildAttributes();
			if(childAttributes != null && childAttributes.size() > 0) {
				checkAttributeNameByContext(childAttributes, contextMap);
			}

			if(contextMap.get(attribute.getName()) == null) {
				throw new BadRequestException(ErrorCode.INVALID_PARAMETER,
						"Invalid attribute name. Not exists '" + attribute.getName() + "' in @context.");
			}
		}
	}

    private void setAttributeContextUri(List<Attribute> attributes, Map<String, String> contextMap) {
		for(Attribute attribute : attributes) {
			String attributeContextUri = contextMap.get(attribute.getName());
			
			List<Attribute> childAttributes = attribute.getChildAttributes();
			if(childAttributes != null && childAttributes.size() > 0) {
				setAttributeContextUri(childAttributes, contextMap);
			}

			if(attributeContextUri != null) {
				attribute.setAttributeUri(attributeContextUri);
			}
		}
	}

	public static MultiValueMap<String, String> getDefaultHeaders() {
        MultiValueMap<String, String> headerMap = new LinkedMultiValueMap<>();
        headerMap.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return headerMap;
    }
	
	public void setDataModelDAO(DataModelDAO dataModelDAO) {
		this.dataModelDAO = dataModelDAO;
	}
}
