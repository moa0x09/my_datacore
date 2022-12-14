package kr.re.keti.sc.dataservicebroker.entities.service.rdb;

import static kr.re.keti.sc.dataservicebroker.common.code.Constants.DEFAULT_SRID;

import java.util.*;

import org.postgis.PGgeometry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.re.keti.sc.dataservicebroker.common.code.Constants;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeValueType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.BigDataStorageType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DbColumnType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DefaultAttributeKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DefaultDbColumnName;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.HistoryStoreType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.Operation;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.PropertyKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.RetrieveOptions;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.UseYn;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdBadRequestException;
import kr.re.keti.sc.dataservicebroker.common.vo.AttributeVO;
import kr.re.keti.sc.dataservicebroker.common.vo.CommonEntityFullVO;
import kr.re.keti.sc.dataservicebroker.common.vo.CommonEntityVO;
import kr.re.keti.sc.dataservicebroker.common.vo.EntityProcessVO;
import kr.re.keti.sc.dataservicebroker.common.vo.GeoPropertiesVO;
import kr.re.keti.sc.dataservicebroker.common.vo.GeoPropertyVO;
import kr.re.keti.sc.dataservicebroker.common.vo.ProcessResultVO;
import kr.re.keti.sc.dataservicebroker.common.vo.PropertiesVO;
import kr.re.keti.sc.dataservicebroker.common.vo.PropertyVO;
import kr.re.keti.sc.dataservicebroker.common.vo.QueryVO;
import kr.re.keti.sc.dataservicebroker.common.vo.RelationshipVO;
import kr.re.keti.sc.dataservicebroker.common.vo.RelationshipsVO;
import kr.re.keti.sc.dataservicebroker.common.vo.entities.DynamicEntityDaoVO;
import kr.re.keti.sc.dataservicebroker.common.vo.entities.DynamicEntityFullVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.Attribute;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelCacheVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelDbColumnVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelStorageMetadataVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.ObjectMember;
import kr.re.keti.sc.dataservicebroker.entities.dao.EntityDAOInterface;
import kr.re.keti.sc.dataservicebroker.entities.service.DefaultEntitySVC;
import kr.re.keti.sc.dataservicebroker.util.DateUtil;
import kr.re.keti.sc.dataservicebroker.util.ObservedAtReverseOrder;
import kr.re.keti.sc.dataservicebroker.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RdbEntitySVC extends DefaultEntitySVC {

	@Value("${entity.retrieve.include.datasetid:N}")
	private String retrieveIncludeDatasetid; // ?????? ??? datasetId ????????????
	@Value("${entity.default.history.store.type:full}")
	private String defaultHistoryStoreType; // ????????? ??? ????????? ?????? ?????? ?????? ?????? ?????? ??????

    @Autowired
    protected ObjectMapper objectMapper;

    @Override
	protected String getTableName(DataModelCacheVO dataModelCacheVO) {
		return dataModelCacheVO.getDataModelStorageMetadataVO().getRdbTableName();
	}
    
    @Override
    protected BigDataStorageType getStorageType() {
    	return BigDataStorageType.RDB;
    }

    @Override
	public void setEntityDAOInterface(EntityDAOInterface<DynamicEntityDaoVO> entityDAO) {
    	this.entityDAO = entityDAO;
	}

    /**
     * Dao -> ?????? ???????????? ?????? (* full representation)
     * @param dynamicEntityDaoVO
     * @param dataModelCacheVO
     * @return CommonEntityVO (EntityFullVO)
     */
    @Override
    public CommonEntityVO daoVOToFullRepresentationVO(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelCacheVO dataModelCacheVO, boolean includeSysAttrs, List<String> attrs) {

        CommonEntityFullVO commonEntityFullVO = new CommonEntityFullVO();
        addDefaultFullRepresentationField(commonEntityFullVO, dynamicEntityDaoVO, dataModelCacheVO, includeSysAttrs);

        DataModelStorageMetadataVO storageMetadataVO = dataModelCacheVO.getDataModelStorageMetadataVO();
        Map<String, DataModelDbColumnVO> dbColumnInfoVOMap = storageMetadataVO.getDbColumnInfoVOMap();

        for (DataModelDbColumnVO dbColumnInfoVO : dbColumnInfoVOMap.values()) {

            String columnName = dbColumnInfoVO.getColumnName();
            Object attributeValue = dynamicEntityDaoVO.get(columnName.toLowerCase());
            List<String> attributeIds = dbColumnInfoVO.getHierarchyAttributeIds();

            //?????? attributeId ????????????
            String rootAttrId = attributeIds.get(0);
            Attribute rootAttribute = dataModelCacheVO.getRootAttribute(rootAttrId);

            //?????? ????????? SKIP
            // 1. DB?????? ????????? attribute value??? ?????? ??????, SKIP
            if (attributeValue == null || commonEntityFullVO.containsKey(rootAttrId)) {
                continue;
            }

            Map<String, AttributeVO> resultMap = (Map<String, AttributeVO>) converDaoToAttribute(dynamicEntityDaoVO, storageMetadataVO, rootAttribute, null, includeSysAttrs);
            commonEntityFullVO.putAll(resultMap);
        }

        // attrs filtering ????????? ?????? ?????? ????????? attributes ??? attrs ????????? ?????????????????? ??????
        if (validateAttrsFiltering(attrs, commonEntityFullVO)) {
            return null;
        }

        return commonEntityFullVO;
    }

    private boolean validateAttrsFiltering(List<String> attrs, CommonEntityFullVO commonEntityFullVO) {
        if(ValidateUtil.isEmptyData(attrs)) {
           return false;
        }

        boolean isMatch = false;
        for(String attr : attrs) {
            if(commonEntityFullVO.containsKey(attr)) {
                isMatch = true;
                break;
            }
        }
        if(isMatch) {
            return false;
        }
        return true;
    }


    /**
     * DB?????? ?????? DaoVO ??? entity?????? ?????? ????????? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param rootAttribute
     * @param hierarchyAttrNames
     * @param isChildAttribute
     * @param includeSysAttrs
     * @return
     */
    private Map<String, AttributeVO> converDaoToAttribute(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO,
    		Attribute rootAttribute, List<String> hierarchyAttrNames, boolean isChildAttribute, boolean includeSysAttrs) {

    	if(hierarchyAttrNames == null) {
    		hierarchyAttrNames = new ArrayList<>();
    	}
    	hierarchyAttrNames.add(rootAttribute.getName());

    	String currentAttrName = hierarchyAttrNames.get(hierarchyAttrNames.size()-1);

        // 1. Property ??? ??????
        Map<String, AttributeVO> convertedMap = new LinkedHashMap<>();
        AttributeVO attributeVO = null;
        if (rootAttribute.getValueType() == AttributeValueType.GEO_JSON) {
        	List<String> columnNames = dataModelManager.getColumnNamesByStorageMetadata(storageMetadataVO, hierarchyAttrNames);
        	if(columnNames != null) {
        		for(String columnName : columnNames) {
        			if(columnName.endsWith(Constants.COLUMN_DELIMITER + DEFAULT_SRID)) {
        				PGgeometry pGgeometry = (PGgeometry) dynamicEntityDaoVO.get(columnName.toLowerCase());
                        if(pGgeometry != null) {
                            attributeVO = valueToAttributeVO(rootAttribute, pGgeometry.getGeometry());
                            break;
                        }
        			}
        		}
        	}

        } else {
        	String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, hierarchyAttrNames);
        	if(columnName != null) {
        		Object value = dynamicEntityDaoVO.get(columnName.toLowerCase());
                if (value != null) {
                    attributeVO = valueToAttributeVO(rootAttribute, value);
                }
        	}
        }

        if (attributeVO != null) {
        	if(isChildAttribute) {
        		convertedMap.put(rootAttribute.getName(), attributeVO);
        	} else {
        		convertedMap.put(hierarchyAttrNames.get(hierarchyAttrNames.size()-1), attributeVO);
        	}
        }

        // 2. ObjectMember ??? ??????
        List<ObjectMember> objectMembers = rootAttribute.getObjectMembers();
        if (objectMembers != null) {

        	Object objectMemberMap = objectMemberToObject(rootAttribute, dynamicEntityDaoVO, storageMetadataVO, hierarchyAttrNames);
        	if(objectMemberMap != null) {
        		if(convertedMap.get(currentAttrName) != null) {
            		convertedMap.get(currentAttrName).put(PropertyKey.TYPE.getCode(), AttributeType.PROPERTY.getCode());
            		convertedMap.get(currentAttrName).put(PropertyKey.VALUE.getCode(), objectMemberMap);
            	} else {
            		convertedMap.put(currentAttrName, valueToAttributeVO(rootAttribute, objectMemberMap));
            	}
        	}
        }

        // 3. observedAt ??? ??????
        if (rootAttribute.getHasObservedAt() != null && rootAttribute.getHasObservedAt()) {
        	if(convertedMap.get(rootAttribute.getName()) != null) {
        		addObservedAt(dynamicEntityDaoVO, storageMetadataVO, convertedMap.get(rootAttribute.getName()), hierarchyAttrNames);
        	}
        }
        
        // 4. unitCode ??? ??????
        if (rootAttribute.getHasUnitCode() != null && rootAttribute.getHasUnitCode()) {
        	if(convertedMap.get(rootAttribute.getName()) != null) {
        		addUnitCode(dynamicEntityDaoVO, storageMetadataVO, convertedMap.get(rootAttribute.getName()), hierarchyAttrNames);
        	}
        }
        
        // 5. sysAttrs (createdAt, modifiedAt) ??? ??????
        if (includeSysAttrs) {
        	if(convertedMap.get(rootAttribute.getName()) != null) {
        		addCreatedAt(dynamicEntityDaoVO, storageMetadataVO, convertedMap.get(rootAttribute.getName()), hierarchyAttrNames);
        	}
        	if(convertedMap.get(rootAttribute.getName()) != null) {
        		addModifiedAt(dynamicEntityDaoVO, storageMetadataVO, convertedMap.get(rootAttribute.getName()), hierarchyAttrNames);
        	}
        }
        
        // 6. Child Attribute ??? ??????
        List<Attribute> childAttributes = rootAttribute.getChildAttributes();
        if (childAttributes != null) {

            Map<String, AttributeVO> childAttributeMap = new LinkedHashMap<>();

            for (Attribute childAttribute : childAttributes) {
                Map<String, AttributeVO> subChildAttributeMap = (Map<String, AttributeVO>) converDaoToAttribute(dynamicEntityDaoVO, storageMetadataVO, childAttribute,
                        new ArrayList(hierarchyAttrNames), true, includeSysAttrs);
                childAttributeMap.putAll(subChildAttributeMap);
            }
            if(convertedMap.get(currentAttrName) != null) {
        		convertedMap.get(currentAttrName).putAll(childAttributeMap);
        	} else {
        		AttributeVO childAttributeVO = new AttributeVO();
        		childAttributeVO.putAll(childAttributeMap);
        		convertedMap.put(currentAttrName, childAttributeVO);
        	}
        }
        
        return convertedMap;
    }

    /**
     * DB ?????? ??????(dao) -> Map(attribute)?????? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param rootAttribute
     * @param hierarchyAttrNames
     * @param includeSysAttrs
     * @return
     */
    private Map<String, AttributeVO> converDaoToAttribute(DynamicEntityDaoVO dynamicEntityDaoVO, 
    		DataModelStorageMetadataVO storageMetadataVO, Attribute rootAttribute, List<String> hierarchyAttrNames, boolean includeSysAttrs) {
        return converDaoToAttribute(dynamicEntityDaoVO, storageMetadataVO, rootAttribute, hierarchyAttrNames, false, includeSysAttrs);
    }

    /**
     * DB?????? ?????? daoVO ??? entity?????? ????????? ????????? ObjectMember ????????? ??????
     * @param rootAttribute
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param hierarchyAttrNames
     * @return
     */
    private Object objectMemberToObject(Attribute rootAttribute, DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, List<String> hierarchyAttrNames) {

    	List<ObjectMember> objectMembers = rootAttribute.getObjectMembers();

    	Map<String, Object> objectMemberMap = objectMembersToMap(objectMembers, dynamicEntityDaoVO, storageMetadataVO, hierarchyAttrNames);

        if (rootAttribute.getValueType() == AttributeValueType.ARRAY_OBJECT) {
            return objectMemberMapToArrayObject(objectMemberMap);
        } else {
            return objectMemberMap;
        }
    }

    /**
     * DB?????? ?????? daoVO ??? entity?????? ????????? ????????? ObjectMember ????????? ??????
     * @param objectMembers
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param hierarchyAttrNames
     * @return
     */
    private Map<String, Object> objectMembersToMap(List<ObjectMember> objectMembers,
    		DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, List<String> hierarchyAttrNames) {

    	Map<String, Object> objectMemberMap = null;
    	for (ObjectMember objectMember : objectMembers) {
    		String objectMemberName = objectMember.getName();
    		
    		List<String> objectMemberAttrNames = new ArrayList<>(hierarchyAttrNames);
    		objectMemberAttrNames.add(objectMember.getName());
    		
            if(objectMember.getObjectMembers() != null) {
            	Map<String, Object> innerMap = objectMembersToMap(objectMember.getObjectMembers(), dynamicEntityDaoVO, storageMetadataVO, objectMemberAttrNames);
            	if(innerMap != null && !innerMap.isEmpty()) {
            		if(objectMemberMap == null) objectMemberMap = new HashMap<>();
            		objectMemberMap.put(objectMemberName, innerMap);
            	}
            } else {
            	String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, objectMemberAttrNames);
                Object value = dynamicEntityDaoVO.get(columnName);
                if(!ValidateUtil.isEmptyData(value)) {
                	if(objectMemberMap == null) objectMemberMap = new HashMap<>();
                	objectMemberMap.put(objectMemberName, value);
                }
            }
        }
    	return objectMemberMap;
	}

    /**
     * AttributeVO??? observedAt ??? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param attributeVO
     * @param hierarchyAttrNames
     */
	private void addObservedAt(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, AttributeVO attributeVO, List<String> hierarchyAttrNames) {
		List<String> observedAttAtrNames = new ArrayList<>(hierarchyAttrNames);
		observedAttAtrNames.add(PropertyKey.OBSERVED_AT.getCode());
    	String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, observedAttAtrNames);
        Object value = dynamicEntityDaoVO.get(columnName.toLowerCase());
    	attributeVO.setObservedAt((Date) value);
    }
	
    /**
     * AttributeVO??? unitCode ??? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param attributeVO
     * @param hierarchyAttrNames
     */
	private void addUnitCode(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, AttributeVO attributeVO, List<String> hierarchyAttrNames) {
		List<String> unitCodeAttrNames = new ArrayList<>(hierarchyAttrNames);
		unitCodeAttrNames.add(PropertyKey.UNIT_CODE.getCode());
		String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, unitCodeAttrNames);
        Object value = dynamicEntityDaoVO.get(columnName.toLowerCase());
       	attributeVO.setUnitCode((String) value);
    }
	
    /**
     * AttributeVO??? createdAt ??? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param attributeVO
     * @param hierarchyAttrNames
     */
	private void addCreatedAt(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, AttributeVO attributeVO, List<String> hierarchyAttrNames) {
		List<String> createdAtAttrNames = new ArrayList<>(hierarchyAttrNames);
		createdAtAttrNames.add(PropertyKey.CREATED_AT.getCode());
		String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, createdAtAttrNames);
        Object value = dynamicEntityDaoVO.get(columnName.toLowerCase());
    	attributeVO.setCreatedAt((Date) value);
    }
	
    /**
     * AttributeVO??? modifedAt ??? ??????
     * @param dynamicEntityDaoVO
     * @param storageMetadataVO
     * @param attributeVO
     * @param hierarchyAttrNames
     */
	private void addModifiedAt(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelStorageMetadataVO storageMetadataVO, AttributeVO attributeVO, List<String> hierarchyAttrNames) {
		List<String> modifiedAtAttrNames = new ArrayList<>(hierarchyAttrNames);
		modifiedAtAttrNames.add(PropertyKey.MODIFIED_AT.getCode());
		String columnName = dataModelManager.getColumnNameByStorageMetadata(storageMetadataVO, modifiedAtAttrNames);
        Object value = dynamicEntityDaoVO.get(columnName.toLowerCase());
    	attributeVO.setModifiedAt((Date) value);
    }

    /**
     * attribute ?????????  AttributeVO(*PropertyVO or RelationShipVO) ????????? ??????
     * @param attribute
     * @param result
     * @return
     */
    private AttributeVO valueToAttributeVO(Attribute attribute, Object result) {

        AttributeVO attributeVO = null;
        if (attribute.getAttributeType() == AttributeType.PROPERTY) {
            PropertyVO propertyVO = new PropertyVO();
            propertyVO.setValue(result);
            attributeVO = propertyVO;
        } else if (attribute.getAttributeType() == AttributeType.RELATIONSHIP) {

            RelationshipVO relationshipVO = new RelationshipVO();
            relationshipVO.setObject(result);
            attributeVO = relationshipVO;

        } else if (attribute.getAttributeType() == AttributeType.GEO_PROPERTY) {
            GeoPropertyVO geoPropertyVO = new GeoPropertyVO();
            geoPropertyVO.setValue(result);
            attributeVO = geoPropertyVO;
        }

        return attributeVO;
    }

    /**
     * map - arr, arr ?????????  arr -> map ?????? ?????? (* congestionIndexPrediction ?????????)
     *
     * @param objectMemberMap
     * @return
     */
    private List<Map<String, Object>> objectMemberMapToArrayObject(Map<String, Object> objectMemberMap){

        List<Map<String, Object>> arrayObject = new ArrayList<>();

        objectMemberMap.forEach((key, obj) -> {
            Object[] objectArr = (Object[]) obj;
            if(objectArr != null) {
            	for (int idx = 0; idx < objectArr.length; idx++) {

                    if (idx >= arrayObject.size()) {
                        Map<String, Object> tmp = new HashMap<>();
                        tmp.put(key, objectArr[idx]);

                        arrayObject.add(tmp);

                    } else {
                    	Map<String, Object> tmp = (Map<String, Object>) arrayObject.get(idx);
                        tmp.put(key, objectArr[idx]);

                        arrayObject.set(idx, tmp);
                    }

                }
            }
        });

        if(arrayObject.size() == 0) {
        	return null;
        }
        return arrayObject;
    }


    /**
     * FullRepresentationVO ?????? ?????? (id, type, createAt, modifiedAt)
     * FullRepresentationVO ?????? ?????? (datasetId)
     */
    private CommonEntityFullVO addDefaultFullRepresentationField(CommonEntityFullVO commonEntityFullVO,
                                                        DynamicEntityDaoVO dynamicEntityDaoVO,
                                                        DataModelCacheVO dataModelCacheVO,
                                                        boolean includeSysAttrs) {

        commonEntityFullVO.setId(dynamicEntityDaoVO.getId());
        commonEntityFullVO.setType(dataModelCacheVO.getDataModelVO().getType());

        if(includeSysAttrs) {
        	if (dynamicEntityDaoVO.containsKey(DefaultDbColumnName.CREATED_AT.getCode())) {
        		commonEntityFullVO.setCreatedAt((Date) dynamicEntityDaoVO.get(DefaultDbColumnName.CREATED_AT.getCode()));
            }
            if (dynamicEntityDaoVO.containsKey(DefaultDbColumnName.MODIFIED_AT.getCode())) {
        		commonEntityFullVO.setModifiedAt((Date) dynamicEntityDaoVO.get(DefaultDbColumnName.MODIFIED_AT.getCode()));
            }
        }
        // ?????? entity ?????? ??? ????????? ?????? (modifiedAt??????????????? sysAttrs??? ?????? ??? ????????? ?????? ?????? ????????? ?????? ?????? ?????? ??????)
        if (dynamicEntityDaoVO.containsKey(DefaultDbColumnName.MODIFIED_AT.getCode())) {
    		commonEntityFullVO.setSortKey((Date) dynamicEntityDaoVO.get(DefaultDbColumnName.MODIFIED_AT.getCode()));
        }

        if (UseYn.YES.getCode().equals(retrieveIncludeDatasetid)
        		&& dynamicEntityDaoVO.containsKey(DefaultDbColumnName.DATASET_ID.getCode())) {
    		commonEntityFullVO.setDatasetId((String) dynamicEntityDaoVO.get(DefaultDbColumnName.DATASET_ID.getCode()));
        }
        return commonEntityFullVO;
    }


    /**
     * SimplifiedRepresentationVO ??? ?????? (* options=KeyValues)
     *
     * @param dynamicEntityDaoVO
     * @param entitySchemaCacheVO
     * @return CommonEntityVO (OffStreetParkingSimpleVO)
     */
    @Override
    public CommonEntityVO daoVOToSimpleRepresentationVO(DynamicEntityDaoVO dynamicEntityDaoVO, DataModelCacheVO entitySchemaCacheVO, List<String> attrs) {

        CommonEntityVO commonEntityVO = daoVOToFullRepresentationVO(dynamicEntityDaoVO, entitySchemaCacheVO, false, attrs);

        if(commonEntityVO != null) {
            for (String key : commonEntityVO.keySet()) {
                Object object = commonEntityVO.get(key);
                Object value = simplify(key, object);
                commonEntityVO.replace(key, value);
            }
        }

        return commonEntityVO;
    }


    private Object simplify(String key, Object objectValue) {
        if (objectValue instanceof PropertyVO) {
            PropertyVO propertyVO = (PropertyVO) objectValue;
            return propertyVO.getValue();
        } else if (objectValue instanceof RelationshipVO) {
            RelationshipVO relationshipVO = (RelationshipVO) objectValue;
            return relationshipVO.getObject();
        } else if (objectValue instanceof GeoPropertyVO) {
            GeoPropertyVO geoPropertyVO = (GeoPropertyVO) objectValue;
            return geoPropertyVO.getValue();
        } else if (objectValue instanceof AttributeVO) {
            AttributeVO attributeVO = (AttributeVO) objectValue;
            if (attributeVO.getType().equalsIgnoreCase(AttributeType.PROPERTY.getCode())) {
                return attributeVO.get(PropertyKey.VALUE.getCode());
            } else if (attributeVO.getType().equalsIgnoreCase(AttributeType.RELATIONSHIP.getCode())) {
                return attributeVO.get(PropertyKey.OBJECT.getCode());
            } else {
                throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER, "should include value or object");
            }
        } else {
            return objectValue;
        }
    }

    /**
     * temporal ?????? ???????????? ??????
     *
     * @param entityDaoVOList
     * @return List<CommonEntityVO> (OffStreetParkingTemporalFullVO List)
     */
    @Override
    public List<CommonEntityVO> daoVOToTemporalFullRepresentationVO(List<DynamicEntityDaoVO> entityDaoVOList, DataModelCacheVO dataModelCacheVO, Integer lastN, String accept) {

        List<CommonEntityVO> commonEntityVOS = daoVOToTemporalNormalizedHistoryRepresentationVO(entityDaoVOList, dataModelCacheVO, accept);


        Map<String, CommonEntityVO> filterdMap = new HashMap<>();
        Map<String, String> tempDatasetMap = new HashMap<>();

        for (CommonEntityVO commonEntityVO : commonEntityVOS) {

            CommonEntityVO observedAtEntityVO = (CommonEntityVO) commonEntityVO.clone();
            String id = (String) commonEntityVO.get(DefaultAttributeKey.ID.getCode());
            tempDatasetMap.put(id, (String) commonEntityVO.get(DefaultAttributeKey.DATASET_ID.getCode()));
            List<String> context = null;
            if (accept.equals(Constants.APPLICATION_LD_JSON_VALUE)) {

                context = dataModelCacheVO.getDataModelVO().getContext();
            }


            for (String key : commonEntityVO.keySet()) {
                //?????? ????????????(@context, id, createdAt ,modifiedAt ,operation ,type) ??????
                if (DefaultAttributeKey.parseType(key) != null) {
                    continue;
                }
            }

            if (filterdMap.containsKey(id)) {
                CommonEntityVO innerCommonEntityVO = filterdMap.get(id);
                for (String key : observedAtEntityVO.keySet()) {

                    Attribute rootAttribute = dataModelCacheVO.getRootAttribute(key);
                    if (rootAttribute == null || rootAttribute.getHasObservedAt() == null || !rootAttribute.getHasObservedAt()) {
                        continue;
                    }

                    if (innerCommonEntityVO.get(key) instanceof List) {
                        // ??? ???????????? ???????????? ?????? ??????
                        AttributeVO attributeVO = (AttributeVO) observedAtEntityVO.get(key);
                        List<AttributeVO> attributeVOList = (List<AttributeVO>) innerCommonEntityVO.get(key);
                        attributeVOList.add(attributeVO);
                        observedAtEntityVO.replace(key, attributeVOList);

                    } else if (innerCommonEntityVO.get(key) instanceof AttributeVO) {

                        // ???????????? ???????????? ??? ??????
                        AttributeVO attributeVO = (AttributeVO) observedAtEntityVO.get(key);
                        List<AttributeVO> attributeVOList = new ArrayList<>();
                        attributeVOList.add(attributeVO);
                        observedAtEntityVO.replace(key, attributeVOList);

                    }

                }
                filterdMap.put(id, innerCommonEntityVO);

            } else {

                for (String key : observedAtEntityVO.keySet()) {
                    if (observedAtEntityVO.get(key) instanceof AttributeVO) {
                        AttributeVO attributeVO = (AttributeVO) observedAtEntityVO.get(key);

                        List<AttributeVO> attributeVOList = new ArrayList<>();
                        attributeVOList.add(attributeVO);
                        observedAtEntityVO.replace(key, attributeVOList);

                    }

                }


                if (observedAtEntityVO != null && observedAtEntityVO.size() > 0) {

                    if (accept.equals(Constants.APPLICATION_LD_JSON_VALUE)) {
                        observedAtEntityVO.setContext(context);
                    }
                    filterdMap.put(id, observedAtEntityVO);

                }
            }
        }

        List<CommonEntityVO> filteredList = new ArrayList<>();
        for (String id : filterdMap.keySet()) {

            CommonEntityFullVO commonEntityVO = (CommonEntityFullVO) filterdMap.get(id);

            // lastN ?????? ??????
            if (lastN != null && lastN > 0) {
                commonEntityVO = retrieveLastN(commonEntityVO, dataModelCacheVO , lastN);
            }
            commonEntityVO.setId(id);
            commonEntityVO.setType(dataModelCacheVO.getDataModelVO().getType());
            filteredList.add(commonEntityVO);
            if (UseYn.YES.getCode().equals(retrieveIncludeDatasetid)
                    && tempDatasetMap.get(id) != null) {
                commonEntityVO.setDatasetId(tempDatasetMap.get(id));
            }
        }

        return filteredList;
    }


    /**
     * temporal ?????? ?????? -> temporalValues ?????? ??????
     *
     * @param commonEntityVOList
     * @return List<CommonEntityVO> (OffStreetParkingTemporalFullVO List)
     */
    @Override
    public List<CommonEntityVO> daoVOToTemporalTemporalValuesRepresentationVO(List<CommonEntityVO> commonEntityVOList) {


        for (CommonEntityVO commonEntityVO : commonEntityVOList) {
            CommonEntityFullVO vo = (CommonEntityFullVO) commonEntityVO;
            for (String key : vo.keySet()) {
                //?????? ????????????(@context, id, createdAt ,modifiedAt ,operation ,type) ?????? SKIP
                if (DefaultAttributeKey.parseType(key) != null) {
                    continue;
                }

                List valueList = (ArrayList) vo.get(key);
                List<Object> midList = new ArrayList<>();
                AttributeVO attributeVO = null;

                for (Object obj : valueList) {

                    attributeVO = (AttributeVO) obj;
                    List<Object> reList = new ArrayList();


                    if (attributeVO.getType().equalsIgnoreCase(AttributeType.PROPERTY.getCode())) {
                        reList.add( attributeVO.get(PropertyKey.VALUE.getCode()));
                    } else if (attributeVO.getType().equalsIgnoreCase(AttributeType.GEO_PROPERTY.getCode())) {
                        reList.add( attributeVO.get(PropertyKey.VALUE.getCode()));
                    }else if (attributeVO.getType().equalsIgnoreCase(AttributeType.RELATIONSHIP.getCode())) {
                        reList.add( attributeVO.get(PropertyKey.OBJECT.getCode()));
                    } else {
                        throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER, "should include value or object");
                    }
                    if (attributeVO.get(PropertyKey.OBSERVED_AT.getCode()) != null) {
                        reList.add(attributeVO.get(PropertyKey.OBSERVED_AT.getCode()));
                    }


                    midList.add(reList);
                }

                if (attributeVO.getType().equalsIgnoreCase(AttributeType.PROPERTY.getCode())) {

                    PropertiesVO propertiesVO = new PropertiesVO();
                    propertiesVO.setValue(midList);
                    vo.replace(key, propertiesVO);

                } else if (attributeVO.getType().equalsIgnoreCase(AttributeType.GEO_PROPERTY.getCode())) {

                    GeoPropertiesVO geoPropertiesVO = new GeoPropertiesVO();
                    geoPropertiesVO.setValue(midList);
                    vo.replace(key, geoPropertiesVO);

                } else if (attributeVO.getType().equalsIgnoreCase(AttributeType.RELATIONSHIP.getCode())) {


                    RelationshipsVO relationshipsVO = new RelationshipsVO();
                    relationshipsVO.setObject(midList);
                    vo.replace(key, relationshipsVO);
                } else {
                    vo.replace(key, midList);
                }


            }
        }
        return commonEntityVOList;
    }

    /**
     * DB?????? ????????? daoVO ??? NormalizedHistory ???????????? ??????
     *
     * @param entityDaoVOList     (EntityDaoVO List)
     * @param dataModelCacheVO
     * @return List<CommonEntityVO> (EntityTemporalFullVO List)
     */
    @Override
    public List<CommonEntityVO> daoVOToTemporalNormalizedHistoryRepresentationVO(List<DynamicEntityDaoVO> entityDaoVOList, DataModelCacheVO dataModelCacheVO, String accept) {
        List<CommonEntityVO> commonEntityVOList = new ArrayList<>();

        for (DynamicEntityDaoVO dynamicEntityDaoVO : entityDaoVOList) {

            // 1. options??? ?????? ?????? ??????, Full Representation (normalizedHistory)
            CommonEntityVO commonEntityVO = this.daoVOToFullRepresentationVO(dynamicEntityDaoVO, dataModelCacheVO, false, null);
            if(commonEntityVO != null) {
                commonEntityVOList.add(commonEntityVO);


                // 2. ?????? header??? accept??? 'application/ld+json' ??? ?????? @context ?????? ??????
                if (accept.equals(Constants.APPLICATION_LD_JSON_VALUE)) {
                    commonEntityVO.setContext(dataModelCacheVO.getDataModelVO().getContext());
                }
            }
        }

        return commonEntityVOList;
    }


    /**
     * Operation ???????????? ?????? ?????? ??????
     *
     * @param entityProcessVOList ??????VO?????????
     */
    @Override
    public void storeEntityStatusHistory(List<EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO>> entityProcessVOList) {

        // 1. ???????????? ?????? ????????? ?????? ??????
        List<DynamicEntityDaoVO> createPartialHistoryVOList = new ArrayList<>();
        List<DynamicEntityDaoVO> createFullHistoryTargetVOList = new ArrayList<>();

        StringBuilder logMessage = new StringBuilder();

        for (EntityProcessVO<DynamicEntityFullVO, DynamicEntityDaoVO> entityProcessVO : entityProcessVOList) {

            DynamicEntityDaoVO entityDaoVO = entityProcessVO.getEntityDaoVO();
            ProcessResultVO processResultVO = entityProcessVO.getProcessResultVO();

            // 2. ?????? ????????? ?????????????????? ??????
            if (!processResultVO.isProcessResult()) {
                continue;
            }

            if (logMessage.length() == 0) {
                logMessage.append("Process SUCCESS.");
                if(entityProcessVO.getDatasetId() != null) {
                    logMessage.append(" datasetId=").append(entityProcessVO.getDatasetId());
                } else {
                    logMessage.append(" Not include datasetId");
                }
            }
            logMessage.append(System.lineSeparator()).append("\t")
                    .append("eventTime=").append(DateUtil.dateToDbFormatString(entityDaoVO.getModifiedAt()))
                    .append(", id=").append(entityDaoVO.getId())
                    .append(", processOperation=").append(processResultVO.getProcessOperation());

            // 3. ?????? ????????? Operation ??? ??????
            entityDaoVO.setOperation(processResultVO.getProcessOperation());

            // 4. ???????????? ?????? ??????
            HistoryStoreType historyStoreType = null;
            if(entityProcessVO.getDatasetId() != null) {
            	historyStoreType = dataModelManager.getHistoryStoreType(entityProcessVO.getDatasetId());
            } else {
            	historyStoreType = HistoryStoreType.parseType(defaultHistoryStoreType);
            }

            // PARTIAL, FULL ?????? ??????
            if(historyStoreType == HistoryStoreType.ALL) {
            	createFullHistoryTargetVOList.add(entityDaoVO);
                createPartialHistoryVOList.add(entityDaoVO);
            // FULL ????????? ??????
            } else if(historyStoreType == HistoryStoreType.FULL) {
            	createFullHistoryTargetVOList.add(entityDaoVO);
            // PARTIAL ????????? ??????
            } else if(historyStoreType == HistoryStoreType.PARTIAL) {
            	createPartialHistoryVOList.add(entityDaoVO);
            // ?????? ???????????? ??????
            } else if(historyStoreType == HistoryStoreType.NONE) {

            } else {
            	// default 'FULL'
            	createFullHistoryTargetVOList.add(entityDaoVO);
            }
        }

        if (logMessage.length() > 0) log.info(logMessage.toString());

        // 5. ?????? ?????? ??????
        // 5-1 Partial ?????? ?????? (???????????? Entity ?????????????????? ??????)
        if (createPartialHistoryVOList != null && createPartialHistoryVOList.size() > 0) {
        	
        	// property ??? created_at ????????? ?????? Entity ????????? ??????
            for (DynamicEntityDaoVO entityDaoVO : createFullHistoryTargetVOList) {
            	// Delete ??? ??????
                if (Operation.DELETE_ENTITY == entityDaoVO.getOperation()) {
                    continue;
                }

                QueryVO queryVO = new QueryVO();
                queryVO.setId(entityDaoVO.getId());
                queryVO.setDatasetId(entityDaoVO.getDatasetId());
                queryVO.setType(entityDaoVO.getEntityType());
                queryVO.setOptions(RetrieveOptions.SYS_ATTRS.getCode());
                queryVO.setLinks(entityDaoVO.getContext());
                DynamicEntityDaoVO entityFullDaoVO = entityDAO.selectById(queryVO, true);
                if (entityFullDaoVO == null) {
                    log.warn("Store entity Partial history error. Now exist Entity id=" + entityDaoVO.getId());
                    continue;
                }

                for(Map.Entry<String, Object> entry : entityDaoVO.entrySet()) {
                	String key = entry.getKey();

                	// _created_at ?????? ????????? ?????? ?????????
                	if(key.endsWith(Constants.COLUMN_DELIMITER + PropertyKey.CREATED_AT.getCode())) {
                		if(entityFullDaoVO.containsKey(key.toLowerCase())) {
                			entityDaoVO.put(key, entityFullDaoVO.get(key.toLowerCase()));
                		}
                	}
                }
            }

            try {
                entityDAO.bulkCreateHist(createPartialHistoryVOList);
            } catch (Exception e) {
                log.error("Store entity PARTIAL history error", e);
            }
        }

        // 5-2. Full ?????? ?????? (Entity ??? ????????? ?????? ??? ????????? ?????? ??????????????? ??????)
        if (createFullHistoryTargetVOList != null && createFullHistoryTargetVOList.size() > 0) {

            List<DynamicEntityDaoVO> createFullHistoryVOList = new ArrayList<>();

            // Full ??????????????? ?????? Entity ????????? ??????
            for (DynamicEntityDaoVO entityDaoVO : createFullHistoryTargetVOList) {

                // Delete ??? Full ?????? ???????????? ??????
                if (Operation.DELETE_ENTITY == entityDaoVO.getOperation()) {
                    continue;
                }

                QueryVO queryVO = new QueryVO();
                queryVO.setId(entityDaoVO.getId());
                queryVO.setDatasetId(entityDaoVO.getDatasetId());
                queryVO.setType(entityDaoVO.getEntityType());
                queryVO.setOptions(RetrieveOptions.SYS_ATTRS.getCode());
                queryVO.setLinks(entityDaoVO.getContext());
                DynamicEntityDaoVO entityFullDaoVO = entityDAO.selectById(queryVO, true);
                if (entityFullDaoVO == null) {
                    log.warn("Store entity FULL history error. Now exist Entity id=" + entityDaoVO.getId());
                    continue;
                }

                entityFullDaoVO.setDbTableName(entityDaoVO.getDbTableName());
                entityFullDaoVO.setDbColumnInfoVOMap(entityDaoVO.getDbColumnInfoVOMap());
                entityFullDaoVO.setDatasetId(entityDaoVO.getDatasetId());
                entityFullDaoVO.setEntityType(entityDaoVO.getEntityType());
                entityFullDaoVO.setOperation(entityDaoVO.getOperation());
                entityFullDaoVO.setCreatedAt(entityDaoVO.getCreatedAt());
                entityFullDaoVO.setModifiedAt(entityDaoVO.getModifiedAt());

                // DB????????? ?????? Array ??? Geo?????? ????????? ?????? ??????
                // lower case -> camel(?????? ??????)?????? ?????? (TODO: ??????)
                Map<String, DataModelDbColumnVO> dbColumnInfoVOMap = entityDaoVO.getDbColumnInfoVOMap();
                for (Map.Entry<String, DataModelDbColumnVO> entry : dbColumnInfoVOMap.entrySet()) {

                	String columnName = entry.getKey().toLowerCase();

                	// Ingest ???????????? ?????? ?????? ????????? ?????? ??????????????? ??????????????? ?????? (property??? createdAt??? ??????)
                	Object receivedValue = entityDaoVO.get(entry.getKey());
                	if(receivedValue != null && !columnName.endsWith(Constants.COLUMN_DELIMITER + PropertyKey.CREATED_AT.getCode().toLowerCase())) {
                		entityFullDaoVO.put(columnName, receivedValue);
                		continue;
                	}

                	DataModelDbColumnVO dbColumnInfoVO = entry.getValue();
                	Object value = entityFullDaoVO.get(columnName.toLowerCase());
                	if(value != null) {
                		// String[] -> List<String>, Integer[] -> List<Integer>, Float[] -> List<Float> ????????????
                        if (dbColumnInfoVO.getColumnType() == DbColumnType.ARRAY_VARCHAR
                                || dbColumnInfoVO.getColumnType() == DbColumnType.ARRAY_INTEGER
                                || dbColumnInfoVO.getColumnType() == DbColumnType.ARRAY_BIGINT
                                || dbColumnInfoVO.getColumnType() == DbColumnType.ARRAY_FLOAT) {
                            Object[] valueArr = (Object[]) value;
                            List<Object> valueList = new ArrayList<>(valueArr.length);
                            for (int i = 0; i < valueArr.length; i++) {
                                valueList.add(valueArr[i]);
                            }
                            entityFullDaoVO.put(columnName, valueList);

                        // Timestamp[] ??? List<Date> ????????????
                        } else if (dbColumnInfoVO.getColumnType() == DbColumnType.ARRAY_TIMESTAMP) {
                            Object[] dateArr = (Object[]) value;
                            List<Date> dateList = new ArrayList<>(dateArr.length);
                            for (int i = 0; i < dateArr.length; i++) {
                                dateList.add((Date) dateArr[i]);
                            }
                            entityFullDaoVO.put(columnName, dateList);

                        // PGgeometry -> GeoJson ????????????
                        } else if (dbColumnInfoVO.getColumnType() == DbColumnType.GEOMETRY_4326) {

                            PGgeometry pGgeometry = (PGgeometry) value;
                            if(pGgeometry != null) {
                            	try {
                            		String geoJson = objectMapper.writeValueAsString(pGgeometry.getGeometry());
									entityFullDaoVO.put(columnName, geoJson);
									entityFullDaoVO.put(columnName.replace(Constants.GEO_PREFIX_4326, Constants.GEO_PREFIX_3857), geoJson);
								} catch (Exception e) {
									log.error("Store entity FULL history pGgeometry parsing error.", e);
								}
                            }
                        }
                        continue;
                	}
                }
                createFullHistoryVOList.add(entityFullDaoVO);
            }

            try {
                entityDAO.bulkCreateFullHist(createFullHistoryVOList);
            } catch (Exception e) {
                log.error("Store entity FULL history error", e);
            }
        }
    }

    /**
     * lastN ?????? ??????, ?????? ??? ?????? ????????? ????????? N?????? ?????????????????? ???????????? ????????? ?????????????????? ??????
     *
     * The lastN parameter refers to a number, n, of Attribute instances which shall correspond
     * to the last n timestamps (in descending ordering) of the temporal property (by default observedAt)
     * within the concerned temporal interval.
     * @param commonEntityVO
     * @param lastN
     * @return
     */
    private CommonEntityFullVO retrieveLastN(CommonEntityFullVO commonEntityVO, DataModelCacheVO dataModelCacheVO, Integer lastN) {

        for (String key : commonEntityVO.keySet()) {
            Attribute rootAttribute =  dataModelCacheVO.getRootAttribute(key);
            if (rootAttribute == null) {
                continue;
            }
            if (rootAttribute.getHasObservedAt() == null || rootAttribute.getHasObservedAt() == false) {
                continue;
            }

            List list = (List) commonEntityVO.get(key);
            if (list.size() > lastN) {
                list = list.subList(0, lastN);
            }

            Collections.sort(list, new ObservedAtReverseOrder());
            commonEntityVO.replace(key, list);

        }


        return commonEntityVO;
    }


}