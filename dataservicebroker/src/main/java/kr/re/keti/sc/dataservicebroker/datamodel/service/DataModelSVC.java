package kr.re.keti.sc.dataservicebroker.datamodel.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeValueType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.BigDataStorageType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DefaultAttributeKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.ErrorCode;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.PropertyKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.StorageType;
import kr.re.keti.sc.dataservicebroker.common.exception.BadRequestException;
import kr.re.keti.sc.dataservicebroker.common.exception.BaseException;
import kr.re.keti.sc.dataservicebroker.datafederation.service.DataFederationService;
import kr.re.keti.sc.dataservicebroker.datamodel.DataModelManager;
import kr.re.keti.sc.dataservicebroker.datamodel.dao.DataModelDAO;
import kr.re.keti.sc.dataservicebroker.datamodel.service.hbase.HBaseTableSVC;
import kr.re.keti.sc.dataservicebroker.datamodel.sqlprovider.BigdataTableSqlProvider;
import kr.re.keti.sc.dataservicebroker.datamodel.sqlprovider.RdbTableSqlProvider;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.Attribute;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelBaseVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelCacheVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelStorageMetadataVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.ObjectMember;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.UpdateDataModelProcessVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.UpdateDataModelProcessVO.AttributeUpdateProcessType;
import kr.re.keti.sc.dataservicebroker.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataModelSVC {
	
    private DataModelManager dataModelManager;
	private DataModelRetrieveSVC dataModelRetrieveSVC;
    private RdbTableSqlProvider rdbDataModelSqlProvider;
	private BigdataTableSqlProvider bigDataTableSqlProvider;
	private BigdataTableSqlProvider bigdataDataModelSqlProvider;
    private DataModelDAO dataModelDAO;
    private HBaseTableSVC hBaseTableSVC;
    private ObjectMapper objectMapper;
    private DataFederationService dataFederationService;
	
    public static final String URI_PATTERN_CREATE_DATA_MODEL = "/datamodels";
    public static final Pattern URI_PATTERN_DATA_MODEL = Pattern.compile("/datamodels/(?<id>.+)");

	public DataModelSVC(
			DataModelManager dataModelManager,
			DataModelRetrieveSVC dataModelRetrieveSVC,
			RdbTableSqlProvider rdbDataModelSqlProvider,
			BigdataTableSqlProvider bigDataTableSqlProvider,
			BigdataTableSqlProvider bigdataDataModelSqlProvider,
			DataModelDAO dataModelDAO,
			HBaseTableSVC hBaseTableSVC,
			ObjectMapper objectMapper,
			DataFederationService dataFederationService
	) {
		this.dataModelManager = dataModelManager;
		this.dataModelRetrieveSVC = dataModelRetrieveSVC;
		this.rdbDataModelSqlProvider = rdbDataModelSqlProvider;
		this.bigDataTableSqlProvider = bigDataTableSqlProvider;
		this.bigdataDataModelSqlProvider = bigdataDataModelSqlProvider;
		this.dataModelDAO = dataModelDAO;
		this.hBaseTableSVC = hBaseTableSVC;
		this.objectMapper = objectMapper;
		this.dataFederationService = dataFederationService;
	}

	public enum DbOperation {
    	ADD_COLUMN,
    	DROP_COLUMN,
    	ADD_NOT_NULL,
    	DROP_NOT_NULL,
    	ALTER_COLUMN_TYPE,
    	ALTER_COLUMN_TYPE_AND_ADD_NOT_NULL,
    	ALTER_COLUMN_TYPE_AND_DROP_NOT_NULL;
    }


    /**
     * ????????? ?????? ?????? Provisioning ??????
     * @param to ????????? ?????? ?????? ?????? url
     * @param requestBody ?????? Body
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     */
    public void processCreate(String to, String requestBody, String requestId, Date eventTime) {

    	if(URI_PATTERN_CREATE_DATA_MODEL.equals(to)) {
    		createDataModel(requestBody, requestId, eventTime);
    		
    	// 404
    	} else {
    		throw new BadRequestException(ErrorCode.NOT_EXIST_ID);
    	}
    }

    /**
     * ????????? ?????? ??????
     * @param requestBody ?????? Body
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     * @throws BaseException
     */
    private void createDataModel(String requestBody, String requestId, Date eventTime) throws BaseException {
    	// 1. ?????? ????????? ??????
    	DataModelVO dataModelVO = null;
		try {
			dataModelVO = objectMapper.readValue(requestBody, DataModelVO.class);
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Parameter. body=" + requestBody);
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

		DataModelBaseVO retrieveDataModelBaseVO = dataModelRetrieveSVC.getDataModelBaseVOById(dataModelVO.getId());

        if (retrieveDataModelBaseVO != null) {
        	
        	// 4. ??????????????? ?????? ?????? DataServiceBroker ?????????????????? DB ?????? ????????? ??????
			if(alreadyProcessByOtherInstance(requestId, eventTime, retrieveDataModelBaseVO)) {
	        	// ?????? Instance?????? DB ???????????? ?????? ?????????????????? ????????? ???????????? ?????? ??????
	            dataModelManager.putDataModelCache(retrieveDataModelBaseVO);
	            return;
	        } else {
        		// ?????? ??????????????? ???????????? ??????
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
        dataModelBaseVO.setProvisioningRequestId(requestId);
        dataModelBaseVO.setProvisioningEventTime(eventTime);
        try {
        	dataModelBaseVO.setDataModel(objectMapper.writeValueAsString(dataModelVO));
        	dataModelBaseVO.setStorageMetadata(objectMapper.writeValueAsString(dataModelManager.createDataModelStorageMetadata(dataModelVO, null, null)));
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.UNKNOWN_ERROR, "DataModel parsing error. body=" + requestBody);
		}

        dataModelDAO.createDataModelBaseVO(dataModelBaseVO);

        // 6. Cache ?????? ??????
        dataModelManager.putDataModelCache(dataModelBaseVO);
        
        // 7. Csource Upsert
        if(dataFederationService.enableFederation()) {
        	dataFederationService.registerCsource();
        }
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
    

    /**
     * ??????????????? ?????? DDL ??????
     * @param ddl ????????? ?????? ?????? ????????? DDL
     * @param storageType Storage ??????
     */
    public void executeDdl(String ddl, StorageType storageType) {

    	if(storageType == null) {
    		storageType = StorageType.RDB;
    	}

    	log.info("DataModel executeDdl. storageType=" + storageType + ", ddl=" + ddl);

    	try {
    		dataModelDAO.executeDdl(ddl, storageType);
    	} catch(Exception e) {
    		log.error("DataModel execute DDL ERROR. SQL=" + ddl, e);
    		throw new BadRequestException(ErrorCode.CREATE_ENTITY_TABLE_ERROR, 
    				"execute DDL ERROR. ddl=" + ddl, e);
    	}
    }

    /**
     * ????????? ?????? ?????? Update
     * @param dataModelBaseVO
     * @return
     */
    public int fullUpdateDataModel(DataModelBaseVO dataModelBaseVO) {
        return dataModelDAO.updateDataModelBase(dataModelBaseVO);
    }


    /**
     * ????????? ?????? ??????
     * @param to ????????? ?????? ?????? ?????? url
     * @param requestBody ?????? Body
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     * @throws BaseException
     */
    public void processUpdate(String to, String requestBody, String requestId, Date eventTime) throws BaseException {
    	Matcher matcherForUpdate = URI_PATTERN_DATA_MODEL.matcher(to);

		if(matcherForUpdate.find()) {
			String id = matcherForUpdate.group("id");

			updateDataModel(id, requestBody, requestId, eventTime);
			
	    // 404
		} else {
			throw new BadRequestException(ErrorCode.NOT_EXIST_ID);
		}
    }

    /**
     * ????????? ?????? ??????
     * @param dataModelId ??????????????? ?????????
     * @param requestBody ?????? Body
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     */
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

		// 4. ??? ???????????? ?????? ?????? CREATE (UPSERT ??????)
        DataModelBaseVO retrieveDataModelBaseVO = dataModelRetrieveSVC.getDataModelBaseVOById(dataModelId);
        if (retrieveDataModelBaseVO == null) {
        	log.info("Create(Upsert) DataModel. requestId={}, requestBody={}", requestId, requestBody);
        	createDataModel(requestBody, requestId, eventTime);
        	return;
        }

        // 5. ??????????????? ?????? ?????? DataServiceBroker ?????????????????? DB ?????? ????????? ??????
 		if(alreadyProcessByOtherInstance(requestId, eventTime, retrieveDataModelBaseVO)) {
         	// ?????? Instance?????? DB ???????????? ?????? ?????????????????? ????????? ???????????? ?????? ??????
             dataModelManager.putDataModelCache(retrieveDataModelBaseVO);
             return;
        }

 		// 6. ??????????????? ??????????????? ?????? ???????????? ???/??? ?????? ??????
 		DataModelVO beforeDataModelVO = null;
 		DataModelVO afterDataModelVO = requestDataModelVO;
 		DataModelStorageMetadataVO beforeStorageMetadataVO = null;
 		DataModelStorageMetadataVO afterStorageMetadataVO = null;
        try {
        	beforeDataModelVO = objectMapper.readValue(retrieveDataModelBaseVO.getDataModel(), DataModelVO.class);
        	if(!ValidateUtil.isEmptyData(retrieveDataModelBaseVO.getStorageMetadata())) {
        		beforeStorageMetadataVO = objectMapper.readValue(retrieveDataModelBaseVO.getStorageMetadata(), DataModelStorageMetadataVO.class);
        	} else {
        		// ?????? ????????? ?????? DB ??? storageMetadata ??? ?????? ?????? ?????? ??????
        		beforeStorageMetadataVO = dataModelManager.createDataModelStorageMetadata(beforeDataModelVO, null, retrieveDataModelBaseVO.getCreatedStorageTypes());
        	}

        	afterStorageMetadataVO = dataModelManager.createDataModelStorageMetadata(afterDataModelVO, beforeStorageMetadataVO, retrieveDataModelBaseVO.getCreatedStorageTypes());

        } catch (IOException e) {
			throw new BadRequestException(ErrorCode.INVALID_DATAMODEL,
	                "datamodel parsing error. datamodel=" + retrieveDataModelBaseVO.getDataModel());
		}
        
        // 7. ???????????? ???????????? ?????? dataModel ??? ?????? ?????????????????? dataModel ?????? ???????????? DDL ?????? ??? ??????
        // BigData ??? RDB ?????? ?????? ??????/????????????/?????? DDL ?????? ??? ??????
        updateStorage(dataModelId, beforeDataModelVO, beforeStorageMetadataVO, afterDataModelVO, afterStorageMetadataVO, retrieveDataModelBaseVO.getCreatedStorageTypes());
        
        // 8. dataModel ?????? ????????????
        DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
        dataModelBaseVO.setId(dataModelId);
        dataModelBaseVO.setName(afterDataModelVO.getName());
        dataModelBaseVO.setEnabled(true);
        dataModelBaseVO.setProvisioningRequestId(requestId);
        dataModelBaseVO.setProvisioningEventTime(eventTime);
        dataModelBaseVO.setCreatedStorageTypes(retrieveDataModelBaseVO.getCreatedStorageTypes());
        try {
        	dataModelBaseVO.setDataModel(objectMapper.writeValueAsString(requestDataModelVO));
        	dataModelBaseVO.setStorageMetadata(objectMapper.writeValueAsString(afterStorageMetadataVO));
		} catch (IOException e) {
			throw new BadRequestException(ErrorCode.UNKNOWN_ERROR, "DataModel parsing error. body=" + requestBody);
		}
        dataModelDAO.updateDataModelBase(dataModelBaseVO);

        // 8. Cache ?????? ?????????
        dataModelManager.putDataModelCache(dataModelBaseVO);
        
        // 9. Csource Upsert
        if(dataFederationService.enableFederation()) {
        	dataFederationService.registerCsource();
        }
    }
    

    /**
     * ???????????? ???/??? ????????? ?????? ?????? ?????? Table ?????? DDL ?????? ??? ??????
     * @param beforeDataModelVO ???????????? ??? ????????? ??????
     * @param afterDataModelVO ???????????? ??? ????????? ??????
     */
    private void updateStorage(String id, 
    							DataModelVO beforeDataModelVO, DataModelStorageMetadataVO beforeStorageMetadataVO, 
    							DataModelVO afterDataModelVO, DataModelStorageMetadataVO afterStorageMetadataVO, 
    							List<BigDataStorageType> createdStorageTypes) {

    	// DB DDL ?????? ?????? ????????? ?????? ?????? ?????? VO
        List<UpdateDataModelProcessVO> updateDataModelProcessVOList = new ArrayList<>();

        // 1. ???????????? ???/??? ?????????????????? ???????????? ????????? Attribute ??? ??? ?????? Attribute??? ???????????? VO ??????
        for(Attribute beforeAttribute : beforeDataModelVO.getAttributes()) {
        	Attribute doUpdateAttribute = null;
        	for(Attribute afterAttribute : afterDataModelVO.getAttributes()) {
        		if(beforeAttribute.getName().equals(afterAttribute.getName())) {
        			doUpdateAttribute = afterAttribute;
        			break;
        		}
        	}

        	UpdateDataModelProcessVO updateDataModelProcessVO = new UpdateDataModelProcessVO();
        	updateDataModelProcessVO.setBeforeAttribute(beforeAttribute);
    		updateDataModelProcessVO.setAfterAttribute(doUpdateAttribute);
        	updateDataModelProcessVO.setAttributeName(beforeAttribute.getName());
        	if(doUpdateAttribute == null) {
        		updateDataModelProcessVO.setAttributeUpdateProcessType(AttributeUpdateProcessType.REMOVE_ATTRIBUTE);
        	} else {
        		updateDataModelProcessVO.setAttributeUpdateProcessType(AttributeUpdateProcessType.EXISTS_ATTRIBUTE);
        	}
        	updateDataModelProcessVOList.add(updateDataModelProcessVO);
        }
        
        // 2. ???????????? ???/??? ?????????????????? ???????????? ?????? ????????? Attribute??? ???????????? VO ??????
        for(Attribute afterAttribute : afterDataModelVO.getAttributes()) {
        	boolean isExists = false;
        	for(Attribute beforeAttribute : beforeDataModelVO.getAttributes()) {
        		if(beforeAttribute.getName().equals(afterAttribute.getName())) {
        			isExists = true;
        			break;
        		}
        	}
        	if(!isExists) {
        		UpdateDataModelProcessVO updateDataModelProcessVO = new UpdateDataModelProcessVO();
        		updateDataModelProcessVO.setAttributeUpdateProcessType(AttributeUpdateProcessType.NEW_ATTRIBUTE);
        		updateDataModelProcessVO.setAfterAttribute(afterAttribute);
            	updateDataModelProcessVO.setAttributeName(afterAttribute.getName());
            	updateDataModelProcessVOList.add(updateDataModelProcessVO);
        	}
        }

        // 3. RDB??? BIGDATA ??? ?????? ????????? SQL ?????? ??? ???????????? ?????? ??? ??????
        boolean useRdb = false;
        boolean useBigData = false;

		if(useBigDataStorage(createdStorageTypes)) {
			useBigData = true;
        } else if(useRdbStorage(createdStorageTypes)) {
        	useRdb = true;
        }

        // 4. BigData DDL ?????? ??? ??????
        if(useBigData) {
			StringBuilder ddlBuilder = new StringBuilder();
			// 5-1. ?????? ADD / ALTER / DROP DDL ??????
			for(UpdateDataModelProcessVO updateDataModelProcessVO : updateDataModelProcessVOList) {
				Attribute beforeAttribute = updateDataModelProcessVO.getBeforeAttribute();
				Attribute afterAttribute = updateDataModelProcessVO.getAfterAttribute();
				String attributeDdl = null;
				switch(updateDataModelProcessVO.getAttributeUpdateProcessType()) {
					case NEW_ATTRIBUTE:  {
						attributeDdl = bigDataTableSqlProvider.generateAddOrDropColumnDdl(id, afterAttribute, DbOperation.ADD_COLUMN);
						break;
					} case EXISTS_ATTRIBUTE: {
						attributeDdl = bigDataTableSqlProvider.generateAlterTableColumnDdl(id, beforeAttribute, afterAttribute);
						break;
					} case REMOVE_ATTRIBUTE: {
						throw new UnsupportedOperationException("Hive not supported drop column");
					} default: {
						break;
					}
				}
				if(!ValidateUtil.isEmptyData(attributeDdl)) {
					ddlBuilder.append(attributeDdl);
				}
			}
			// 5-2. alter Index DDL ??????
			String alterIndexDdl = rdbDataModelSqlProvider.generateIndexDdl(afterDataModelVO, afterStorageMetadataVO,
					beforeDataModelVO.getIndexAttributeNames(), afterDataModelVO.getIndexAttributeNames());
			if(!ValidateUtil.isEmptyData(alterIndexDdl)) {
				ddlBuilder.append(alterIndexDdl);
			}
			// 5-3. DDL ??????
			if(!ValidateUtil.isEmptyData(ddlBuilder.toString())) {
				String[] splitQuery = ddlBuilder.toString().split("---EOS---");
				for (String query : splitQuery) {
					if (StringUtils.isNotEmpty(query)) {
						executeDdl(query, StorageType.HIVE);
					}
				}
			}
		}

        // 5. RDB DDL ?????? ??? ??????
        if(useRdb) {

        	StringBuilder ddlBuilder = new StringBuilder();

        	// 5-1. ?????? ADD / ALTER / DROP DDL ??????
//        	for(UpdateDataModelProcessVO updateDataModelProcessVO : updateDataModelProcessVOList) {
//
//        		Attribute beforeAttribute = updateDataModelProcessVO.getBeforeAttribute();
//				Attribute afterAttribute = updateDataModelProcessVO.getAfterAttribute();
//
//				String attributeDdl = null;
//
//        		switch(updateDataModelProcessVO.getAttributeUpdateProcessType()) {
//        			case NEW_ATTRIBUTE:  {
//        				attributeDdl = rdbDataModelSqlProvider.generateAddOrDropColumnDdl(id, afterAttribute, beforeStorageMetadataVO, DbOperation.ADD_COLUMN);
//        				break;
//        			} case EXISTS_ATTRIBUTE: {
//        				attributeDdl = rdbDataModelSqlProvider.generateAlterTableColumnDdl(id, beforeAttribute, beforeStorageMetadataVO, afterAttribute, afterStorageMetadataVO);
//        				break;
//        			} case REMOVE_ATTRIBUTE: {
//        				attributeDdl = rdbDataModelSqlProvider.generateAddOrDropColumnDdl(id, beforeAttribute, beforeStorageMetadataVO, DbOperation.DROP_COLUMN);
//        				break;
//        			} default: {
//        				break;
//        			}
//        		}
//
//        		if(!ValidateUtil.isEmptyData(attributeDdl)) {
//        			ddlBuilder.append(attributeDdl);	
//        		}
//        	}

        	String attributeDdl = rdbDataModelSqlProvider.generateAlterTableColumnDdl(id, beforeStorageMetadataVO, afterStorageMetadataVO);
        	if(!ValidateUtil.isEmptyData(attributeDdl)) {
    			ddlBuilder.append(attributeDdl);	
    		}

        	// 5-2. alter Index DDL ??????
        	String alterIndexDdl = rdbDataModelSqlProvider.generateIndexDdl(afterDataModelVO, afterStorageMetadataVO,
    				beforeDataModelVO.getIndexAttributeNames(), afterDataModelVO.getIndexAttributeNames());
        	if(!ValidateUtil.isEmptyData(alterIndexDdl)) {
        		ddlBuilder.append(alterIndexDdl);
        	}

        	// 5-3. DDL ??????
            if(!ValidateUtil.isEmptyData(ddlBuilder.toString())) {
            	executeDdl(ddlBuilder.toString(), StorageType.RDB);
            }
        }
	}



    /**
     * ????????? ?????? ??????
     * @param to ????????? ?????? ?????? ?????? url
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     * @throws BaseException
     */
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

    /**
     * ????????? ?????? ??????
     * @param id ??????????????? ?????????
     */
	private void deleteDataModel(String id) {
		
		// 1. ???????????? ?????? ??? ????????? ??????
		DataModelBaseVO retrieveDataModelBaseVO = dataModelRetrieveSVC.getDataModelBaseVOById(id);

		if (retrieveDataModelBaseVO != null) {
			
			List<BigDataStorageType> createdStorageTypes = retrieveDataModelBaseVO.getCreatedStorageTypes();

			if(createdStorageTypes != null) {
				
				for(BigDataStorageType bigDataStorageType : createdStorageTypes) {
					if(bigDataStorageType == BigDataStorageType.RDB) {
						// DROP TABLE DDL ??????
						String rdbDropTableDdl = rdbDataModelSqlProvider.generateDropTableDdl(id);
						// DROP TABLE DDL ??????
						try {
							executeDdl(rdbDropTableDdl, StorageType.RDB);
						} catch(Exception e) {
							log.warn("deleteDataModel error.", e);
						}

					} else {
						// DROP TABLE DDL ??????
						String bigdataDropTableDdl = bigdataDataModelSqlProvider.generateDropTableDdl(id);

						// DROP TABLE DDL ??????
						try {
							String[] sqls = bigdataDropTableDdl.split("FORSPLIT");
					
					        for (String sql : sqls) {
					        	// TODO: HBase ???????????? ??????
//					            List<String> tokens = Arrays.asList(sql.split(" "));
//					            String tableName = tokens.get(tokens.size()-1).replace(";","").replace("\n", "");
//					            hBaseTableSVC.dropTable(tableName);
					            executeDdl(sql, StorageType.HIVE);
					        }
						} catch(Exception e) {
							log.warn("deleteDataModel error.", e);
						}
					}
				}
			}

			// 4. dataModel ?????? ??????
			DataModelBaseVO dataModelBaseVO = new DataModelBaseVO();
			dataModelBaseVO.setId(id);
			dataModelBaseVO.setType(id.substring(id.lastIndexOf("/")+1, id.length()));

			dataModelDAO.deleteDataModelBaseVO(dataModelBaseVO);
		}

		// 5. ???????????? ??????
		dataModelManager.removeDataModelCache(id);

		// 6. Csource Upsert
        if(dataFederationService.enableFederation()) {
        	dataFederationService.registerCsource();
        }
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

    /**
     * ???????????? ?????? ?????? ??????
     * @param bigDataStorageTypeList
     * @return
     */
    private boolean useBigDataStorage(List<BigDataStorageType> bigDataStorageTypeList) {
    	if(bigDataStorageTypeList == null || bigDataStorageTypeList.size() == 0) {
    		return false;
    	}

    	for(BigDataStorageType bigDataStorageType : bigDataStorageTypeList) {
    		if(bigDataStorageType == BigDataStorageType.HIVE
    				|| bigDataStorageType == BigDataStorageType.HBASE) {
    			return true;
    		}
    	}
    	return false;
    }
    
    /**
     * RDB ?????? ?????? ??????
     * @param bigDataStorageTypeList
     * @return
     */
    private boolean useRdbStorage(List<BigDataStorageType> bigDataStorageTypeList) {
    	if(bigDataStorageTypeList == null || bigDataStorageTypeList.size() == 0) {
    		return false;
    	}

    	for(BigDataStorageType bigDataStorageType : bigDataStorageTypeList) {
    		if(bigDataStorageType == BigDataStorageType.RDB) {
    			return true;
    		}
    	}
    	return false;
    }

    /**
     * HTTP ?????? Provisioning ????????? ?????? Instance?????? ?????? ?????? ?????? ??? ?????? ??????
     * @param requestId Provisioning Request Id
     * @param eventTime Provisioning Request Time
     * @param retrieveDataModelBaseVO DB?????? ????????? ??????
     * @return
     */
    private boolean alreadyProcessByOtherInstance(String requestId, Date eventTime, DataModelBaseVO retrieveDataModelBaseVO) {
		// ??????????????? ?????? ?????? DataServiceBroker ?????????????????? DB ?????? ????????? ??????
    	if(retrieveDataModelBaseVO == null) {
    		return false;
    	}

    	if(requestId.equals(retrieveDataModelBaseVO.getProvisioningRequestId())
    			&& eventTime.getTime() >= retrieveDataModelBaseVO.getProvisioningEventTime().getTime()) {
    		return true;
    	}
    	return false;
	}

    public int updateDataModelStorage(DataModelBaseVO dataModelBaseVO) {
        return dataModelDAO.updateDataModelStorage(dataModelBaseVO);
    }

	public void setDataModelDAO(DataModelDAO dataModelDAO) {
		this.dataModelDAO = dataModelDAO;
	}

}
