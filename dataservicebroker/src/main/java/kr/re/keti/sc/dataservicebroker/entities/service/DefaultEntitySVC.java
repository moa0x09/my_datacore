package kr.re.keti.sc.dataservicebroker.entities.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.SerializationUtils;
import org.geojson.GeoJsonObject;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.re.keti.sc.dataservicebroker.common.code.Constants;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeValueType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.BigDataStorageType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DefaultAttributeKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.ErrorCode;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.GeoJsonValueType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.GeometryType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.Operation;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.OperationOption;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.PropertyKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.RetrieveOptions;
import kr.re.keti.sc.dataservicebroker.common.exception.BaseException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdBadRequestException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdInternalServerErrorException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdNoExistTypeException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdResourceNotFoundException;
import kr.re.keti.sc.dataservicebroker.common.vo.CommonEntityVO;
import kr.re.keti.sc.dataservicebroker.common.vo.EntityProcessVO;
import kr.re.keti.sc.dataservicebroker.common.vo.IngestMessageVO;
import kr.re.keti.sc.dataservicebroker.common.vo.ProcessResultVO;
import kr.re.keti.sc.dataservicebroker.common.vo.QueryVO;
import kr.re.keti.sc.dataservicebroker.common.vo.entities.DynamicEntityDaoVO;
import kr.re.keti.sc.dataservicebroker.common.vo.entities.DynamicEntityFullVO;
import kr.re.keti.sc.dataservicebroker.datamodel.DataModelManager;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.Attribute;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelCacheVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelStorageMetadataVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.ObjectMember;
import kr.re.keti.sc.dataservicebroker.entities.dao.EntityDAOInterface;
import kr.re.keti.sc.dataservicebroker.entities.vo.EntityDataModelVO;
import kr.re.keti.sc.dataservicebroker.util.CommonParamUtil;
import kr.re.keti.sc.dataservicebroker.util.DateUtil;
import kr.re.keti.sc.dataservicebroker.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

/**
 * Entity ?????? ????????? ?????????
 */
@Slf4j
public abstract class DefaultEntitySVC implements EntitySVCInterface<DynamicEntityFullVO, DynamicEntityDaoVO> {

    protected abstract String getTableName(DataModelCacheVO dataModelCacheVO);

    protected abstract BigDataStorageType getStorageType();

    public abstract void setEntityDAOInterface(EntityDAOInterface<DynamicEntityDaoVO> entityDAO);

    protected EntityDAOInterface<DynamicEntityDaoVO> entityDAO;
    @Autowired
    protected DataModelManager dataModelManager;
    @Autowired
    protected EntityDataModelSVC entityDataModelSVC;
    @Autowired
    protected ObjectMapper objectMapper;
    @Value("${entity.default.context-uri}")
    private String defaultContextUri;
    @Value("${entity.validation.id-pattern.enabled:true}")
    private Boolean validateIdPatternEnabled;

    /**
     * Entity ????????? Operation ??? ?????? ??????
     *
     * @param requestMessageVOList ?????????????????????VO?????????
     * @return ???????????? ??? ????????????VO?????????
     */
    @Override
    public List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> processBulk(List<IngestMessageVO> requestMessageVOList) {

        // 1. ???????????? VO -> ????????? ?????? VO ??? ??????
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> processVOList = requestMessageVOToProcessVO(requestMessageVOList);

        // 2. ???????????? ????????? ??? ?????? ??????
        processValidate(processVOList);

        // 3. Operation ??? ?????? ??????
        processOperation(processVOList);

        // 4. Entity ??? DataModel ?????? ??????
        storeEntityDataModel(processVOList);

        // 5. Operation ?????? ?????? ?????? ??????
        storeEntityStatusHistory(processVOList);

        return processVOList;
    }

    /**
     * ???????????? VO -> ?????? ????????? ?????? VO ??????
     *
     * @param requestMessageVOList ?????? ?????? VO
     * @return List<OffStreetParkingProcessVO> ????????? ?????? VO ?????????
     */
    @Override
    public List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> requestMessageVOToProcessVO(List<IngestMessageVO> requestMessageVOList) {

        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList = new ArrayList<>();

        for (IngestMessageVO ingestMessageVO : requestMessageVOList) {

            // 1. ????????? ?????? ?????? ?????? ??? ?????? ?????? ????????? ????????? ?????? ??????
            EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO = new EntityProcessVO<>();

            String content = ingestMessageVO.getContent();
            Operation opertaion = ingestMessageVO.getOperation();

            // NGSI-LD ?????? ?????? ??? ??????
            String attrId = CommonParamUtil.extractAttrId(opertaion, ingestMessageVO.getTo());

            entityProcessVO.setDatasetId(ingestMessageVO.getDatasetId());
            entityProcessVO.setContent(content);
            entityProcessVO.setOperation(opertaion);
            entityProcessVO.setOperationOptions(ingestMessageVO.getOperationOptions());

            // 2. ?????? content -> entityFullVO ??????
            DynamicEntityFullVO entityFullVO = null;
            try {

                // Partial Attrbute Update ?????? ?????? ??????
                // ????????? Property??? ????????? , geo, rel ?????? ?????????
                if (opertaion == Operation.PARTIAL_ATTRIBUTE_UPDATE) {
                    HashMap<String, Object> params = objectMapper.readValue(content, new TypeReference<HashMap<String, Object>>() {});
//        	        params.put(PropertyKey.TYPE.getCode(), DataServiceBrokerCode.AttributeType.PROPERTY.getCode());
//        	        HashMap<String, Object> convertedHashMap = new HashMap<>();
//        	        convertedHashMap.put(attrId, params);
                    content = objectMapper.writeValueAsString(params);
                }

                entityFullVO = deserializeContent(content);

                // eventTime??? ?????? ?????? ?????? ?????? ??????
                Date eventTime = null;
                if (ingestMessageVO.getIngestTime() != null) {
                    eventTime = ingestMessageVO.getIngestTime();
                } else {
                    eventTime = new Date();
                }
                entityFullVO.setCreatedAt(eventTime);
                entityFullVO.setModifiedAt(eventTime);
                if (entityFullVO.getId() == null && ingestMessageVO.getId() != null) {
                    entityFullVO.setId(ingestMessageVO.getId());
                }
                if (entityFullVO.getType() == null && ingestMessageVO.getEntityType() != null) {
                    entityFullVO.setType(ingestMessageVO.getEntityType());
                }
                entityFullVO.setDatasetId(entityProcessVO.getDatasetId());
                entityProcessVO.setEntityId(entityFullVO.getId());

                // contentType??? application/json??? ??????
                if(!ValidateUtil.isEmptyData(ingestMessageVO.getContentType())) {
                    if(ingestMessageVO.getContentType().contains(Constants.APPLICATION_JSON_VALUE)) {
                        // contentType??? application/json??? ?????? @context ????????????
                        if(!ValidateUtil.isEmptyData(entityFullVO.getContext())) {
                            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                    "Invalid Request Content. @context parameter cannot be used when contentType=application/json");
                        }

                        if(!ValidateUtil.isEmptyData(ingestMessageVO.getLinks())) {
                            entityFullVO.setContext(ingestMessageVO.getLinks());
                        }

                        // contentType??? application/ld+json??? ??????
                    } else if(ingestMessageVO.getContentType().contains(Constants.APPLICATION_LD_JSON_VALUE)) {
                        // contentType??? application/ld+json??? ?????? link header ????????????
                        if(!ValidateUtil.isEmptyData(ingestMessageVO.getLinks())) {
                            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                    "Invalid Request Content. Link Header cannot be used when contentType=application/ld+json");
                        }
                    }
                }

                if(entityFullVO.getContext() == null) {
                    entityFullVO.setContext(new ArrayList<>());
                }

                // default context-uri ?????? context ?????? ?????? ?????? ??????
                if(!ValidateUtil.isEmptyData(defaultContextUri)) {
                    entityFullVO.getContext().add(0, defaultContextUri);
                }

                validateEntityId(entityFullVO.getId());

                EntityDataModelVO retrieveEntityDataModelVO = entityDataModelSVC.getEntityDataModelVOById(entityFullVO.getId());
                if (opertaion == Operation.CREATE_ENTITY) {
                    if (retrieveEntityDataModelVO != null) {
                        throw new NgsiLdBadRequestException(ErrorCode.ALREADY_EXISTS,
                                "Invalid Request Content. Already exists entityId=" + entityFullVO.getId());
                    }
                } else if (opertaion == Operation.DELETE_ENTITY
                        || opertaion == Operation.APPEND_ENTITY_ATTRIBUTES
                        || opertaion == Operation.PARTIAL_ATTRIBUTE_UPDATE
                        || opertaion == Operation.UPDATE_ENTITY_ATTRIBUTES
                        || opertaion == Operation.REPLACE_ENTITY_ATTRIBUTES
                        || opertaion == Operation.DELETE_ENTITY_ATTRIBUTES) {
                    if (retrieveEntityDataModelVO == null) {
                        throw new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                                "Invalid Request Content. Not exists entityId=" + entityFullVO.getId());
                    }
                }

                // ??????????????? ????????? ??????
                DataModelCacheVO dataModelCacheVO = null;
                if(retrieveEntityDataModelVO != null) {
                    dataModelCacheVO = dataModelManager.getDataModelVOCacheById(retrieveEntityDataModelVO.getDataModelId());

                    if (entityFullVO.getType() == null || !entityFullVO.getType().endsWith(retrieveEntityDataModelVO.getDataModelType())) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                "Invalid Request Content. Invalid dataModel type. request type=" + entityFullVO.getType()
                                        + ", exists entity type=" + retrieveEntityDataModelVO.getDataModelType()
                                        + ", entityId=" + retrieveEntityDataModelVO.getId()
                                        + ", datasetId=" + retrieveEntityDataModelVO.getDatasetId());
                    }
                }
                if (dataModelCacheVO == null) {
                    dataModelCacheVO = dataModelManager.getDataModelCacheByDatasetId(ingestMessageVO.getDatasetId());
                }
                if (dataModelCacheVO == null) {
                    dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(entityFullVO.getContext(), entityFullVO.getType());
                }
                if (dataModelCacheVO == null) {
                    throw new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR,
                            "Not Found DataModel. datasetId=" + ingestMessageVO.getDatasetId() + ", type=" + entityFullVO.getType());
                }

                entityFullVO.setType(dataModelCacheVO.getDataModelVO().getType());

                // 3. entityFullVO -> DB ????????? daoVO??? ??????
                DynamicEntityDaoVO entityDaoVO = fullVOToDaoVO(entityFullVO, dataModelCacheVO, opertaion);
                entityDaoVO.setAttrId(attrId);

                entityProcessVO.setEntityFullVO(entityFullVO);
                entityProcessVO.setEntityDaoVO(entityDaoVO);
                entityProcessVO.setDataModelCacheVO(dataModelCacheVO);
                entityProcessVOList.add(entityProcessVO);

            } catch (BaseException e) {
                ProcessResultVO processResultVO = new ProcessResultVO();
                processResultVO.setProcessResult(false);
                processResultVO.setException(e);
                processResultVO.setErrorDescription("Content Parsing Error. message=" + e.getMessage());
                entityProcessVO.setProcessResultVO(processResultVO);
                entityProcessVOList.add(entityProcessVO);
                continue;

            } catch (Exception e) {
                ProcessResultVO processResultVO = new ProcessResultVO();
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR, e));
                processResultVO.setErrorDescription("Content Parsing Error. message=" + e.getMessage());
                entityProcessVO.setProcessResultVO(processResultVO);
                entityProcessVOList.add(entityProcessVO);
                continue;
            }
        }

        return entityProcessVOList;
    }

    private void validateEntityId(String entityId) {
        if (ValidateUtil.isEmptyData(entityId)) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Invalid Request Content. Not found 'id'");
        }

        if (validateIdPatternEnabled && !ValidateUtil.isValidUrn(entityId)) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Invalid Request Content. entityId is not in URN format. id=" + entityId);
        }
    }

    /**
     * ???????????? content ???????????? ????????? FullVO ??? DB??? daoVO??? ??????
     *
     * @param dynamicEntityFullVO ???????????? content ???????????? ????????? FullVO
     * @return EntityDaoVO DB??? daoVO
     * @throws BaseException
     */
    public DynamicEntityDaoVO fullVOToDaoVO(DynamicEntityFullVO dynamicEntityFullVO, DataModelCacheVO dataModelCacheVO,
                                            Operation opertaion) throws BaseException {

        try {

            // 1. daoVO ?????? ?????? ??? ????????? ??????
            DynamicEntityDaoVO dynamicEntityDaoVO = new DynamicEntityDaoVO();
            dynamicEntityDaoVO.setContext(dynamicEntityFullVO.getContext());
            dynamicEntityDaoVO.setId(dynamicEntityFullVO.getId());
            dynamicEntityDaoVO.setDatasetId(dynamicEntityFullVO.getDatasetId());
            dynamicEntityDaoVO.setCreatedAt(dynamicEntityFullVO.getCreatedAt());
            dynamicEntityDaoVO.setModifiedAt(dynamicEntityFullVO.getModifiedAt());
            dynamicEntityDaoVO.setEntityType(dynamicEntityFullVO.getType());
            // 2. Dynamic Query ????????? ????????? Meta?????? ??????
            dynamicEntityDaoVO.setDbTableName(this.getTableName(dataModelCacheVO));
            dynamicEntityDaoVO.setDbColumnInfoVOMap(dataModelCacheVO.getDataModelStorageMetadataVO().getDbColumnInfoVOMap());

            // 3. Operation ?????? ??? ????????? ?????? attribute ?????? ?????? ??? Dao?????? ??????
            if(opertaion != Operation.DELETE_ENTITY) {
                List<Attribute> rootAttributes = dataModelCacheVO.getDataModelVO().getAttributes();
                attributeToDynamicDaoVO(dynamicEntityFullVO, dynamicEntityDaoVO, null, rootAttributes, dynamicEntityFullVO.getModifiedAt(), dataModelCacheVO.getDataModelStorageMetadataVO());
                checkInvalidAttribute(dynamicEntityFullVO, rootAttributes);
            }

            return dynamicEntityDaoVO;
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR,
                    "fullVO to daoVO parsing ERROR. entityType=" + dynamicEntityFullVO.getType() + ", id=" + dynamicEntityFullVO.getId(), e);
        }
    }

    /**
     * Operation ?????? ??? ????????? ?????? attribute ?????? ?????? ??? Dao?????? ??????
     *
     * @param dynamicEntityFullVO ???????????? content ???????????? ????????? FullVO
     * @param dynamicEntityDaoVO  FullVO ???????????? ????????? DaoVO (??????????????? attribute ??? flat ?????? ??????)
     * @param parentHierarchyIds  ???????????? attributeId (????????????)
     * @param rootAttributes      RootAttbitues ??????
     * @throws ParseException
     */

    /**
     * Operation ?????? ??? ????????? ?????? attribute ?????? ?????? ??? Dao?????? ??????
     * @param currentEntityVO ???????????? content ???????????? ????????? FullVO
     * @param dynamicEntityDaoVO FullVO ???????????? ????????? DaoVO (??????????????? attribute ??? flat ?????? ??????)
     * @param parentHierarchyIds ???????????? attributeId (????????????)
     * @param rootAttributes RootAttbitues ??????
     * @param eventTime ????????????
     * @param storageMetadataVO dataModel ?????? Storage ?????? ????????????
     * @throws ParseException ????????????
     * @throws NgsiLdBadRequestException BadRequest ??????
     */
    @SuppressWarnings("unchecked")
    private void attributeToDynamicDaoVO(Map<String, Object> currentEntityVO, DynamicEntityDaoVO dynamicEntityDaoVO,
                                         List<String> parentHierarchyIds, List<Attribute> rootAttributes, Date eventTime,
                                         DataModelStorageMetadataVO storageMetadataVO) throws ParseException, NgsiLdBadRequestException {

        if (rootAttributes == null) return;

        Map<String, String> contextMap = dataModelManager.contextToFlatMap(dynamicEntityDaoVO.getContext());

        for (Attribute rootAttribute : rootAttributes) {

            if(!ValidateUtil.isEmptyData(dynamicEntityDaoVO.getContext())) {
                String currentEntityFullUri = contextMap.get(rootAttribute.getName());

                if(ValidateUtil.isEmptyData(currentEntityFullUri)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Invalid Request Content. Not exists attribute in @context. attribute name=" + rootAttribute.getName());
                }

                if (!currentEntityFullUri.equals(rootAttribute.getAttributeUri())) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Invalid Request Content. No match attribute full uri. attribute name={}" + rootAttribute.getName()
                                    + ", dataModel attribute uri=" + rootAttribute.getAttributeUri() + " but ingest attribute uri=" + currentEntityFullUri);
                }
            }

            String attributeKey = null;
            // short name ?????? ??????
            if (currentEntityVO.containsKey(rootAttribute.getName())) {
                attributeKey = rootAttribute.getName();
            } else {
                // full uri??? ??????
                if (currentEntityVO.containsKey(rootAttribute.getAttributeUri())) {
                    attributeKey = rootAttribute.getAttributeUri();
                } else {
                    continue;
                }
            }

            List<String> currentHierarchyIds = new ArrayList<>();
            if (parentHierarchyIds != null && parentHierarchyIds.size() > 0) {
                currentHierarchyIds.addAll(parentHierarchyIds);
            }
            currentHierarchyIds.add(rootAttribute.getName());

            // 1. get attribute 
            Map<String, Object> attribute = null;
            Object attributeValue = null;
            try {
                attributeValue = currentEntityVO.get(attributeKey);
                attribute = (Map<String, Object>) attributeValue;
            } catch (ClassCastException e) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                        "Invalid Request Content. attributeId=" + rootAttribute.getName() +
                                ", valueType=" + rootAttribute.getValueType().getCode() + ", value=" + attributeValue);
            }

            // 2. ????????? ??????
            checkDefaultParam(attribute, attributeKey);

            // 3-1. type??? Property??? ??????
            if (DataServiceBrokerCode.AttributeType.PROPERTY == rootAttribute.getAttributeType()) {

                AttributeValueType valueType = rootAttribute.getValueType();
                // 3-1-1. value type??? ArrayObject??? ??????
                if (valueType == AttributeValueType.ARRAY_OBJECT) {

                    List<Map<String, Object>> arrayObject = null;
                    try {
                        arrayObject = (List<Map<String, Object>>) attribute.get(PropertyKey.VALUE.getCode());
                    } catch (ClassCastException e) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                "Invalid Request Content. attributeId=" + rootAttribute.getName() +
                                        ", valueType=" + rootAttribute.getValueType().getCode() + ", value=" + attribute.get(PropertyKey.VALUE.getCode()));
                    }

                    List<ObjectMember> objectMembers = rootAttribute.getObjectMembers();
                    for (ObjectMember objectMember : objectMembers) {
                        List<Object> objectMemberValueList = null;
                        for (Map<String, Object> object : arrayObject) {

                            Object value = object.get(objectMember.getName());
                            // ?????? ???????????? ????????? ??????
                            checkObjectType(objectMember.getName(), objectMember.getValueType(), value, objectMember);

                            if (value == null) {
                                continue;
                            }

                            if (objectMemberValueList == null) {
                                objectMemberValueList = new ArrayList<>();
                            }

                            if (objectMember.getValueType() == AttributeValueType.DATE) {
                                objectMemberValueList.add(DateUtil.strToDate((String) value));
                            } else {
                                objectMemberValueList.add(value);
                            }
                        }
                        List<String> hierarchyAttributeIds = new ArrayList<>(currentHierarchyIds);
                        hierarchyAttributeIds.add(objectMember.getName());
                        String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttributeIds);
                        dynamicEntityDaoVO.put(id, objectMemberValueList);
                    }

                    // 3-1-2. value type??? Object??? ??????
                } else if (valueType == AttributeValueType.OBJECT) {

                    Map<String, Object> object = null;
                    try {
                        object = (Map<String, Object>) attribute.get(PropertyKey.VALUE.getCode());
                    } catch (ClassCastException e) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                "Invalid Request Content. attributeId=" + rootAttribute.getName() +
                                        ", valueType=" + rootAttribute.getValueType().getCode() + ", value=" + attribute.get(PropertyKey.VALUE.getCode()));
                    }

                    objectTypeParamToDaoVO(currentHierarchyIds, rootAttribute.getObjectMembers(), object, dynamicEntityDaoVO, storageMetadataVO);

                    // 3-1-3. value type??? String, Integer, Double, Date, Boolean, ArrayString, ArrayInteger, ArrayDouble, ArrayBoolean, Object ??? ??????
                } else {
                    Object value = attribute.get(PropertyKey.VALUE.getCode());
                    // ?????? ???????????? ????????? ??????
                    checkObjectType(rootAttribute.getName(), valueType, value, rootAttribute);

                    if (valueType == AttributeValueType.DATE) {
                        String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                        dynamicEntityDaoVO.put(id, DateUtil.strToDate((String) value));
                    } else {
                        String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                        dynamicEntityDaoVO.put(id, value);
                    }
                }

                // 3-2. type??? GeoProperty??? ??????
            } else if (DataServiceBrokerCode.AttributeType.GEO_PROPERTY == rootAttribute.getAttributeType()) {
                Object value = attribute.get(PropertyKey.VALUE.getCode());

                checkGeometryObjectType(value);

                try {
                    String geoJson = objectMapper.writeValueAsString(value);
                    GeoJsonObject object = objectMapper.readValue(geoJson, GeoJsonObject.class);
                    List<String> ids = dataModelManager.getColumnNamesByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                    for(String id : ids) {
                        dynamicEntityDaoVO.put(id, geoJson);
                    }
                } catch (JsonProcessingException e) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Invalid Request Content. GeoJson parsing ERROR. attributeId=" + rootAttribute.getName() +
                                    ", valueType=" + AttributeValueType.GEO_JSON.getCode() + ", value=" + value);
                }
            } else if (DataServiceBrokerCode.AttributeType.RELATIONSHIP == rootAttribute.getAttributeType()) {
                Object object = attribute.get(PropertyKey.OBJECT.getCode());
                // ?????? ???????????? ????????? ??????
                checkObjectType(rootAttribute.getName(), AttributeValueType.STRING, object, rootAttribute);

                String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                dynamicEntityDaoVO.put(id, object);
            }

            // 3-3. ObservedAt ??? ????????? Attribute ??? ??????
            if (rootAttribute.getHasObservedAt() != null && rootAttribute.getHasObservedAt()) {
                Object value = attribute.get(PropertyKey.OBSERVED_AT.getCode());
                if (value != null) {
                    // Date ???????????? ????????? ??????
                    if (!ValidateUtil.isDateObject(value)) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                                "Invalid Request Content. attributeId=" + rootAttribute.getName() + "." + PropertyKey.OBSERVED_AT.getCode() +
                                        ", valueType=" + AttributeValueType.DATE.getCode() + ", value=" + value);
                    }

                    List<String> hierarchyAttributeIds = new ArrayList<>(currentHierarchyIds);
                    hierarchyAttributeIds.add(PropertyKey.OBSERVED_AT.getCode());
                    String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttributeIds);
                    dynamicEntityDaoVO.put(id, DateUtil.strToDate((String) value));
                }
            }

            // 3-4. has property or relationship ??? ???????????? ??????
            if (rootAttribute.getChildAttributes() != null) {
                attributeToDynamicDaoVO(attribute, dynamicEntityDaoVO, currentHierarchyIds, rootAttribute.getChildAttributes(), eventTime, storageMetadataVO);
            }

            // 3-5. unitCode ??? ????????? Attribute ??? ??????
            if (rootAttribute.getHasUnitCode() != null && rootAttribute.getHasUnitCode()) {
                Object value = attribute.get(PropertyKey.UNIT_CODE.getCode());
                if (value != null) {
                    checkObjectType(rootAttribute.getName(), AttributeValueType.STRING, value, rootAttribute);

                    List<String> hierarchyAttributeIds = new ArrayList<>(currentHierarchyIds);
                    hierarchyAttributeIds.add(PropertyKey.UNIT_CODE.getCode());
                    String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttributeIds);
                    dynamicEntityDaoVO.put(id, (String) value);
                }
            }

            // 3-6. createdAt ?????? ??????
            {
                List<String> hierarchyAttributeIds = new ArrayList<>(currentHierarchyIds);
                hierarchyAttributeIds.add(PropertyKey.CREATED_AT.getCode());
                String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttributeIds);
                dynamicEntityDaoVO.put(id, eventTime);
            }

            // 3-7. modifiedAt ?????? ??????
            {
                List<String> hierarchyAttributeIds = new ArrayList<>(currentHierarchyIds);
                hierarchyAttributeIds.add(PropertyKey.MODIFIED_AT.getCode());
                String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttributeIds);
                dynamicEntityDaoVO.put(id, eventTime);
            }
        }
    }

    /**
     * ????????? ??????
     *
     * @param attribute
     * @param attributeId
     * @throws NgsiLdBadRequestException
     */
    private void checkDefaultParam(Map<String, Object> attribute, String attributeId) throws NgsiLdBadRequestException {
        if (attribute == null) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Not found attribute. attributeId=" + attributeId);
        }
        if (attribute.get(PropertyKey.TYPE.getCode()) == null) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                    "Not found attribute type. attributeId=" + attributeId);
        }

        if (AttributeType.parseType(attribute.get(PropertyKey.TYPE.getCode()).toString()) == null) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                    "invalid attribute type. attribute Type=" + attribute.get(PropertyKey.TYPE.getCode()));
        }

        if (attribute.get(PropertyKey.TYPE.getCode()) == AttributeType.PROPERTY
                || attribute.get(PropertyKey.TYPE.getCode()) == AttributeType.GEO_PROPERTY) {
            if (attribute.get(PropertyKey.VALUE.getCode()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                        "Not found Property value. attributeId=" + attributeId);
            }
        } else if (attribute.get(PropertyKey.TYPE.getCode()) == AttributeType.RELATIONSHIP) {
            if (attribute.get(PropertyKey.OBJECT.getCode()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                        "Not found Relationship object. attributeId=" + attributeId);
            }
        }
    }

    /**
     * Object ????????? Property ??? DaoVO ??? ??????
     *
     * @param parentHierarchyIds ???????????? ?????? AttributeId ?????????
     * @param objectMembers      ChildAttribute
     * @param object             property value
     * @param dynamicEntityDaoVO DynamicEntityDaoVO
     * @throws ParseException
     */
    private void objectTypeParamToDaoVO(List<String> parentHierarchyIds, List<ObjectMember> objectMembers,
                                        Map<String, Object> object, DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO) throws ParseException {

        for (ObjectMember objectMember : objectMembers) {
            List<String> currentHierarchyIds = new ArrayList<>(parentHierarchyIds);
            currentHierarchyIds.add(objectMember.getName());

            Object value = object.get(objectMember.getName());
            // ?????? ???????????? ????????? ??????
            checkObjectType(objectMember.getName(), objectMember.getValueType(), value, objectMember);

            if (objectMember.getValueType() == AttributeValueType.OBJECT) {

                Map<String, Object> objectInObject = (Map<String, Object>) value;
                objectTypeParamToDaoVO(currentHierarchyIds, objectMember.getObjectMembers(), objectInObject, dynamicEntityDaoVO, storageMetadataVO);

            } else if (objectMember.getValueType() == AttributeValueType.DATE) {
                String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                dynamicEntityDaoVO.put(id, DateUtil.strToDate((String) value));
            } else {
                String id = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, currentHierarchyIds);
                dynamicEntityDaoVO.put(id, object.get(objectMember.getName()));
            }
        }
    }



    /**
     * primitive object ????????? ??????
     * @param id attributeId
     * @param valueType attributeValueType
     * @param value attributeValue
     * @param attribute ObjectMember
     * @return
     * @throws NgsiLdBadRequestException
     */
    private static boolean checkObjectType(String id, AttributeValueType valueType, Object value, ObjectMember attribute) throws NgsiLdBadRequestException {

        Boolean required = attribute.getIsRequired();
        String minLength = attribute.getMinLength();
        String maxLength = attribute.getMaxLength();
        BigDecimal greaterThanOrEqualTo = attribute.getGreaterThanOrEqualTo();
        BigDecimal greaterThan = attribute.getGreaterThan();
        BigDecimal lessThanOrEqualTo = attribute.getLessThanOrEqualTo();
        BigDecimal lessThan = attribute.getLessThan();
        List<Object> valueEnum = attribute.getValueEnum();

        if (required != null && required) {
            if (value == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Request Content. attributeId=" + id + " is null");
            }
        } else {
            if (value == null) {
                return true;
            }
        }

        switch (valueType) {
            case STRING:
                if (!ValidateUtil.isStringObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidStringMinLength(value, minLength)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "underflow Attribute MinLength. attributeId=" + id + ", valueType=" + valueType + ", minLength=" + minLength + ", value=" + value);
                }
                if (!ValidateUtil.isValidStringMaxLength(value, maxLength)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Overflow Attribute MaxLength. attributeId=" + id + ", valueType=" + valueType + ", maxLength=" + maxLength + ", value=" + value);
                }
                if (!ValidateUtil.isValidEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case INTEGER:
                if (!ValidateUtil.isIntegerObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }

                if (!ValidateUtil.isValidLessThanOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case LONG:
                if (!ValidateUtil.isLongObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }

                if (!ValidateUtil.isValidLessThanOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case DOUBLE:
                if (!ValidateUtil.isBigDecimalObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }

                if (!ValidateUtil.isValidLessThanOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case DATE:
                if (!ValidateUtil.isDateObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                break;
            case BOOLEAN:
                if (!ValidateUtil.isBooleanObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                break;
            case ARRAY_STRING:
                if (!ValidateUtil.isArrayStringObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayStringMinLength(value, minLength)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "underflow Attribute MinLength. attributeId=" + id + ", valueType=" + valueType + ", minLength=" + minLength + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayStringMaxLength(value, maxLength)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Overflow Attribute MaxLength. attributeId=" + id + ", valueType=" + valueType + ", maxLength=" + maxLength + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case ARRAY_INTEGER:
                if (!ValidateUtil.isArrayIntegerObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater  Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }

                if (!ValidateUtil.isValidArrayLessThenOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case ARRAY_LONG:
                if (!ValidateUtil.isArrayLongObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater  Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }

                if (!ValidateUtil.isValidArrayLessThenOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case ARRAY_DOUBLE:
                if (!ValidateUtil.isArrayBigDecimalObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThanOrEqualTo(value, greaterThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThanOrEqualTo=" + greaterThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayGreaterThan(value, greaterThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Greater Attribute value. attributeId=" + id + ", valueType=" + valueType + ", greaterThan=" + greaterThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayLessThenOrEqualTo(value, lessThanOrEqualTo)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less or equal to Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThanOrEqualTo=" + lessThanOrEqualTo + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayLessThan(value, lessThan)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Less Attribute value. attributeId=" + id + ", valueType=" + valueType + ", lessThan=" + lessThan + ", value=" + value);
                }
                if (!ValidateUtil.isValidArrayEnum(value, valueEnum)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER,
                            "Not match Attribute valueEnum. attributeId=" + id + ", valueType=" + valueType + ", valueEnum=" + valueEnum + ", value=" + value);
                }
                break;
            case ARRAY_BOOLEAN:
                if (!ValidateUtil.isArrayBooleanObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                break;
            case OBJECT:
                if (!ValidateUtil.isMapObject(value)) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute Type. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
                }
                break;
            default:
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Invalid Attribute valueType. attributeId=" + id + ", valueType=" + valueType + ", value=" + value);
        }

        return true;
    }

    /**
     * Geometry Object Type ?????? (Point, MultiPoint, LineString, MultiLineString, Polygon, MultiPolygon)
     *
     * @param value
     * @return
     * @throws NgsiLdBadRequestException
     */
    private static boolean checkGeometryObjectType(Object value) throws NgsiLdBadRequestException {

        HashMap<String, Object> map = (HashMap<String, Object>) value;
        String geoType = map.get(PropertyKey.TYPE.getCode()).toString();
        if (GeoJsonValueType.parseType(geoType) == null) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid attribute type. Geometry Object Type=" + geoType);
        }
        return true;
    }


    /**
     * ?????? ?????? ?????? attribute ??????
     *
     * @param dynamicEntityFullVO
     * @param rootAttributes
     * @return
     */
    private static void checkInvalidAttribute(DynamicEntityFullVO dynamicEntityFullVO, List<Attribute> rootAttributes) {

        for (Map.Entry<String, Object> entry : dynamicEntityFullVO.entrySet()) {

            String key = entry.getKey();

            //?????? ????????????(@context, id, createdAt ,modifiedAt ,operation ,type) ?????? SKIP
            if (DefaultAttributeKey.parseType(key) != null) {
                continue;
            }
            Attribute attribute = findAttribute(rootAttributes, entry.getKey());
            if (attribute == null) {
                // rootAttribute ??????
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid key : " + key);
            }
            isExistAttribute(entry, attribute);
        }
    }

    /**
     * attribute ???????????? ??????
     *
     * @param entry
     * @param attribute
     */
    private static void isExistAttribute(Map.Entry<String, Object> entry, Attribute attribute) {

        String attrKey = entry.getKey();
        LinkedHashMap<String, Object> attrValue = (LinkedHashMap<String, Object>) entry.getValue();
        String type = attrValue.get(PropertyKey.TYPE.getCode()).toString();


        if (type.equalsIgnoreCase(AttributeType.PROPERTY.getCode())) {
            // PROPERTY ??? item ??????
            Object valueItem = attrValue.get(PropertyKey.VALUE.getCode());
            if (valueItem == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found value : " + entry.getKey());
            }


            if (valueItem instanceof LinkedHashMap) {
                checkInnerAttribute(attrKey, attrValue, attribute);
            } else if (valueItem instanceof ArrayList) {
                // n-?????? Object ?????? ??????
                ArrayList arrayObject = (ArrayList) valueItem;
                Object innerItem = arrayObject.get(0);
                if (innerItem instanceof LinkedHashMap) {
                    // n-?????? ArrayObject ?????? ??????
                    for (Object item : arrayObject) {
                        checkArrayObject((LinkedHashMap<String, Object>) item, attribute);
                    }
                } else {
                    // 1-?????? ??? array ??? ??????
                    if (!attrKey.equals(attribute.getName()) && !attrKey.equals(attribute.getAttributeUri())) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + entry.getKey());
                    }
                }
            } else {
                // 1-?????? ??????
                if (!attrKey.equals(attribute.getName()) && !attrKey.equals(attribute.getAttributeUri())) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + entry.getKey());
                }
            }

            //?????? ?????? attribute ??????
            checkInnerAttribute(attrKey, attrValue, attribute);

        } else if (type.equalsIgnoreCase(AttributeType.RELATIONSHIP.getCode())) {
            // RELATIONSHIP ??? item ??????
            Object objectItem = attrValue.get(PropertyKey.OBJECT.getCode());
            if (objectItem == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found object : " + entry.getKey());
            }
            //?????? ?????? attribute ??????
            checkInnerAttribute(attrKey, attrValue, attribute);

        } else if (type.equalsIgnoreCase(AttributeType.GEO_PROPERTY.getCode())) {
            // GEO_PROPERTY ??? item ??????
            LinkedHashMap<String, Object> valueItem = (LinkedHashMap<String, Object>) attrValue.get(PropertyKey.VALUE.getCode());
            String geoType = valueItem.get(PropertyKey.TYPE.getCode()).toString();
            if (GeometryType.parseType(geoType) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found geo-type : " + entry.getKey());
            }
            Object coordinatesItem = valueItem.get(PropertyKey.COORDINATES.getCode());
            if (coordinatesItem == null) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found coordinates : " + entry.getKey());
            }

        }

        // ObservedAt ??????
        if (attribute != null && attribute.getHasObservedAt() != null) {
            if (attribute.getHasObservedAt() && !attrValue.containsKey(DefaultAttributeKey.OBSERVED_AT.getCode())) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found observedAt : " + entry.getKey());
            }
        }
    }

    /**
     * ?????? attribute ??????
     *
     * @param key
     * @param attrValue
     * @param attribute
     */
    private static void checkInnerAttribute(String key, LinkedHashMap<String, Object> attrValue, Attribute attribute) {
        String type = attrValue.get(PropertyKey.TYPE.getCode()).toString();

        if (type.equalsIgnoreCase(AttributeType.PROPERTY.getCode())) {


            for (Map.Entry<String, Object> propertyMap : attrValue.entrySet()) {

                String propertyMapKey = propertyMap.getKey();
                if (propertyMapKey.equals(PropertyKey.TYPE.getCode())
                        || propertyMapKey.equals(PropertyKey.OBSERVED_AT.getCode())
                        || propertyMapKey.equals(PropertyKey.UNIT_CODE.getCode()) ) {
                    continue;
                } else if (propertyMapKey.equals(PropertyKey.VALUE.getCode())) {
                    Object attrObjectValue = attrValue.get(PropertyKey.VALUE.getCode());
                    if (attrObjectValue instanceof LinkedHashMap) {
                        LinkedHashMap<String, Object> tmpMap = (LinkedHashMap<String, Object>) attrObjectValue;
                        tmpMap.entrySet();
                        for (Map.Entry<String, Object> entry : tmpMap.entrySet()) {
                            if (attribute != null) {
                                // ex) address ??????
                                checkObjectMember(entry.getKey(), attribute);
                            }
                        }
                    } else {
                        if (attribute == null || (!key.equals(attribute.getName()) && !key.equals(attribute.getAttributeUri()))) {
                            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + key);
                        }
                    }
                } else {

                    // ?????? ?????? ?????? attribute ????????? unit
                    if (attribute.getChildAttributes() == null) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + propertyMapKey);
                    }
                    Attribute innerAttribute = findAttribute(attribute.getChildAttributes(), propertyMapKey);
                    if (innerAttribute == null) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + propertyMapKey);
                    }
                    isExistAttribute(propertyMap, innerAttribute);
                }
            }


        } else if (type.equalsIgnoreCase(AttributeType.RELATIONSHIP.getCode())) {

            for (Map.Entry<String, Object> relationshipMap : attrValue.entrySet()) {

                String relationshipMapKey = relationshipMap.getKey();
                if (relationshipMapKey.equals(PropertyKey.TYPE.getCode())
                        || relationshipMapKey.equals(PropertyKey.OBJECT.getCode())
                        || relationshipMapKey.equals(PropertyKey.OBSERVED_AT.getCode())) {
                    continue;
                }
                Attribute innerAttribute = findAttribute(attribute.getChildAttributes(), relationshipMapKey);
                if (innerAttribute == null) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + relationshipMapKey);
                }
                isExistAttribute(relationshipMap, innerAttribute);
            }

        }
    }

    /**
     * ?????? attribute ?????? ????????????
     *
     * @param attributes
     * @param name
     * @return
     */
    private static Attribute findAttribute(List<Attribute> attributes, String name) {
        for (Attribute attribute : attributes) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
            if (attribute.getAttributeUri().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Property ??? ArrayObject ????????? ??????
     *
     * @param attrValue
     * @param attribute
     */
    private static void checkArrayObject(LinkedHashMap<String, Object> attrValue, Attribute attribute) {

        for (Map.Entry<String, Object> entry : attrValue.entrySet()) {
            boolean isOK = false;
            String innerKey = entry.getKey();
            for (ObjectMember objectMember : attribute.getObjectMembers()) {
                if (innerKey.equals(objectMember.getName())) {
                    isOK = true;
                    break;
                }
            }
            if (!isOK) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "Not found key : " + innerKey);
            }
        }
    }

    /**
     * Property ??? ObjectMember ????????? ??????
     *
     * @param attrKey
     * @param attribute
     */
    private static void checkObjectMember(String attrKey, Attribute attribute) {

        for (ObjectMember objectMember : attribute.getObjectMembers()) {
            if (attrKey.equals(objectMember.getName())) {
                return;
            }
        }
        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid key : " + attribute.getName() + "." + attrKey);
    }

    /**
     * ???????????? ????????? EntityFullVO ????????? ???????????? ??????
     */
    public DynamicEntityFullVO deserializeContent(String content) throws NgsiLdBadRequestException {

        if (ValidateUtil.isEmptyData(content)) {
            return new DynamicEntityFullVO();
        }

        try {
            return objectMapper.readValue(content, new TypeReference<DynamicEntityFullVO>() {
            });
        } catch (IOException e) {
            new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR,
                    "Content Parsing ERROR. content=" + content, e);
        }
        return new DynamicEntityFullVO();
    }

    /**
     * ???????????? ????????? ??? ?????? ??????
     *
     * @param processVOList ????????? ?????? VO ?????????
     */
    @Override
    public void processValidate(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> processVOList) {

        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> processVO : processVOList) {

            // ?????? ????????? ????????? ????????? ?????? ??????
            if (processVO.getProcessResultVO().isProcessResult() != null
                    && !processVO.getProcessResultVO().isProcessResult()) {
                continue;
            }

            try {

                checkValidate(processVO.getEntityFullVO(),
                        processVO.getEntityDaoVO(),
                        processVO.getOperation(),
                        processVO.getDataModelCacheVO());

            } catch (NgsiLdBadRequestException e) {
                ProcessResultVO processResultVO = new ProcessResultVO();
                processResultVO.setProcessResult(false);
                processResultVO.setException(e);
                processResultVO.setErrorDescription(e.getMessage());
                processVO.setProcessResultVO(processResultVO);

                continue;
            }
        }
    }

    /**
     * ???????????? ????????? ??? ?????? ??????
     *
     * @param dynamicEntityFullVO Kafka??? ?????? ???????????? EntityVO
     * @param dynamicEntityDaoVO  dynamicEntityFullVO ???????????? ????????? DaoVO
     * @param operation           ???????????? ?????? Operation
     * @throws NgsiLdBadRequestException
     */
    private void checkValidate(DynamicEntityFullVO dynamicEntityFullVO, DynamicEntityDaoVO dynamicEntityDaoVO, Operation operation, DataModelCacheVO dataModelCacheVO) throws NgsiLdBadRequestException {

        if (operation == Operation.CREATE_ENTITY
                || operation == Operation.REPLACE_ENTITY_ATTRIBUTES
                || operation == Operation.CREATE_ENTITY_OR_REPLACE_ENTITY_ATTRIBUTES) {

            // ?????? rootAttbiute ?????? ????????? ?????? (????????? ?????? ???????????? ?????? ???????????? ?????????)
            List<Attribute> rootAttributes = dataModelCacheVO.getDataModelVO().getAttributes();
            for (Attribute rootAttribute : rootAttributes) {

                boolean rootRequired = rootAttribute.getIsRequired() == null ? false : rootAttribute.getIsRequired();

                // rootAttribute??? ?????? ????????? ????????? ??????
                if (rootRequired) {
                    if (dynamicEntityFullVO.get(rootAttribute.getName()) == null
                            && dynamicEntityFullVO.get(rootAttribute.getAttributeUri()) == null) {
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "'" + rootAttribute.getName() + "' is null");
                    }

                    // rootAttribute??? ????????? ?????? ??????
                } else {
                    if (dynamicEntityFullVO.get(rootAttribute.getName()) == null) {
                        continue;
                    }
                }
            }

            // update ?????? attribute ?????? ?????? ??????
        } else if (operation == Operation.PARTIAL_ATTRIBUTE_UPDATE) {
            String entityId = dynamicEntityDaoVO.getId();
            String attrId = dynamicEntityDaoVO.getAttrId();

            // AttrId ????????? ??????
            // TODO: ???????????? attrId ?????? ??????
            boolean isValid = false;
            List<Attribute> rootAttributes = dataModelCacheVO.getDataModelVO().getAttributes();
            for (Attribute rootAttribute : rootAttributes) {
                if (rootAttribute.getName().equals(attrId)) {
                    isValid = true;
                    break;
                }
            }
            if (!isValid) {
                throw new NgsiLdResourceNotFoundException(ErrorCode.NOT_EXIST_ENTITY_ATTR,
                        "Not exists Entity Attribute. entityId=" + entityId + ", attrId=" + attrId);
            }

            // ?????? ?????? attribute ?????? ?????? ??????
        } else if (operation == Operation.DELETE_ENTITY_ATTRIBUTES) {
            String entityId = dynamicEntityDaoVO.getId();
            String attrId = dynamicEntityDaoVO.getAttrId();

            // AttrId ????????? ??????
            // TODO: ???????????? attrId ?????? ??????
            boolean isValid = false;
            List<Attribute> rootAttributes = dataModelCacheVO.getDataModelVO().getAttributes();
            for (Attribute rootAttribute : rootAttributes) {
                if (rootAttribute.getName().equals(attrId)) {
                    isValid = true;
                    break;
                }
            }
            if (!isValid) {
                throw new NgsiLdResourceNotFoundException(ErrorCode.NOT_EXIST_ENTITY_ATTR,
                        "Not exists Entity Attribute. entityId=" + entityId + ", attrId=" + attrId);
            }
        }
    }

    /**
     * Operation ??? ???????????? ???????????? ??????
     *
     * @param createList              CREATE ???????????? ?????????
     * @param replaceAttrList         REPLACE ATTRIBUTES ???????????? ?????????
     * @param appendAttrList          APPEND ATTRIBUTES ???????????? ?????????
     * @param createOrReplaceAttrList CREATE OR REPLACE ATTRIBUTES ???????????? ?????????
     * @param createOrAppendAttrList  CREATE OR APPEND ATTRIBUTES ???????????? ?????????
     * @param deleteList              DELETE ???????????? ?????????
     * @param entityProcessVOList     ??????????????? ????????? ?????? VO ?????????
     */
    private void setProcessVOByOperation(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> replaceAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendNoOverwriteEntityList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> updateAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> partialAttrUpdateList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createOrReplaceAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createOrAppendAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteAttrList,
                                         List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {

        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> processVO : entityProcessVOList) {

            // ????????? ?????? ????????? ?????????????????? ?????? ????????? ???????????? ??????
            if (processVO.getProcessResultVO().isProcessResult() != null
                    && !processVO.getProcessResultVO().isProcessResult()) {
                continue;
            }

            // Operation ??? ??????
            Operation operation = processVO.getOperation();
            List<OperationOption> operationOptions = processVO.getOperationOptions();

            switch (operation) {
                case CREATE_ENTITY:
                    createList.add(processVO);
                    break;
                case APPEND_ENTITY_ATTRIBUTES:
                    boolean noOverwrite = false;
                    if (!ValidateUtil.isEmptyData(operationOptions)) {
                        for (OperationOption operationOption : operationOptions) {
                            if (OperationOption.NO_OVERWRITE == operationOption) {
                                noOverwrite = true;
                                break;
                            }
                        }
                    }
                    if (noOverwrite) {
                        appendNoOverwriteEntityList.add(processVO);
                    } else {
                        appendAttrList.add(processVO);
                    }
                    break;
                case REPLACE_ENTITY_ATTRIBUTES:
                    replaceAttrList.add(processVO);
                    break;
                case UPDATE_ENTITY_ATTRIBUTES:
                    updateAttrList.add(processVO);
                    break;
                case PARTIAL_ATTRIBUTE_UPDATE:
                    partialAttrUpdateList.add(processVO);
                    break;
                case CREATE_ENTITY_OR_APPEND_ENTITY_ATTRIBUTES:
                    createOrAppendAttrList.add(processVO);
                    break;
                case CREATE_ENTITY_OR_REPLACE_ENTITY_ATTRIBUTES:
                    createOrReplaceAttrList.add(processVO);
                    break;
                case DELETE_ENTITY:
                    deleteList.add(processVO);
                    break;
                case DELETE_ENTITY_ATTRIBUTES:
                    deleteAttrList.add(processVO);
                    break;
                default:
                    // Error Handling ??????
                    break;
            }
        }
    }

    /**
     * Operation ??? ?????? ??????
     *
     * @param entityProcessVOList ??????VO?????????
     */
    @Override
    public void processOperation(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {

        // 1. Operation ??? ??????????????? ?????? List ??????
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> replaceEntityList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendEntityList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendNoOverwriteEntityList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> updateAttrList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> partialAttrUpdateList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createOrAppendAttrList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createOrReplaceAttrList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteList = new ArrayList<>();
        List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteAttrList = new ArrayList<>();

        // 2. Operation ??? ??????????????? ?????? VO ??????
        setProcessVOByOperation(createList, replaceEntityList, appendEntityList, appendNoOverwriteEntityList, updateAttrList, partialAttrUpdateList,
                createOrReplaceAttrList, createOrAppendAttrList, deleteList, deleteAttrList, entityProcessVOList);

        // 3. Create Entity ????????????
        processCreate(createList);

        // 4. Replace Entity Attributes ????????????
        processReplaceAttr(replaceEntityList);

        // 5. Append Entity Attributes ????????????
        processAppendAttr(appendEntityList);

        // 5-1. Append Entity (noOverwrite) Attributes ????????????
        processAppendNoOverwriteAttr(appendNoOverwriteEntityList);

        // 6. Update Entity Attributes ????????????
        processUpdateAttr(updateAttrList);

        // 7. Partial Attribute Update ????????????
        processPartialAttrUpdate(partialAttrUpdateList);

        // 8. Create Entity or Replace Entity Attributes ????????????
        processFullUpsert(createOrReplaceAttrList);

        // 9. Create Entity or Append Entity Attributes ????????????
        processPartialUpsert(createOrAppendAttrList);

        // 10. Delete Entity ????????????
        processDelete(deleteList);

        // 11. Delete Attribute Entity
        processDeleteAttr(deleteAttrList);
    }

    private void storeEntityDataModel(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : entityProcessVOList) {
            if (entityProcessVO.getProcessResultVO().isProcessResult()) {

                try {
                    DataModelCacheVO dataModelCacheVO = entityProcessVO.getDataModelCacheVO();

                    String entityId = entityProcessVO.getEntityFullVO().getId();
                    String datasetId = entityProcessVO.getDatasetId();
                    String dataModelId = dataModelCacheVO.getDataModelVO().getId();
                    String dataModelType = dataModelCacheVO.getDataModelVO().getType();

                    // entity??? ????????? ?????? entity??? ????????? ?????? ?????? ??????????????? ??????
                    if (Operation.DELETE_ENTITY == entityProcessVO.getProcessResultVO().getProcessOperation()) {
                        entityDataModelSVC.deleteEntityDataModel(entityId);

                        // ?????? ?????? ????????? ??????
                    } else {

                        EntityDataModelVO retrieveEntityDataModelVO = entityDataModelSVC.getEntityDataModelVOById(entityId);
                        // ?????? ????????? ?????? ?????? ?????? Insert
                        if (retrieveEntityDataModelVO == null) {
                            EntityDataModelVO entityDataModelVO = new EntityDataModelVO();
                            entityDataModelVO.setId(entityId);
                            entityDataModelVO.setDatasetId(datasetId);
                            entityDataModelVO.setDataModelId(dataModelId);
                            entityDataModelVO.setDataModelType(dataModelType);

                            entityDataModelSVC.createEntityDataModel(entityDataModelVO);

                            // ?????? ????????? ???????????? ??????
                        } else {

                            // ????????? ????????? ?????? ????????? ??????
                            if ((datasetId != null && !datasetId.equals(retrieveEntityDataModelVO.getDatasetId()))
                                    || !retrieveEntityDataModelVO.getDataModelId().equals(dataModelId)
                                    || !retrieveEntityDataModelVO.getDataModelType().equals(dataModelType)) {

                                EntityDataModelVO entityDataModelVO = new EntityDataModelVO();
                                entityDataModelVO.setId(entityId);
                                entityDataModelVO.setDatasetId(datasetId);
                                entityDataModelVO.setDataModelId(dataModelId);
                                entityDataModelVO.setDataModelType(dataModelType);

                                entityDataModelSVC.updateEntityDataModel(entityDataModelVO);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("storeEntityDataModel error. datasetId=" + entityProcessVO.getDatasetId()
                            + ", entityId=" + entityProcessVO.getEntityDaoVO().getId(), e);
                }
            }
        }
    }


    /**
     * Create Entity ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param createList CREATE ?????? VO ?????????
     */
    private void processCreate(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createList) {
        if (createList == null || createList.size() == 0) return;

        // ?????? Create Entity ??????
        boolean bulkResult = bulkCreate(createList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : createList) {
                singleCreate(entityProcessVO);
            }
        }
    }

    /**
     * Entity ?????? Create ??????
     *
     * @param createList CREATE ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkCreate(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> createList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(createList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : createList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? CREATE ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkCreate(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < createList.size(); i++) {
                createList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                log.warn("bulkCreate Entity duplicate error", e);
            } else {
                log.error("bulkCreate Entity error", e);
            }
            return false;
        }
    }

    /**
     * Entity ?????? Create ??????
     *
     * @param entityProcessVO CREATE ?????? VO
     * @return ?????? ??????
     */
    private boolean singleCreate(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.create(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
            processResultVO.setProcessResult(false);

            if (e1 instanceof org.springframework.dao.DuplicateKeyException) {
                log.warn(entityProcessVO.getDatasetId() + " Single Create Entity duplicate error", e1);
                processResultVO.setErrorDescription("Create Entity duplicate ERROR(Create Entity). id=" + id);
                processResultVO.setException(new NgsiLdBadRequestException(ErrorCode.ALREADY_EXISTS, "Create duplicate ERROR(Create Entity). id=" + id, e1));
            } else {
                log.error(entityProcessVO.getDatasetId() + " Single Create Entity error", e1);
                processResultVO.setErrorDescription("SQL ERROR(Create Entity). id=" + id);
                processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR, "SQL ERROR(Create Entity). id=" + id, e1));
            }
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }


    /**
     * Replace Entity Attributes ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param replaceEntity Replace Entity Attributes VO ?????? ?????????
     */
    private void processReplaceAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> replaceEntity) {
        if (replaceEntity == null || replaceEntity.size() == 0) return;

        // ?????? Replace Entity Attributes ??????
        boolean bulkResult = bulkReplaceEntity(replaceEntity);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : replaceEntity) {
                singleReplaceEntity(entityProcessVO);
            }
        }
    }

    /**
     * Replace Entity Attributes ??????
     *
     * @param replaceEntityList Replace Entity Attributes VO ?????? ?????????
     * @return ?????? ??????
     */
    private boolean bulkReplaceEntity(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> replaceEntityList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(replaceEntityList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : replaceEntityList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Replace Entity Attributes ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkReplaceEntity(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < replaceEntityList.size(); i++) {
                replaceEntityList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("Replace Entity Attributes error", e);
            return false;
        }
    }

    /**
     * Entity ?????? Replace Entity Attributes ??????
     *
     * @param entityProcessVO Replace Entity Attributes ?????? VO
     * @return ?????? ??????
     */
    private boolean singleReplaceEntity(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.replaceEntity(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " Single Replace Entity Attributes error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(Replace Entity Attributes). id=" + id));
            processResultVO.setErrorDescription("SQL ERROR(Replace Entity Attributes). id=" + id);
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }


    /**
     * Entity Append Entity Attributes ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param appendAttrList Append Entity Attributes ?????? VO ?????????
     */
    private void processAppendAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendAttrList) {
        if (appendAttrList == null || appendAttrList.size() == 0) return;

        // ?????? Append Entity Attributes ??????
        boolean bulkResult = bulkAppendAttr(appendAttrList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : appendAttrList) {
                singleAppendAttr(entityProcessVO);
            }
        }
    }

    /**
     * Entity Append Entity Attributes ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param appendNoOverwriteEntityList Append Entity (noOverwrite) Attributes ?????? VO ?????????
     */
    private void processAppendNoOverwriteAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendNoOverwriteEntityList) {
        if (appendNoOverwriteEntityList == null || appendNoOverwriteEntityList.size() == 0) return;

        // ?????? Append Entity Attributes ??????
        boolean bulkResult = bulkAppendNoOverwriteAttr(appendNoOverwriteEntityList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : appendNoOverwriteEntityList) {
                singleAppendNoOverwriteAttr(entityProcessVO);
            }
        }
    }

    /**
     * Entity ?????? Append Entity Attributes ??????
     *
     * @param appendAttrList Append Entity Attributes ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkAppendAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendAttrList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(appendAttrList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : appendAttrList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Append Entity Attributes ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkAppendAttr(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < appendAttrList.size(); i++) {
                appendAttrList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("bulkAppendAttr error", e);
            return false;
        }
    }

    /**
     * Entity ?????? Append Entity Attributes ??????
     *
     * @param entityProcessVO Append Entity Attributes ?????? VO
     * @return ?????? ??????
     */
    private boolean singleAppendAttr(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.appendAttr(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " Single Append Entity Attributes error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(Append Entity Attributes). id=" + id));
            processResultVO.setErrorDescription("SQL ERROR(Append Entity Attributes). id=" + id);
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }


    /**
     * Entity ?????? Append Entity (noOverwrite) Attributes ??????
     *
     * @param appendNoOverwriteEntityList Append Entity Attributes ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkAppendNoOverwriteAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> appendNoOverwriteEntityList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(appendNoOverwriteEntityList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : appendNoOverwriteEntityList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Append Entity Attributes ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkAppendNoOverwriteAttr(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < appendNoOverwriteEntityList.size(); i++) {
                appendNoOverwriteEntityList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("bulkAppendAttr error", e);
            return false;
        }
    }

    /**
     * Entity ?????? Append Entity (noOverwrite) Attributes ??????
     *
     * @param entityProcessVO Append Entity Attributes ?????? VO
     * @return ?????? ??????
     */
    private boolean singleAppendNoOverwriteAttr(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.appendAttr(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " Single Append Entity (noOverwrite) Attributes error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(Append Entity Attributes (noOverwrite)). id=" + id));
            processResultVO.setErrorDescription("SQL ERROR(Append Entity (noOverwrite) Attributes). id=" + id);
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }

    /**
     * Update Entity Attributes ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param updateAttrList Update Entity Attributes ?????? VO ?????????
     */
    private void processUpdateAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> updateAttrList) {
        if (updateAttrList == null || updateAttrList.size() == 0) return;

        // ?????? Update Entity Attributes ??????
        boolean bulkResult = bulkUpdateAttr(updateAttrList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : updateAttrList) {
                singleUpdateAttr(entityProcessVO);
            }
        }
    }

    /**
     * ?????? Update Entity Attributes ??????
     *
     * @param entityProcessVOList Update Entity Attributes ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkUpdateAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(entityProcessVOList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : entityProcessVOList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Update Entity Attributes ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkUpdateAttr(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < entityProcessVOList.size(); i++) {
                entityProcessVOList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("bulkUpdateAttr error", e);
            return false;
        }
    }

    /**
     * ?????? Update Entity Attributes ??????
     *
     * @param entityProcessVO Update Entity Attributes ?????? VO
     * @return ?????? ??????
     */
    private boolean singleUpdateAttr(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.updateAttr(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " singleUpdateEntityAttr error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.UPDATE_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(UpdateEntityAttr). id=" + id));
            processResultVO.setErrorDescription("SQL ERROR(UpdateEntityAttr). id=" + id);
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }

    /**
     * Partial Attribute Update ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param partialAttrUpdateList Partial Attribute Update ?????? VO ?????????
     */
    private void processPartialAttrUpdate(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> partialAttrUpdateList) {
        if (partialAttrUpdateList == null || partialAttrUpdateList.size() == 0) return;

        // ?????? Update Entity Attributes ??????
        boolean bulkResult = bulkPartialAttrUpdate(partialAttrUpdateList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : partialAttrUpdateList) {
                singlePartialAttrUpdate(entityProcessVO);
            }
        }
    }

    /**
     * ?????? Partial Attribute Update ??????
     *
     * @param entityProcessVOList Partial Attribute Update ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkPartialAttrUpdate(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(entityProcessVOList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : entityProcessVOList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Partial Attribute Update ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkPartialAttrUpdate(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < entityProcessVOList.size(); i++) {
                entityProcessVOList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("bulkPartialAttrUpdate error", e);
            return false;
        }
    }

    /**
     * ?????? Partial Attribute Update ??????
     *
     * @param entityProcessVO Partial Attribute Update ?????? VO
     * @return ?????? ??????
     */
    private boolean singlePartialAttrUpdate(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.partialAttrUpdate(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " singlePartialAttrUpdate error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.PARTIAL_ATTRIBUTE_UPDATE);
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(PartialAttrUpdate). id=" + id));
            processResultVO.setErrorDescription("SQL ERROR(PartialAttrUpdate). id=" + id);
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }

    /**
     * Entity FULL UPSERT ??????
     * - ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param fullUpsertList FULL UPSERT ?????? VO ?????????
     */
    private void processFullUpsert(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> fullUpsertList) {
        if (fullUpsertList == null || fullUpsertList.size() == 0) return;

        // ?????? FULL UPSERT ??????
        boolean bulkResult = bulkFullUpsert(fullUpsertList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : fullUpsertList) {
                singleFullUpsert(entityProcessVO);
            }
        }
    }

    /**
     * Entity ?????? FULL UPSERT ??????
     *
     * @param fullUpsertList FULL UPSERT ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkFullUpsert(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> fullUpsertList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(fullUpsertList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : fullUpsertList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? Full UPSERT ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkFullUpsert(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < fullUpsertList.size(); i++) {
                fullUpsertList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                log.warn("bulkFullUpsert create duplicate error. daoVOList size={}", daoVOList.size());
            } else {
                log.error("bulkFullUpsert error", e);
            }
            return false;
        }
    }

    /**
     * Entity ?????? FULL UPSERT ??????
     *
     * @param entityProcessVO FULL UPSERT ?????? VO
     * @return ?????? ??????
     */
    private boolean singleFullUpsert(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {

            processResultVO = entityDAO.fullUpsert(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY_OR_REPLACE_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);

            if (e1 instanceof org.springframework.dao.DuplicateKeyException) {
//                log.warn(entityProcessVO.getDatasetId() + " singleFullUpsert create duplicate error", e1);
//                processResultVO.setErrorDescription("Create duplicate ERROR(FullUpsert). id=" + id);
//                processResultVO.setException(new NgsiLdBadRequestException(ErrorCode.ALREADY_EXISTS, "Create duplicate ERROR(FullUpsert). id=" + id, e1));

                // Upsert ?????? Dupl??? ????????? ?????? ???????????? ??????
                //  - ?????? ???????????? ????????????????????? ?????? ??????????????? ????????? ???????????? ?????? ?????? ?????????
                processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
                processResultVO.setProcessResult(true);
            } else {
                log.error(entityProcessVO.getDatasetId() + " singleFullUpsert error", e1);
                processResultVO.setErrorDescription("SQL ERROR(FullUpsert). id=" + id);
                processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR, "SQL ERROR(FullUpsert). id=" + id));
            }
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }

    /**
     * Entity Partial Upsert ??????
     * ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param partialUpsertList
     */
    private void processPartialUpsert(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> partialUpsertList) {
        if (partialUpsertList == null || partialUpsertList.size() == 0) return;

        // ?????? PARTIAL UPSERT ??????
        boolean bulkResult = bulkPartialUpsert(partialUpsertList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : partialUpsertList) {
                singlePartialUpsert(entityProcessVO);
            }
        }
    }

    /**
     * Entity ?????? PARTIAL UPSERT ??????
     *
     * @param partialUpsertList PARTIAL UPSERT ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkPartialUpsert(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> partialUpsertList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(partialUpsertList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : partialUpsertList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? PARTIAL UPSERT ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkPartialUpsert(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < partialUpsertList.size(); i++) {
                partialUpsertList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                log.warn("bulkPartialUpsert create duplicate error. daoVOList size={}", daoVOList.size());
            } else {
                log.error("bulkPartialUpsert error", e);
            }
            return false;
        }
    }

    /**
     * Entity ?????? PARTIAL UPSERT ??????
     *
     * @param entityProcessVO PARTIAL UPSERT ?????? VO
     * @return ?????? ??????
     */
    private boolean singlePartialUpsert(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.partialUpsert(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY_OR_APPEND_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);

            if (e1 instanceof org.springframework.dao.DuplicateKeyException) {
//                log.warn(entityProcessVO.getDatasetId() + " singlePartialUpsert create duplicate error", e1);
//                processResultVO.setErrorDescription("Create duplicate ERROR(PartialUpsert). id=" + id);
//                processResultVO.setException(new NgsiLdBadRequestException(ErrorCode.ALREADY_EXISTS, "Create duplicate ERROR(PartialUpsert). id=" + id, e1));

                // Upsert ?????? Dupl??? ????????? ?????? ???????????? ??????
                //  - ?????? ???????????? ????????????????????? ?????? ??????????????? ????????? ???????????? ?????? ?????? ?????????
                processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
                processResultVO.setProcessResult(true);
            } else {
                log.error(entityProcessVO.getDatasetId() + " singlePartialUpsert error", e1);
                processResultVO.setErrorDescription("SQL ERROR(PartialUpsert). id=" + id);
                processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR, "SQL ERROR(PartialUpsert). id=" + id));
            }
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }


    /**
     * Entity Delete ??????
     * ?????? ?????? ?????? ??? ?????? ??? ?????? ??????
     *
     * @param deleteList
     */
    private void processDelete(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteList) {
        if (deleteList == null || deleteList.size() == 0) return;

        // ?????? DELETE ??????
        boolean bulkResult = bulkDelete(deleteList);

        if (!bulkResult) {
            // ???????????? Query ?????? ??? ????????? ????????? ?????? (?????? ??????)
            for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : deleteList) {
                singleDelete(entityProcessVO);
            }
        }
    }

    /**
     * Entity ?????? Delete ??????
     *
     * @param deleteList DELETE ?????? VO ?????????
     * @return ?????? ??????
     */
    private boolean bulkDelete(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteList) {

        List<DynamicEntityDaoVO> daoVOList = new ArrayList<>(deleteList.size());
        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : deleteList) {
            daoVOList.add(entityProcessVO.getEntityDaoVO());
        }

        try {
            // ?????? DELETE ??????
            List<ProcessResultVO> processResultVOList = entityDAO.bulkDelete(daoVOList);

            if (processResultVOList == null) return false;

            for (int i = 0; i < deleteList.size(); i++) {
                deleteList.get(i).setProcessResultVO(processResultVOList.get(i));
            }

            return true;
        } catch (Exception e) {
            log.error("BulkDelete error", e);
            return false;
        }
    }

    /**
     * Entity ?????? Delete ??????
     *
     * @param entityProcessVO DELETE ?????? VO
     * @return ?????? ??????
     */
    private boolean singleDelete(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            processResultVO = entityDAO.delete(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " delete error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.DELETE_ENTITY);
            processResultVO.setProcessResult(false);
            processResultVO.setErrorDescription("SQL ERROR(Delete). id=" + id);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(Delete). id=" + id, e1));
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }

    /**
     * Entity DeleteAttr ??????
     *
     * @param deleteAttrList
     */
    private void processDeleteAttr(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> deleteAttrList) {
        if (deleteAttrList == null || deleteAttrList.size() == 0) return;

        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : deleteAttrList) {

            // DELETE ATTRIBUTES ??????
            singleDeleteAttr(entityProcessVO);
        }
    }

    /**
     * Entity ?????? DeleteAttr ??????
     *
     * @param entityProcessVO DELETE ?????? VO
     * @return ?????? ??????
     */
    private boolean singleDeleteAttr(EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO) {
        ProcessResultVO processResultVO = null;

        try {
            // 2. Attribute Delete ??????
            processResultVO = entityDAO.deleteAttr(entityProcessVO.getEntityDaoVO());

        } catch (Exception e1) {
            log.error(entityProcessVO.getDatasetId() + " deleteAttr error", e1);

            String id = entityProcessVO.getEntityDaoVO().getId();

            processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.DELETE_ENTITY_ATTRIBUTES);
            processResultVO.setProcessResult(false);
            processResultVO.setErrorDescription("SQL ERROR(DeleteAttr). id=" + id);
            processResultVO.setException(new NgsiLdInternalServerErrorException(ErrorCode.SQL_ERROR,
                    "SQL ERROR(DeleteAttr). id=" + id, e1));
        }

        entityProcessVO.setProcessResultVO(processResultVO);
        return processResultVO.isProcessResult();
    }


    /**
     * ??????????????? ????????? ??????
     *
     * @param queryVO ?????? ????????????
     * @param accept  accept ??????
     * @return
     */
    @Override
    public List<CommonEntityVO> selectAll(QueryVO queryVO, String accept) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType : ").append(queryVO.getType())
                    .append(", params(queryVO) : ").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        if(ValidateUtil.isEmptyData(queryVO.getType())) {
            List<DataModelCacheVO> dataModelCacheVOs = dataModelManager.getTargetDataModelByQueryUri(queryVO.getLinks(), queryVO);
            return selectAllWithMultiType(dataModelCacheVOs, queryVO, accept);
        } else if(queryVO.getType().contains(",")) {
            String[] typeArrs = queryVO.getType().split(",");
            List<DataModelCacheVO> dataModelCacheVOs = new ArrayList<>();
            for(String type : typeArrs) {
                DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), type);
                if(dataModelCacheVO != null) {
                    dataModelCacheVOs.add(dataModelCacheVO);
                }
            }
            return selectAllWithMultiType(dataModelCacheVOs, queryVO, accept);
        } else {
            return selectAllWithType(queryVO, accept);
        }
    }

    private List<CommonEntityVO> selectAllWithMultiType(List<DataModelCacheVO> dataModelCacheVOs, QueryVO queryVO, String accept) {

        if (ValidateUtil.isEmptyData(dataModelCacheVOs)) {
            throw new NgsiLdBadRequestException(ErrorCode.NOT_EXIST_ENTITY, "Not exist entityTypes. type=" + queryVO.getType() + ", Link=" + queryVO.getLinks());
        }

        List<CommonEntityVO> totalCommonEntityVOs = new ArrayList<>();

        for (DataModelCacheVO dataModelCacheVO : dataModelCacheVOs) {
            if(dataModelCacheVO.getCreatedStorageTypes() != null
                    && dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {

                if(dataModelCacheVO.getDataModelVO().getTypeUri() == null) {
                    log.warn("selectAll Invalid DataModel. typeUri is null. dataModelId={}", dataModelCacheVO.getDataModelVO().getId());
                    continue;
                }

                QueryVO innerQueryVO = (QueryVO) SerializationUtils.clone(queryVO);
                innerQueryVO.setType(dataModelCacheVO.getDataModelVO().getTypeUri());
                innerQueryVO.setLinks(null);
                innerQueryVO.setOffset(queryVO.getOffset());
                innerQueryVO.setLimit(queryVO.getLimit());
                List<CommonEntityVO> commonEntityVOs = this.selectAllWithType(innerQueryVO, accept);
                if (!ValidateUtil.isEmptyData(commonEntityVOs)) {
                    totalCommonEntityVOs.addAll(commonEntityVOs);
                }
            }
        }

        Collections.sort(totalCommonEntityVOs);
        return extractSubListWithoutType(totalCommonEntityVOs, queryVO.getLimit(), queryVO.getOffset());
    }

    public List<CommonEntityVO> selectAllWithType(QueryVO queryVO, String accept) {

        DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), queryVO.getType());
        if (dataModelCacheVO == null) {
            throw new NgsiLdNoExistTypeException(ErrorCode.NOT_EXIST_ENTITY, "Invalid EntityType. entityType=" + queryVO.getType() + ", link=" + queryVO.getLinks());
        }
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        // ?????????????????? ???????????? ?????? ???????????? ?????? ?????? (???????????? ?????? ????????? ?????? ????????? ?????? storageType??? ???????????? ?????? ??????)
        if(dataModelCacheVO.getCreatedStorageTypes() == null
                || !dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {
            return new ArrayList<>();
        }

        // 1. Entity ?????? DB ??????
        List<DynamicEntityDaoVO> entityDaoVOList = entityDAO.selectAll(queryVO);

        List<CommonEntityVO> commonEntityVOList = new ArrayList<>();

        if (entityDaoVOList != null) {
            for (DynamicEntityDaoVO entityDaoVO : entityDaoVOList) {

                CommonEntityVO commonEntityVO = null;

                // 2. options ????????? ?????? ?????? ??????
                if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.KEY_VALUES.getCode())) {
                    // options = keyValues ??? ?????? ??????, Simplified Representation
                    commonEntityVO = this.daoVOToSimpleRepresentationVO(entityDaoVO, dataModelCacheVO, queryVO.getAttrs());
                } else {

                    boolean includeSysAttrs = false;
                    if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.SYS_ATTRS.getCode())) {
                        includeSysAttrs = true;
                    }
                    // options??? ?????? ?????? ??????, Full Representation
                    commonEntityVO = this.daoVOToFullRepresentationVO(entityDaoVO, dataModelCacheVO, includeSysAttrs, queryVO.getAttrs());
                }

                // 3. ?????? header??? accept??? 'application/ld+json' ??? ?????? @context ?????? ??????
                if (commonEntityVO != null && accept.equals(Constants.APPLICATION_LD_JSON_VALUE)) {
                    commonEntityVO.setContext(dataModelCacheVO.getDataModelVO().getContext());
                }

                if(commonEntityVO != null) {
                    commonEntityVOList.add(commonEntityVO);
                }
            }
        }

        return commonEntityVOList;
    }


    /**
     * ??????????????? ?????? ??????
     *
     * @param queryVO    ?????? ????????????
     * @return
     */
    @Override
    public CommonEntityVO selectById(QueryVO queryVO, String accept, Boolean useForCreateOperation) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append("params(queryVO)=").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // 1. ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        // 2. ??????????????? ?????? ??????
        EntityDataModelVO entityDataModelVO = entityDataModelSVC.getEntityDataModelVOById(queryVO.getId());
        if (entityDataModelVO == null) {
            throw new NgsiLdResourceNotFoundException(DataServiceBrokerCode.ErrorCode.NOT_EXIST_ID, "There is no Entity instance with the requested identifier.");
        }

        queryVO.setDataModelId(entityDataModelVO.getDataModelId());
        queryVO.setType(entityDataModelVO.getDataModelType());

        DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheById(entityDataModelVO.getDataModelId());
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        // ?????????????????? ???????????? ?????? ???????????? ?????? ?????? (???????????? ?????? ????????? ?????? ????????? ?????? storageType??? ???????????? ?????? ??????)
        if(dataModelCacheVO.getCreatedStorageTypes() == null
                || !dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {
            return null;
        }

        CommonEntityVO commonEntityVO = null;

        // 3. entity ?????? DB ??????
        DynamicEntityDaoVO entityDaoVO = entityDAO.selectById(queryVO, useForCreateOperation);

        if (entityDaoVO != null) {

            // 4. options ????????? ?????? ?????? ??????
            if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.KEY_VALUES.getCode())) {
                // options = keyValues ??? ?????? ??????, Simplified Representation
                commonEntityVO = this.daoVOToSimpleRepresentationVO(entityDaoVO, dataModelCacheVO, queryVO.getAttrs());

            } else {

                boolean includeSysAttrs = false;
                if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.SYS_ATTRS.getCode())) {
                    includeSysAttrs = true;
                }
                // options??? ?????? ?????? ??????, Full Representation
                commonEntityVO = this.daoVOToFullRepresentationVO(entityDaoVO, dataModelCacheVO, includeSysAttrs, queryVO.getAttrs());
            }
        }

        // 5. ?????? header??? accept??? 'application/ld+json' ??? ?????? @context ?????? ??????
        if (commonEntityVO != null && accept.equals(Constants.APPLICATION_LD_JSON_VALUE)) {
            commonEntityVO.setContext(dataModelCacheVO.getDataModelVO().getContext());
        }

        return commonEntityVO;
    }

    /**
     * ??????????????? ????????? ??????
     *
     * @param queryVO ?????? ????????????
     * @param accept  entity ??????
     * @return
     */
    @Override
    public List<CommonEntityVO> selectTemporal(QueryVO queryVO, String accept) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType : ").append(queryVO.getType())
                    .append(", params(queryVO) : ").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        if(ValidateUtil.isEmptyData(queryVO.getType())) {
            List<DataModelCacheVO> dataModelCacheVOs = dataModelManager.getTargetDataModelByQueryUri(queryVO.getLinks(), queryVO);
            if (dataModelCacheVOs == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXIST_ENTITY, "Not Exist EntityTypes . Link=" + String.join(",", queryVO.getLinks()));
            }

            List<CommonEntityVO> totalCommonEntityVOs = new ArrayList<>();

            for (DataModelCacheVO dataModelCacheVO : dataModelCacheVOs) {
                if(dataModelCacheVO.getCreatedStorageTypes() != null
                        && dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {

                    if(dataModelCacheVO.getDataModelVO().getTypeUri() == null) {
                        log.warn("selectTemporal Invalid DataModel. typeUri is null. dataModelId={}", dataModelCacheVO.getDataModelVO().getId());
                        continue;
                    }

                    QueryVO copiedQueryVO = (QueryVO) SerializationUtils.clone(queryVO);
                    copiedQueryVO.setType(dataModelCacheVO.getDataModelVO().getTypeUri());
                    copiedQueryVO.setLinks(null);
                    List<CommonEntityVO> commonEntityVOs = this.selectTemporalWithType(copiedQueryVO, accept);
                    if (!ValidateUtil.isEmptyData(commonEntityVOs)) {
                        totalCommonEntityVOs.addAll(commonEntityVOs);
                    }
                }
            }

            Collections.sort(totalCommonEntityVOs);
            return extractSubListWithoutType(totalCommonEntityVOs, queryVO.getLimit(), queryVO.getOffset());
        } else {
            return selectTemporalWithType(queryVO, accept);
        }

    }

    public List<CommonEntityVO> selectTemporalWithType(QueryVO queryVO, String accept) {

        if (log.isDebugEnabled()) {

            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType=").append(queryVO.getType())
                    .append(", params(queryVO)=").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), queryVO.getType());
        if (dataModelCacheVO == null) {
            throw new NgsiLdNoExistTypeException(ErrorCode.NOT_EXIST_ENTITY, "Not Exist EntityType. entityType=" + queryVO.getType());
        }
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        // ?????????????????? ???????????? ?????? ???????????? ?????? ?????? (???????????? ?????? ????????? ?????? ????????? ?????? storageType??? ???????????? ?????? ??????)
        if(dataModelCacheVO.getCreatedStorageTypes() == null
                || !dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {
            return new ArrayList<>();
        }

        // 1. entity ?????? DB ??????
        List<DynamicEntityDaoVO> entityDaoVOList = entityDAO.selectAllHist(queryVO);
        List<CommonEntityVO> commonEntityVOList = new ArrayList<>();

        // 2. options ????????? ?????? ?????? ??????
        if (entityDaoVOList != null) {

            if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.NORMALIZED_HISTORY.getCode())) {
                commonEntityVOList = this.daoVOToTemporalNormalizedHistoryRepresentationVO(entityDaoVOList, dataModelCacheVO, accept);

            } else if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.TEMPORAL_VALUES.getCode())) {
                Integer lastN = queryVO.getLastN();
                commonEntityVOList = this.daoVOToTemporalFullRepresentationVO(entityDaoVOList, dataModelCacheVO, lastN, accept);
                commonEntityVOList = this.daoVOToTemporalTemporalValuesRepresentationVO(commonEntityVOList);

            } else {
                Integer lastN = queryVO.getLastN();
                commonEntityVOList = this.daoVOToTemporalFullRepresentationVO(entityDaoVOList, dataModelCacheVO, lastN, accept);
            }
        }
        return commonEntityVOList;
    }


    /**
     * ??????????????? ?????? ??????
     *
     * @param queryVO    ?????? ????????????
     * @param accept
     * @return
     */
    @Override
    public CommonEntityVO selectTemporalById(QueryVO queryVO, String accept) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType=").append(queryVO.getType())
                    .append(", params(queryVO)=").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        // 1. ??????????????? ?????? ??????
        EntityDataModelVO entityDataModelVO = entityDataModelSVC.getEntityDataModelVOById(queryVO.getId());
        if (entityDataModelVO == null) {
            throw new NgsiLdResourceNotFoundException(DataServiceBrokerCode.ErrorCode.NOT_EXIST_ID, "There is no Entity instance with the requested identifier.???");
        }

        queryVO.setDataModelId(entityDataModelVO.getDataModelId());
        queryVO.setType(entityDataModelVO.getDataModelType());

        DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheById(entityDataModelVO.getDataModelId());
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        // ?????????????????? ???????????? ?????? ???????????? ?????? ?????? (???????????? ?????? ????????? ?????? ????????? ?????? storageType??? ???????????? ?????? ??????)
        if(dataModelCacheVO.getCreatedStorageTypes() == null
                || !dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {
            return new CommonEntityVO();
        }

        // 2. entity ?????? DB ??????
        List<DynamicEntityDaoVO> entityDaoVOList = entityDAO.selectHistById(queryVO);

        // 3. options ????????? ?????? ?????? ??????
        List<CommonEntityVO> commonEntityVOList = new ArrayList<>();

        if (entityDaoVOList != null) {

            if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.NORMALIZED_HISTORY.getCode())) {
                commonEntityVOList = this.daoVOToTemporalNormalizedHistoryRepresentationVO(entityDaoVOList, dataModelCacheVO, accept);

            } else if (queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.TEMPORAL_VALUES.getCode())) {
                Integer lastN = queryVO.getLastN();
                commonEntityVOList = this.daoVOToTemporalFullRepresentationVO(entityDaoVOList, dataModelCacheVO, lastN, accept);
                commonEntityVOList = this.daoVOToTemporalTemporalValuesRepresentationVO(commonEntityVOList);

            } else {
                Integer lastN = queryVO.getLastN();
                commonEntityVOList = this.daoVOToTemporalFullRepresentationVO(entityDaoVOList, dataModelCacheVO, lastN, accept);
            }
        }

        CommonEntityVO commonEntityVO;

        if (commonEntityVOList != null && commonEntityVOList.size() > 0) {
            commonEntityVO = commonEntityVOList.get(0);
        } else {
            //????????? ???????????? ?????? ??????, ????????? ????????? ?????? ??? ?????? ?????????
            commonEntityVO = new CommonEntityVO();
        }
        return commonEntityVO;
    }


    @Override
    public Integer selectCount(QueryVO queryVO) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType : ").append(queryVO.getType())
                    .append(", params(queryVO) : ").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        if(ValidateUtil.isEmptyData(queryVO.getType())) {
            List<DataModelCacheVO> dataModelCacheVOs = dataModelManager.getTargetDataModelByQueryUri(queryVO.getLinks(), queryVO);
            return getEntityCountWithMultiType(queryVO, dataModelCacheVOs);

        } else if(queryVO.getType().contains(",")) {
            String[] typeArrs = queryVO.getType().split(",");
            List<DataModelCacheVO> dataModelCacheVOs = new ArrayList<>();
            for(String type : typeArrs) {
                DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), type);
                if(dataModelCacheVO != null) {
                    dataModelCacheVOs.add(dataModelCacheVO);
                }
            }
            return getEntityCountWithMultiType(queryVO, dataModelCacheVOs);
        } else {
            return getEntityCountWithType(queryVO);
        }
    }

    private Integer getEntityCountWithMultiType(QueryVO queryVO, List<DataModelCacheVO> dataModelCacheVOs) {
        if (dataModelCacheVOs == null) {
            throw new NgsiLdBadRequestException(ErrorCode.NOT_EXIST_ENTITY, "Not Exist EntityTypes . Context=" + String.join(",", queryVO.getLinks()));
        }

        Integer totalCount = 0;

        try {
            for (DataModelCacheVO dataModelCacheVO : dataModelCacheVOs) {
                if(dataModelCacheVO.getCreatedStorageTypes() != null
                        && dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {

                    if(dataModelCacheVO.getDataModelVO().getTypeUri() == null) {
                        log.warn("SelectCount Invalid DataModel. typeUri is null. dataModelId={}", dataModelCacheVO.getDataModelVO().getId());
                        continue;
                    }

                    QueryVO innerQueryVO = (QueryVO) SerializationUtils.clone(queryVO);
                    innerQueryVO.setType(dataModelCacheVO.getDataModelVO().getTypeUri());
                    innerQueryVO.setLinks(null);
                    Integer cnt = this.getEntityCountWithType(innerQueryVO);
                    if (!ValidateUtil.isEmptyData(cnt)) {
                        totalCount = totalCount + cnt;
                    }
                }
            }
        } catch (NgsiLdBadRequestException ne) {
            log.warn("selectCount error", ne);
        }

        // Entity ?????? ?????? ??????
        return totalCount;
    }


    private Integer getEntityCountWithType(QueryVO queryVO) {
        DataModelCacheVO dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), queryVO.getType());
        if (dataModelCacheVO == null) {
            throw new NgsiLdBadRequestException(ErrorCode.NOT_EXIST_ENTITY, "Invalid Type. entityType=" + queryVO.getType() + ", link=" + queryVO.getLinks());
        }
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        // ?????????????????? ???????????? ?????? ???????????? ?????? ?????? (???????????? ?????? ????????? ?????? ????????? ?????? storageType??? ???????????? ?????? ??????)
        if(dataModelCacheVO.getCreatedStorageTypes() == null
                || !dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {
            return 0;
        }

        // Entity ?????? ?????? ??????
        return entityDAO.selectCount(queryVO);
    }



    @Override
    public Integer selectTemporalCount(QueryVO queryVO) {

        if (log.isDebugEnabled()) {
            StringBuilder requestParams = new StringBuilder();
            requestParams.append(", params(queryVO) : ").append(queryVO.toString());
            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }

        // ???????????? ????????? ??????
        if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
            if(dataModelManager.getDatasetCache(queryVO.getDatasetId()) == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXISTS_DATASET, "Not exist dataset. datasetId=" + queryVO.getDatasetId());
            }
        }

        if(ValidateUtil.isEmptyData(queryVO.getType())) {
            List<DataModelCacheVO> dataModelCacheVOs = dataModelManager.getTargetDataModelByQueryUri(queryVO.getLinks(), queryVO);
            if (dataModelCacheVOs == null) {
                throw new NgsiLdBadRequestException(ErrorCode.NOT_EXIST_ENTITY, "Not Exist EntityTypes . Link=" + String.join(",", queryVO.getLinks()));
            }

            Integer totalCount = 0;

            for (DataModelCacheVO dataModelCacheVO : dataModelCacheVOs) {
                if(dataModelCacheVO.getCreatedStorageTypes() != null
                        && dataModelCacheVO.getCreatedStorageTypes().contains(this.getStorageType())) {

                    if(dataModelCacheVO.getDataModelVO().getTypeUri() == null) {
                        log.warn("selectTemporalCount Invalid DataModel. typeUri is null. dataModelId={}", dataModelCacheVO.getDataModelVO().getId());
                        continue;
                    }

                    QueryVO innerQueryVO = (QueryVO) SerializationUtils.clone(queryVO);
                    innerQueryVO.setType(dataModelCacheVO.getDataModelVO().getTypeUri());
                    innerQueryVO.setLinks(null);
                    Integer cnt = this.selectTemporalCountWithType(innerQueryVO);
                    if (!ValidateUtil.isEmptyData(cnt)) {
                        totalCount = totalCount + cnt;
                    }
                }
            }
            return totalCount;
        } else {
            return selectTemporalCountWithType(queryVO);
        }
    }

    public Integer selectTemporalCountWithType(QueryVO queryVO) {

        if (log.isDebugEnabled()) {

            StringBuilder requestParams = new StringBuilder();
            requestParams.append("entityType=").append(queryVO.getType())
                    .append(", params(queryVO)=").append(queryVO.toString());

            //?????? ?????? ??????
            log.debug("request msg='{}'", requestParams);
        }
        if (queryVO.getDataModelCacheVO() != null) {
            queryVO.setDataModelCacheVO(null);
        }
        QueryVO copiedQueryVO = (QueryVO) SerializationUtils.clone(queryVO);
        copiedQueryVO.setLimit(null);
        copiedQueryVO.setOffset(null);

        List<CommonEntityVO> commonEntityVOs = this.selectTemporal(copiedQueryVO, Constants.APPLICATION_LD_JSON_VALUE);
        int totalCount = 0;
        if (commonEntityVOs != null) {
            totalCount = commonEntityVOs.size();
        }
        return totalCount;
    }


    /**
     * type ?????? query ???, limit & offset ??????
     *
     * @param totalCommonEntityVOs
     * @param limit
     * @param offset
     * @return
     */
    private List<CommonEntityVO> extractSubListWithoutType(List<CommonEntityVO> totalCommonEntityVOs, Integer limit, Integer offset) {
        Integer startIndex = 0;
        Integer endIndex = totalCommonEntityVOs.size();

        if (limit != null && offset != null) {
            if (endIndex > (limit + offset)) {
                endIndex = limit + offset;
            }
            if (offset > endIndex) {
                startIndex = endIndex;
            } else {
                startIndex = offset;
            }
            totalCommonEntityVOs = totalCommonEntityVOs.subList(startIndex, endIndex);
        } else if (limit != null && offset == null) {
            if (endIndex > limit) {
                endIndex = limit;
            }
            totalCommonEntityVOs = totalCommonEntityVOs.subList(startIndex, endIndex);
        }

        return totalCommonEntityVOs;
    }
}
