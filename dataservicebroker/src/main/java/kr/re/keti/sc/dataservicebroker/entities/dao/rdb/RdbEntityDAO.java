package kr.re.keti.sc.dataservicebroker.entities.dao.rdb;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode;
import kr.re.keti.sc.dataservicebroker.entities.dao.EntityDataModelDAO;
import kr.re.keti.sc.dataservicebroker.entities.vo.EntityDataModelVO;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.logging.Logger;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgis.LineString;
import org.postgis.MultiLineString;
import org.postgis.MultiPolygon;
import org.postgis.PGgeometry;
import org.postgis.Point;
import org.postgis.Polygon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.CaseFormat;

import kr.re.keti.sc.dataservicebroker.common.code.Constants;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.AttributeValueType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.DefaultDbColumnName;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.ErrorCode;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.GeometryType;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.Operation;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.PropertyKey;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.QueryOperator;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.RetrieveOptions;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.TemporalOperator;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode.UseYn;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdBadRequestException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdResourceNotFoundException;
import kr.re.keti.sc.dataservicebroker.common.vo.DbConditionVO;
import kr.re.keti.sc.dataservicebroker.common.vo.EntityAttrDaoVO;
import kr.re.keti.sc.dataservicebroker.common.vo.ProcessResultVO;
import kr.re.keti.sc.dataservicebroker.common.vo.QueryVO;
import kr.re.keti.sc.dataservicebroker.common.vo.entities.DynamicEntityDaoVO;
import kr.re.keti.sc.dataservicebroker.datamodel.DataModelManager;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.Attribute;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelCacheVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.DataModelDbColumnVO;
import kr.re.keti.sc.dataservicebroker.datamodel.vo.ObjectMember;
import kr.re.keti.sc.dataservicebroker.entities.dao.EntityDAOInterface;
import kr.re.keti.sc.dataservicebroker.entities.sqlprovider.rdb.RdbEntitySqlProvider;
import kr.re.keti.sc.dataservicebroker.util.ConvertTimeParamUtil;
import kr.re.keti.sc.dataservicebroker.util.QueryUtil;
import kr.re.keti.sc.dataservicebroker.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RdbEntityDAO implements EntityDAOInterface<DynamicEntityDaoVO> {

    @Autowired
    private SqlSessionTemplate sqlSession;
    @Autowired
//	@Qualifier("batchSqlSession")
    private SqlSessionTemplate batchSqlSession;
    @Autowired
	@Qualifier("retrieveSqlSession")
	private SqlSessionTemplate retrieveSqlSession;
    @Autowired
    private DataModelManager dataModelManager;
    @Autowired
    private EntityDataModelDAO entityDataModelDAO;

    //Geo-query default attribute??? ??????
    @Value("${geometry.default.attribute:location}")
    private String defaultLocationAttrName;
    //Geo-query default EPSG ??????
    @Value("${geometry.default.EPSG:4326}")
    private String defaultEPSG;
    @Value("${entity.history.delete.yn}")
    public String deleteEntityHistoryYn; // Entity ?????? ??? ???????????? ?????? ?????? ??????

    @Value("${entity.history.retrive.full.yn:N}")
    public String retrieveFullHistoryYn;    //Entity ?????? ?????? ?????? ??????
    
    @Value("${entity.retrieve.default.limit:1000}")
    private Integer defaultLimit;

    public static String[] MANDATORY_ATTRIBUTE = new String[]{"id", "dataset_id", "created_at", "modified_at"};
    public static String[] MANDATORY_TEMPORAL_ATTRIBUTE = new String[]{"id", "dataset_id", "operation", "modified_at"};
    
    /**
     * ?????? CREATE ??????
     * @param createList CREATE ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkCreate(List<DynamicEntityDaoVO> createList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(createList.size());

        for (DynamicEntityDaoVO entityDaoVO : createList) {

            log.debug("bulkCreate. id=" + entityDaoVO.getId());

            // CREATE
            RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);
            batchMapper.create(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
            processResultVO.setProcessResult(true);
            processResultVOList.add(processResultVO);

            log.debug("bulkCreate. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * CREATE ??????
     *
     * @param entityDaoVO CREATE ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO create(DynamicEntityDaoVO entityDaoVO) {

        log.debug("create. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        mapper.create(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
        processResultVO.setProcessResult(true);

        log.debug("create. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }


    /**
     * ?????? Update Entity Attributes ??????
     * @param updateAttrList Update Entity Attributes ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkUpdateAttr(List<DynamicEntityDaoVO> updateAttrList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(updateAttrList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        for (DynamicEntityDaoVO entityDaoVO : updateAttrList) {

            log.debug("bulkUpdateAttr. id=" + entityDaoVO.getId());

            // Update Entity Attributes
            int result = batchMapper.updateAttr(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.UPDATE_ENTITY_ATTRIBUTES);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("Update Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("bulkUpdateAttr. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * Update Entity Attributes ??????
     * @param entityDaoVO Update Entity Attributes ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO updateAttr(DynamicEntityDaoVO entityDaoVO) {

        log.debug("Update Entity Attributes. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.appendAttr(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.UPDATE_ENTITY_ATTRIBUTES);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("Update Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("Update Entity Attributes. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }

    /**
     * ?????? Partial Attribute Update ??????
     * @param partialAttrUpdateList Partial Attribute Update ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkPartialAttrUpdate(List<DynamicEntityDaoVO> partialAttrUpdateList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(partialAttrUpdateList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        for (DynamicEntityDaoVO entityDaoVO : partialAttrUpdateList) {

            log.debug("bulkPartialAttrUpdate. id=" + entityDaoVO.getId());

            // Update Entity Attributes
            int result = batchMapper.partialAttrUpdate(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.PARTIAL_ATTRIBUTE_UPDATE);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("Partial Attribute Update fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("bulkPartialAttrUpdate. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * Partial Attribute Update ??????
     * @param entityDaoVO Update Entity Attributes ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO partialAttrUpdate(DynamicEntityDaoVO entityDaoVO) {

        log.debug("Partial Attribute Update. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.partialAttrUpdate(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.PARTIAL_ATTRIBUTE_UPDATE);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("Partial Attribute Update fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("Partial Attribute Update. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }

    /**
     * ?????? Append Entity Attributes ??????
     *
     * @param appendAttrList Append Entity Attributes ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkAppendAttr(List<DynamicEntityDaoVO> appendAttrList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(appendAttrList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        for (DynamicEntityDaoVO entityDaoVO : appendAttrList) {

            log.debug("bulkAppendAttr. id=" + entityDaoVO.getId());

            // Append Entity Attributes
            int result = batchMapper.appendAttr(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("Append Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("bulkAppendAttr. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * Append Entity Attributes ??????
     *
     * @param entityDaoVO Append Entity Attributes ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO appendAttr(DynamicEntityDaoVO entityDaoVO) {

        log.debug("appendAttr. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.appendAttr(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("Append Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("appendAttr. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }



    /**
     * ?????? Append Entity Attributes ??????
     *
     * @param appendAttrList Append Entity Attributes ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkAppendNoOverwriteAttr(List<DynamicEntityDaoVO> appendAttrList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(appendAttrList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        for (DynamicEntityDaoVO entityDaoVO : appendAttrList) {

            log.debug("bulkAppendNoOverwriteAttr. id=" + entityDaoVO.getId());

            // Append Entity Attributes
            int result = batchMapper.appendNoOverwriteAttr(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("Append Entity Attributes (noOverwrite) fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("bulkAppendAttr. (noOverwrite) id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }


    /**
     * Append Entity (noOverwrite) Attributes ??????
     *
     * @param entityDaoVO Append Entity (noOverwrite) Attributes ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO appendNoOverwriteAttr(DynamicEntityDaoVO entityDaoVO) {

        log.debug("appendNoOverwriteAttr. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.appendNoOverwriteAttr(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("Append Entity (noOverwrite) Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("appendAttr. (noOverwrite) id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }

    /**
     * ?????? Replace Entity Attributes ??????
     *
     * @param daoVOList Replace Entity Attributes ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkReplaceEntity(List<DynamicEntityDaoVO> daoVOList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(daoVOList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        for (DynamicEntityDaoVO entityDaoVO : daoVOList) {

            log.debug("Replace Entity Attributes. id=" + entityDaoVO.getId());

            // Replace Entity Attributes
            int result = batchMapper.replaceAttr(entityDaoVO);

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("Replace Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("Replace Entity Attributes. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * Replace Entity Attributes ??????
     *
     * @param entityDaoVO Replace Entity Attributes ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO replaceEntity(DynamicEntityDaoVO entityDaoVO) {

        log.debug("Replace Entity Attributes. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.replaceAttr(entityDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("Replace Entity Attributes fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("Replace Entity Attributes. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }


    /**
     * FULL UPSERT ?????? ??????
     * - Replace Entity Attributes ?????? ?????? ??? result ??? 0 ??? ???????????? Create ??????
     *
     * @param fullUpsertList FULL UPSERT ?????? VO ?????????
     * @return List<ProcessResultVO> ???????????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkFullUpsert(List<DynamicEntityDaoVO> fullUpsertList) {

        List<ProcessResultVO> processResultVOList = new ArrayList<>(fullUpsertList.size());

        // DB ???????????? ?????? ???????????? ?????? update ?????? ?????? ?????? ??? create ??????
        List<Integer> updateResult = new ArrayList<>(fullUpsertList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        // Replace Entity Attributes
        for (DynamicEntityDaoVO entityDaoVO : fullUpsertList) {

            log.debug("bulkFullUpsert. id=" + entityDaoVO.getId());

            int result = batchMapper.replaceAttr(entityDaoVO);
            updateResult.add(result);
        }

        for (int i = 0; i < fullUpsertList.size(); i++) {
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);
            int result = updateResult.get(i);
            if (result == 0) {
                // CREATE
                processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
                batchMapper.create(fullUpsertList.get(i));
            }

            processResultVO.setProcessResult(true);
            processResultVOList.add(processResultVO);

            log.debug("bulkFullUpsert. id=" + fullUpsertList.get(i).getId() + ", processResultVO=" + processResultVO);
        }
        return processResultVOList;
    }

    /**
     * FULL UPSERT ??????
     * - Replace Entity Attributes ????????? 0 ??? ?????? Create ??????
     *
     * @param entityDaoVO FULL UPSERT ?????? VO
     * @return ProcessResultVO ????????????VO
     */
    @Override
    public ProcessResultVO fullUpsert(DynamicEntityDaoVO entityDaoVO) {
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.REPLACE_ENTITY_ATTRIBUTES);

        log.debug("fullUpsert. DaoVO=" + entityDaoVO);

        // Replace Entity Attributes
        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.replaceAttr(entityDaoVO);
        if (result == 0) {
            // Create
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
            mapper.create(entityDaoVO);
        }

        processResultVO.setProcessResult(true);

        log.debug("fullUpsert. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }


    /**
     * ?????? PARTIAL UPSERT ??????
     *
     * @param partialUpsertList PARTIAL UPSERT ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkPartialUpsert(List<DynamicEntityDaoVO> partialUpsertList) {

        List<ProcessResultVO> processResultVOList = new ArrayList<>(partialUpsertList.size());

        // DB ???????????? ?????? ???????????? ?????? update ?????? ?????? ??? create ??????
        List<Integer> updateResult = new ArrayList<>(partialUpsertList.size());

        RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);

        // Append Entity Attributes
        for (DynamicEntityDaoVO entityDaoVO : partialUpsertList) {

            log.debug("bulkPartialUpsert. id=" + entityDaoVO.getId());

            int result = batchMapper.appendAttr(entityDaoVO);
            updateResult.add(result);
        }

        for (int i = 0; i < partialUpsertList.size(); i++) {

            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
            int result = updateResult.get(i);
            if (result == 0) {
                // CREATE
                processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
                batchMapper.create(partialUpsertList.get(i));
            }

            processResultVO.setProcessResult(true);
            processResultVOList.add(processResultVO);

            log.debug("bulkPartialUpsert. id=" + partialUpsertList.get(i).getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * PARTIAL UPSERT ??????
     *
     * @param entityDaoVO PARTIAL UPSERT ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    public ProcessResultVO partialUpsert(DynamicEntityDaoVO entityDaoVO) {
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);

        log.debug("partialUpsert. DaoVO=" + entityDaoVO);

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.appendAttr(entityDaoVO);
        if (result == 0) {
            processResultVO.setProcessOperation(Operation.CREATE_ENTITY);
            mapper.create(entityDaoVO);
        }

        processResultVO.setProcessResult(true);

        log.debug("partialUpsert. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }


    /**
     * ?????? DELETE ??????
     *
     * @param deleteList DELETE ?????? VO ?????????
     * @return List<ProcessResultVO> ?????? ?????? ?????????
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public List<ProcessResultVO> bulkDelete(List<DynamicEntityDaoVO> deleteList) {

        // ?????? ?????????
        List<ProcessResultVO> processResultVOList = new ArrayList<>(deleteList.size());

        for (DynamicEntityDaoVO entityDaoVO : deleteList) {

            log.debug("bulkDelete. id=" + entityDaoVO.getId());

            // DELETE
            RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);
            int result = batchMapper.delete(entityDaoVO);

            if (result > 0) {
                if (UseYn.YES.getCode().equals(deleteEntityHistoryYn)) {
                    batchMapper.deleteHist(entityDaoVO);
                }

                if (UseYn.YES.getCode().equals(deleteEntityHistoryYn)) {
                    batchMapper.deleteFullHist(entityDaoVO);
                }
            }

            // ?????? ??????
            ProcessResultVO processResultVO = new ProcessResultVO();
            processResultVO.setProcessOperation(Operation.DELETE_ENTITY);
            if (result > 0) {
                processResultVO.setProcessResult(true);
            } else {
                processResultVO.setProcessResult(false);
                processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                        "Not Exists Target Entity. id=" + entityDaoVO.getId()));
                processResultVO.setErrorDescription("DELETE fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
            }
            processResultVOList.add(processResultVO);

            log.debug("bulkDelete. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);
        }

        return processResultVOList;
    }

    /**
     * DELETE ??????
     *
     * @param entityDaoVO DELETE ?????? VO
     * @return ProcessResultVO ?????? ??????VO
     */
    @Override
    @Transactional(value = "dataSourceTransactionManager")
    public ProcessResultVO delete(DynamicEntityDaoVO entityDaoVO) {

        log.debug("delete. id=" + entityDaoVO.getId());

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.delete(entityDaoVO);

        if (result > 0) {
            RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);
            if (UseYn.YES.getCode().equals(deleteEntityHistoryYn)) {
                batchMapper.deleteHist(entityDaoVO);
            }

            if (UseYn.YES.getCode().equals(deleteEntityHistoryYn)) {
                batchMapper.deleteFullHist(entityDaoVO);
            }
        }

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.DELETE_ENTITY);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. id=" + entityDaoVO.getId()));
            processResultVO.setErrorDescription("DELETE fail. Not Exists Target Entity. id=" + entityDaoVO.getId());
        }

        log.debug("delete. id=" + entityDaoVO.getId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }

    @Override
	public ProcessResultVO deleteAttr(DynamicEntityDaoVO entityDaoVO) {
		log.debug("deleteAttribute. entityId=" + entityDaoVO.getId() + ", attrId=" + entityDaoVO.getAttrId());

		RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.deleteAttr(entityDaoVO);

		// ?????? ??????
		ProcessResultVO processResultVO = new ProcessResultVO();
		processResultVO.setProcessOperation(Operation.DELETE_ENTITY_ATTRIBUTES);
		if(result > 0) {
			processResultVO.setProcessResult(true);
		} else {
			processResultVO.setProcessResult(false);
			processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.NOT_EXIST_ENTITY,
					"Not Exists Target Entity. entityId=" + entityDaoVO.getId()));
			processResultVO.setErrorDescription("DELETE_ATTRIBUTE fail. Not Exists Target Entity. entityId=" + entityDaoVO.getId());
		}

		log.debug("deleteAttribute. entityId=" + entityDaoVO.getId() + ", attrId=" + entityDaoVO.getAttrId() + ", processResultVO=" + processResultVO);

		return processResultVO;
	}

    @Transactional(value = "dataSourceTransactionManager")
    @Override
    public ArrayList<Integer> bulkCreateHist(List<DynamicEntityDaoVO> histList) {

        ArrayList<Integer> resultList = new ArrayList<>(histList.size());
        for (DynamicEntityDaoVO entityDaoVO : histList) {
            RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);
            int result = batchMapper.createHist(entityDaoVO);
            resultList.add(result);
        }
        return resultList;
    }

    @Transactional(value = "dataSourceTransactionManager")
    @Override
    public ArrayList<Integer> bulkCreateFullHist(List<DynamicEntityDaoVO> histList) {

        ArrayList<Integer> resultList = new ArrayList<>(histList.size());
        for (DynamicEntityDaoVO entityDaoVO : histList) {
            RdbEntitySqlProvider batchMapper = batchSqlSession.getMapper(RdbEntitySqlProvider.class);
            int result = batchMapper.createFullHist(entityDaoVO);
            resultList.add(result);
        }
        return resultList;
    }

    
    /**
     * ?????? ????????? ?????? by ID
     * @param queryVO(id)
     * @return ????????????VO
     */
    @Override
    public DynamicEntityDaoVO selectById(QueryVO queryVO, Boolean useForCreateOperation) {

        DbConditionVO dbConditionVO = setQueryCondition(queryVO, false);

        RdbEntitySqlProvider mapper = null;

        // CREATE ?????? ??? ???????????? ????????? ??????
        // CREATE ????????? ????????? Primary DB Connection Pool ?????? ???????????? ??????
        //  - Replication DB??? ???????????? ?????? ???????????? ?????? ????????? ?????? ?????? ??? ?????? ??????
        if(useForCreateOperation) {
        	mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);

       	// RETRIEVE ?????? ??? ???????????? ????????? ??????
        //  - RETRIEVE ????????? ????????? ?????? Primary ?????? Replication DB?????? ???????????? ??????
        //	- datasource.secondary.use.yn=Y??? ?????? secondary datasource??? sqlSession ??? ??????
        } else {
        	mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        }

        //ID ?????? ??????
        dbConditionVO.setId(queryVO.getId());
        DynamicEntityDaoVO entityDaoVO = (DynamicEntityDaoVO) mapper.selectOne(dbConditionVO);

        return entityDaoVO;
    }

    /**
     * ?????? ????????? ?????? ??????
     * @param queryVO
     * @return ????????????VO?????????
     */
    @Override
    public List<DynamicEntityDaoVO> selectAll(QueryVO queryVO) {
        DbConditionVO dbConditionVO = setQueryCondition(queryVO, false);

        RdbEntitySqlProvider mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        List<DynamicEntityDaoVO> entityDaoVOs = (List<DynamicEntityDaoVO>) mapper.selectList(dbConditionVO);

        return entityDaoVOs;
    }

    /**
     * ?????? ?????????(partial) ?????? by ID
     * @param queryVO
     * @return ????????????VO?????????
     */
    @Override
    public List<DynamicEntityDaoVO> selectHistById(QueryVO queryVO) {

        DbConditionVO dbConditionVO = setQueryCondition(queryVO, true);
        dbConditionVO.setId(queryVO.getId());

        RdbEntitySqlProvider mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        List<DynamicEntityDaoVO> entityDaoVOs = (List<DynamicEntityDaoVO>) mapper.selectHistList(dbConditionVO);

        return entityDaoVOs;
    }


    /**
     * ?????? ?????????(parital) ?????? ??????
     * @param queryVO
     * @return ????????????VO?????????
     */
    @Override
    public List<DynamicEntityDaoVO> selectAllHist(QueryVO queryVO) {

        DbConditionVO dbConditionVO = setQueryCondition(queryVO, true);

        RdbEntitySqlProvider mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        List<DynamicEntityDaoVO> entityDaoVOs = (List<DynamicEntityDaoVO>) mapper.selectHistList(dbConditionVO);

        return entityDaoVOs;
    }

    private DbConditionVO setQueryCondition(QueryVO queryVO, Boolean isTemproal) {
    	return setQueryCondition(queryVO, isTemproal, false);
    }

    /**
     * DB ?????? ?????? ??????
     * @param queryVO
     * @param isTemproal
     * @return
     */
    private DbConditionVO setQueryCondition(QueryVO queryVO, Boolean isTemproal, Boolean isSelectCount) {

        DataModelCacheVO dataModelCacheVO = queryVO.getDataModelCacheVO();
        if(dataModelCacheVO == null) {
            dataModelCacheVO = dataModelManager.getDataModelVOCacheByContext(queryVO.getLinks(), queryVO.getType());
        }
        if(dataModelCacheVO == null) {
            dataModelCacheVO = dataModelManager.getDataModelCacheByDatasetId(queryVO.getDatasetId());
        }
        if(dataModelCacheVO == null) {
            if(!ValidateUtil.isEmptyData(queryVO.getId())) {
                EntityDataModelVO entityDataModelVO = entityDataModelDAO.getEntityDataModelVOById(queryVO.getId());
                if(entityDataModelVO != null) {
                    dataModelCacheVO = dataModelManager.getDataModelVOCacheById(entityDataModelVO.getDataModelId());
                }
            }
        }
        queryVO.setDataModelCacheVO(dataModelCacheVO);

        DbConditionVO dbConditionVO = new DbConditionVO();

        dbConditionVO.setTableName(Constants.SCHEMA_NAME + ".\"" + dataModelCacheVO.getDataModelStorageMetadataVO().getRdbTableName() + "\"");
        dbConditionVO.setHistTableName(Constants.SCHEMA_NAME + ".\"" + getHistoryTableName(dataModelCacheVO.getDataModelStorageMetadataVO().getRdbTableName()) + "\"");
        if(isSelectCount != null && !isSelectCount ) {
        	if(queryVO.getLimit() == null) {
            	dbConditionVO.setLimit(defaultLimit);
            } else {
            	dbConditionVO.setLimit(queryVO.getLimit());
            }
            dbConditionVO.setOffset(queryVO.getOffset());
        }

        // id List ?????? ??????
        if (queryVO.getSearchIdList() != null) {
            dbConditionVO.setSearchIdList(queryVO.getSearchIdList());
        }
        
        // id pattern ?????? ??????
        if (queryVO.getIdPattern() != null) {
        	try {
        		Pattern.compile(queryVO.getIdPattern());
        	} catch(PatternSyntaxException e) {
        		log.warn("invalid RegEx expression. idPattern={}", queryVO.getIdPattern());
        		throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid RegEx expression. idPattern=" + queryVO.getIdPattern(), e);
        	}
            dbConditionVO.setIdPattern(queryVO.getIdPattern());
        }
        
        //1. ?????? ?????? ?????? ??????
        String selectCondition = getSelectCondition(queryVO, isTemproal);
        dbConditionVO.setSelectCondition(selectCondition);

        //2. geo-query param ??????
        if (QueryUtil.validateGeoQuery(queryVO) && QueryUtil.includeGeoQuery(queryVO)) {
            dbConditionVO.setGeoCondition(QueryUtil.convertGeoQuery(generateGeoQuery(dataModelCacheVO, queryVO)));
        }

        //3. ????????????(q-query) param ??????
        if (QueryUtil.includeQQuery(queryVO)) {
            queryVO = QueryUtil.generateQuery(dataModelCacheVO, queryVO);
            dbConditionVO.setQueryCondition(queryVO.getQuery());
        }

        //4. ?????? ??????(time rel) param ??????
        if (isTemproal == true) {
            // timerel param ??????, ?????? ????????? ??????????????? ?????? 
            if (queryVO.getTimerel() != null) {
                queryVO = convertTimerel(queryVO);
                dbConditionVO.setTimerelCondition(queryVO.getTimeQuery());
            }
        }

        //5. ????????????(????????????) ?????? ?????? ??????
        List<String> aclDatasetIds = queryVO.getAclDatasetIds();
        StringBuilder aclDatasetConditionSB = new StringBuilder();
        if (!ValidateUtil.isEmptyData(aclDatasetIds)) {
            // ??????????????? datasetId ??? ?????? ??????
            if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
                if(!aclDatasetIds.contains(queryVO.getDatasetId())) {
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "dataset permission deny. datasetId=" + queryVO.getDatasetId());
                }
                aclDatasetIds.clear();
                aclDatasetIds.add(queryVO.getDatasetId());
            }
        } else {
            // ??????????????? datasetId ??? ?????? ??????
            if(!ValidateUtil.isEmptyData(queryVO.getDatasetId())) {
                aclDatasetIds = new ArrayList<>(Arrays.asList(queryVO.getDatasetId()));
            }
        }

        if(!ValidateUtil.isEmptyData(aclDatasetIds)) {
            aclDatasetConditionSB.append(DefaultDbColumnName.DATASET_ID.getCode());
            aclDatasetConditionSB.append(" ");
            aclDatasetConditionSB.append("IN");
            aclDatasetConditionSB.append(" (");
            String datasetIds = "'" + StringUtils.join(aclDatasetIds,"','") + "'";
            aclDatasetConditionSB.append(datasetIds);
            aclDatasetConditionSB.append(" )");

            dbConditionVO.setAclDatasetCondition(aclDatasetConditionSB.toString());
        }

        return dbConditionVO;
    }


    @Override
    public ProcessResultVO deleteAttribute(EntityAttrDaoVO entityAttrDaoVO) {
        log.debug("deleteAttribute. entityId=" + entityAttrDaoVO.getId() + ", attrId=" + entityAttrDaoVO.getAttrId());

        RdbEntitySqlProvider mapper = sqlSession.getMapper(RdbEntitySqlProvider.class);
        int result = mapper.deleteAttr(entityAttrDaoVO);

        // ?????? ??????
        ProcessResultVO processResultVO = new ProcessResultVO();
        processResultVO.setProcessOperation(Operation.APPEND_ENTITY_ATTRIBUTES);
        if (result > 0) {
            processResultVO.setProcessResult(true);
        } else {
            processResultVO.setProcessResult(false);
            processResultVO.setException(new NgsiLdResourceNotFoundException(ErrorCode.INVALID_PARAMETER,
                    "Not Exists Target Entity. entityId=" + entityAttrDaoVO.getId()));
            processResultVO.setErrorDescription("DELETE_ATTRIBUTE fail. Not Exists Target Entity. entityId=" + entityAttrDaoVO.getId());
        }

        log.debug("deleteAttribute. entityId=" + entityAttrDaoVO.getId() + ", attrId=" + entityAttrDaoVO.getAttrId() + ", processResultVO=" + processResultVO);

        return processResultVO;
    }

    /**
     * ?????? ????????? ?????? ??????
     * @param queryVO
     * @return ????????????VO?????????
     */
    @Override
    public Integer selectCount(QueryVO queryVO) {
        DbConditionVO dbConditionVO = setQueryCondition(queryVO, false, true);

        RdbEntitySqlProvider mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        return (Integer) mapper.selectCount(dbConditionVO);
    }

    /**
     * ?????? ????????? ?????? ??????
     * @param queryVO
     * @return ????????????VO?????????
     */
    @Override
    public Integer selectHistCount(QueryVO queryVO) {
        DbConditionVO dbConditionVO = setQueryCondition(queryVO, false, true);

        RdbEntitySqlProvider mapper = retrieveSqlSession.getMapper(RdbEntitySqlProvider.class);
        return (Integer) mapper.selectHistCount(dbConditionVO);
    }

    /**
     * select ?????? ??????
     * @param queryVO
     * @param isTemproal ?????? ????????? ?????? ??????
     * @return
     */
    public String getSelectCondition(QueryVO queryVO, Boolean isTemproal) {

    	DataModelCacheVO dataModelCacheVO = queryVO.getDataModelCacheVO();

    	String selectCondition;
        List<String> targetColumnList = new ArrayList<>();

        // 1-1. attr ????????? ?????? ??????
        if (queryVO.getAttrs() != null) {
        	
        	for (String attr : queryVO.getAttrs()) {
        		List<String> hierarchyAttrNames = new ArrayList<>();
            	Map<String, DataModelDbColumnVO> dbColumnInfoVOMap = dataModelCacheVO.getDataModelStorageMetadataVO().getDbColumnInfoVOMap();

            	// objectMember ??? child attribute name ??? ???????????? List ??? ??????
            	if(attr.contains("]")) {
            		attr = attr.replace("]", "");
            	}
           		String[] attrArr = attr.split("\\[", 2);

                // full url -> name
                if (attrArr.length > 0 && attrArr[0] != null && attrArr[0].startsWith("http")) {
                    String attrName = QueryUtil.getAttrNameFromAttributeUri(queryVO.getDataModelCacheVO(), attrArr[0]);
                    if (!ValidateUtil.isEmptyData(attrName)) {
                        attrArr[0] = attrName;
                    }
                }

           		for(String objectMemberSplitAttr : attrArr) {
           			String[] childSpliAttr = objectMemberSplitAttr.split("\\.");
           			for(String splitAttrName : childSpliAttr) {
           				hierarchyAttrNames.add(splitAttrName);
           			}
           		}
           		
           		for(DataModelDbColumnVO dataModelDbColumnVO : dbColumnInfoVOMap.values()) {
           			boolean isMatch = true;
       				for(int i=0; i<hierarchyAttrNames.size(); i++) {
       					if(dataModelDbColumnVO.getHierarchyAttributeIds().size() > i) {
       						if(!hierarchyAttrNames.get(i).equals(dataModelDbColumnVO.getHierarchyAttributeIds().get(i))) {
       							isMatch = false;
       						}
       					}
               		}
       				if(isMatch) {
       					targetColumnList.add(dataModelDbColumnVO.getColumnName());

       					for(int i=0; i<hierarchyAttrNames.size(); i++) {
       						String observedAtColumnName = null;
       						if(i > 0) {
       							StringBuilder observedAtColumNameBuilder = new StringBuilder();
       							for(int j=0; j<=i; j++) {
       								if(j > 0) {
       									observedAtColumNameBuilder.append(Constants.COLUMN_DELIMITER);
       								}
       								observedAtColumNameBuilder.append(hierarchyAttrNames.get(j));
       							}
       							observedAtColumNameBuilder.append(Constants.COLUMN_DELIMITER).append(PropertyKey.OBSERVED_AT.getCode());
       							observedAtColumnName = observedAtColumNameBuilder.toString();
       						} else {
       							observedAtColumnName = hierarchyAttrNames.get(i) + Constants.COLUMN_DELIMITER + PropertyKey.OBSERVED_AT.getCode();
       						}
       						
       						DataModelDbColumnVO observedAtColumnVO = dbColumnInfoVOMap.get(observedAtColumnName);
           					if(observedAtColumnVO != null) {
           						targetColumnList.add(observedAtColumnVO.getColumnName());
           					}
       					}
       					
       					// options ??? sysAttr??? ????????? ?????? property??? createdAt, modifiedAt ??????
       					if(!isTemproal && queryVO.getOptions() != null && queryVO.getOptions().contains(RetrieveOptions.SYS_ATTRS.getCode())) {
       						for(int i=0; i<hierarchyAttrNames.size(); i++) {
           						String createdAtColumnName = null;
           						String modifiedAtColumnName = null;
           						if(i > 0) {
           							StringBuilder createdAtColumNameBuilder = new StringBuilder();
           							StringBuilder modifiedAtColumNameBuilder = new StringBuilder();
           							for(int j=0; j<=i; j++) {
           								if(j > 0) {
           									createdAtColumNameBuilder.append(Constants.COLUMN_DELIMITER);
           									modifiedAtColumNameBuilder.append(Constants.COLUMN_DELIMITER);
           								}
           								createdAtColumNameBuilder.append(hierarchyAttrNames.get(j));
           								modifiedAtColumNameBuilder.append(hierarchyAttrNames.get(j));
           							}
           							createdAtColumNameBuilder.append(Constants.COLUMN_DELIMITER).append(PropertyKey.CREATED_AT.getCode());
           							modifiedAtColumNameBuilder.append(Constants.COLUMN_DELIMITER).append(PropertyKey.MODIFIED_AT.getCode());
           							
           							createdAtColumnName = createdAtColumNameBuilder.toString();
           							modifiedAtColumnName = modifiedAtColumNameBuilder.toString();
           						} else {
           							createdAtColumnName = hierarchyAttrNames.get(i) + Constants.COLUMN_DELIMITER + PropertyKey.CREATED_AT.getCode();
           							modifiedAtColumnName = hierarchyAttrNames.get(i) + Constants.COLUMN_DELIMITER + PropertyKey.MODIFIED_AT.getCode();
           						}
           						
               					DataModelDbColumnVO createdAtColumnVO = dbColumnInfoVOMap.get(createdAtColumnName);
               					if(createdAtColumnVO != null) {
               						targetColumnList.add(createdAtColumnVO.getColumnName());
               					}
               					
               					DataModelDbColumnVO modifiedAtColumnVO = dbColumnInfoVOMap.get(modifiedAtColumnName);
               					if(modifiedAtColumnVO != null) {
               						targetColumnList.add(modifiedAtColumnVO.getColumnName());
               					}
           					}
       					}
       				}
       			}
        	}
        	
        } else {
            // 1-2. attr ????????? ?????? ??????
            for (Map.Entry<String, DataModelDbColumnVO> entry : dataModelCacheVO.getDataModelStorageMetadataVO().getDbColumnInfoVOMap().entrySet()) {

            	DataModelDbColumnVO dbColumnInfoVO = entry.getValue();

                String columnName = dbColumnInfoVO.getColumnName();

                if (isTemproal) {
                	if(!columnName.endsWith(Constants.COLUMN_DELIMITER + PropertyKey.CREATED_AT.getCode())
                			&& !columnName.endsWith(Constants.COLUMN_DELIMITER + PropertyKey.MODIFIED_AT.getCode())){
                		targetColumnList.add(columnName);
                	}
                } else {
                	targetColumnList.add(columnName);
                }
            }
        }

        if (isTemproal) {
            targetColumnList.addAll(Arrays.asList(MANDATORY_TEMPORAL_ATTRIBUTE));
        } else {
            targetColumnList.addAll(Arrays.asList(MANDATORY_ATTRIBUTE));
        }
        selectCondition = String.join(",", targetColumnList);

        return selectCondition;

    }

    public List<String> getEntityTimeColumnNameList(QueryVO queryVO) {

        DataModelCacheVO dataModelCacheVO = queryVO.getDataModelCacheVO();

        Map<String, DataModelDbColumnVO> dbColumnInfoVOMap = dataModelCacheVO.getDataModelStorageMetadataVO().getDbColumnInfoVOMap();

        List<String> timeColumnNameList = new ArrayList<>();
        for (String key : dbColumnInfoVOMap.keySet()) {
            if (key.endsWith(PropertyKey.OBSERVED_AT.getCode().toLowerCase())) {
                timeColumnNameList.add(key);
            }
        }

        // observedAt ?????? ????????? ?????? ?????? entity ???????????? ???????????? ??????
        if(timeColumnNameList.isEmpty()) {
            timeColumnNameList.add(DefaultDbColumnName.MODIFIED_AT.getCode());
        }

        return timeColumnNameList;
    }

    /**
     * location ?????? -> postgis geometry Value(text)??? ??????
     * @param queryVO
     * @return
     */
    private QueryVO generateGeoQuery(DataModelCacheVO dataModelCacheVO, QueryVO queryVO) {
        /*
            georel = nearRel / withinRel / containsRel / overlapsRel / intersectsRel / equalsRel / disjointRel
            nearRel = nearOp andOp distance equal PositiveNumber distance = "maxDistance" / "minDistance"
            nearOp = "near"
            withinRel = "within"
            containsRel = "contains"
            intersectsRel = "intersects"
            equalsRel = "equals"
            disjointRel = "disjoint"
            overlapsRel = "overlaps"
            ; near;max(min)Distance==x (in meters)
         */

        if (QueryUtil.includeGeoQuery(queryVO)) {

            try {
                String georelFullTxt = queryVO.getGeorel();
                String georelName = georelFullTxt.split(";")[0];

                GeometryType geometryType = GeometryType.parseType(georelName);
                if (geometryType == null) {
                    log.warn("invalid geo-query parameter");
                    throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid geo-query parameter");
                }

                queryVO.setGeorelType(geometryType);

                if (georelFullTxt.startsWith(GeometryType.NEAR_REL.getCode())) {
                    String distanceText = georelFullTxt.split(";")[1];
                    String distanceColName = distanceText.split("==")[0];
                    int distance = Integer.parseInt(distanceText.split("==")[1]);

                    if (distanceColName.equals(GeometryType.MIN_DISTANCE.getCode())) {
                        queryVO.setMinDistance(distance);
                    } else if (distanceColName.equals(GeometryType.MAX_DISTANCE.getCode())) {
                        queryVO.setMaxDistance(distance);
                    } else {
                        log.warn("invalid geo-query parameter");
                        throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid geo-query parameter");
                    }
                }

            } catch (Exception e) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid geo-query parameter");
            }

            int srid = Integer.parseInt(defaultEPSG);
            String locationCol;

            if (queryVO.getGeoproperty() != null) {
                locationCol = queryVO.getGeoproperty();
                locationCol = locationCol.replace(".", "_") + "_" + srid;
            } else {
                locationCol = getDefaultLocationColName(dataModelCacheVO);
            }

            if(!dataModelCacheVO.getDataModelStorageMetadataVO().getDbColumnInfoVOMap().containsKey(locationCol.toLowerCase())) {
                throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid geoProperty.");
            }

            PGgeometry pGgeometry = makePostgisType(queryVO.getGeometry(), queryVO.getCoordinates(), srid);

            String pGgeometryValue = pGgeometry.getValue();

            queryVO.setGeometryValue(pGgeometryValue);
            queryVO.setLocationCol(locationCol);

            if (log.isDebugEnabled()) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("geometryValue : ")
                        .append(pGgeometryValue)
                        .append(" locationCol : ")
                        .append(locationCol);
                log.debug(stringBuilder.toString());
            }

            return queryVO;

        } else {
            log.warn("invalid geo-query parameter");
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid geo-query parameter");
        }
    }

    /**
     * geo-query ?????? ????????? postGiS type?????? ?????? ( Point, Polygon, LineString, MultiLineString, MultiPolygon)
     *
     * @param geometry
     * @param coordinates
     * @param srid
     * @return
     */
    private PGgeometry makePostgisType(String geometry, String coordinates, int srid) {

        log.debug("geometry : '{}', coordinates : {}, srid : {}", geometry, coordinates, srid);

        try {
            String convertedCoordinates = convertCoordinatesToPGisStr(coordinates);

            PGgeometry pGgeometry = new PGgeometry();

            if (geometry.equalsIgnoreCase("Point")) {

//             {"type": "Point", "coordinates": [100.0, 0.0]}

                Point point = new Point(convertedCoordinates);
                point.setSrid(srid);
                pGgeometry.setGeometry(point);

            } else if (geometry.equalsIgnoreCase("Polygon")) {

            /*
            	"geometry": {
            		"type": "Polygon",
            		"coordinates": [
            			[
            				[100.0, 0.0],
            			 	[101.0, 0.0],
            			  	[101.0, 1.0],
            			   	[100.0, 1.0],
            			    [100.0, 0.0]
            			 ]
            		]
            	}
            */

                Polygon polygon = new Polygon(convertedCoordinates);
                polygon.setSrid(srid);
                pGgeometry.setGeometry(polygon);

            } else if (geometry.equalsIgnoreCase("LineString")) {

            /*
                "type": "Feature",
                "geometry": {
                   "type": "LineString",
                   "coordinates": [
                       [102.0, 0.0],
                       [103.0, 1.0],
                       [104.0, 0.0],
                       [105.0, 1.0]
                   ]
                }
            */

                LineString lineString = new LineString(convertedCoordinates);
                lineString.setSrid(srid);
                pGgeometry.setGeometry(lineString);


            } else if (geometry.equalsIgnoreCase("MultiLineString")) {

            /*
                {
                   "type": "MultiLineString",
                   "coordinates": [
                       [
                           [170.0, 45.0], [180.0, 45.0]
                       ], [
                           [-180.0, 45.0], [-170.0, 45.0]
                       ]
                   ]
                }
            */

                MultiLineString multiLineString = new MultiLineString(convertedCoordinates);
                multiLineString.setSrid(srid);
                pGgeometry.setGeometry(multiLineString);

            } else if (geometry.equalsIgnoreCase("MultiPolygon")) {

            /*
                {
                   "type": "MultiPolygon",
                   "coordinates": [
                       [
                           [
                               [180.0, 40.0], [180.0, 50.0], [170.0, 50.0],
                               [170.0, 40.0], [180.0, 40.0]
                           ]
                       ],
                       [
                           [
                               [-170.0, 40.0], [-170.0, 50.0], [-180.0, 50.0],
                               [-180.0, 40.0], [-170.0, 40.0]
                           ]
                       ]
                   ]
               }
            */

                MultiPolygon multiPolygon = new MultiPolygon(convertedCoordinates);
                multiPolygon.setSrid(srid);
                pGgeometry.setGeometry(multiPolygon);

            }

            log.debug("pGgeometry : '{}'", pGgeometry.toString());
            return pGgeometry;

        } catch (EmptyStackException et) {
            log.warn("invalid coordinates : " + et.getMessage());
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid coordinates");
        } catch (SQLException se) {
            log.warn("invalid coordinates : " + se.getMessage());
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid coordinates");
        }
    }


    /**
     * coordinates (IETF RFC7946[8] -> postgis)
     *
     * @param coordinates
     * @return
     */
    private String convertCoordinatesToPGisStr(String coordinates) {

        /*
            coordinates = [[[180.0, 40.0], [180.0, 50.0]]]  , IETF RFC7946[8]
            coordinates = (((180.0 40.0]) (180.0, 50.0)))   , postgis
        */
        try {
            coordinates = coordinates.replace(" ", "");
            coordinates = coordinates.replace("[", "(");
            coordinates = coordinates.replace("]", ")");

            char[] arr = coordinates.toCharArray();
            char[] changeArr = new char[arr.length];

            for (int j = 0; j < arr.length; j++) {
                char comma = ',';
                char ch = arr[j];
                if (j > 1) {
                    if (ch == comma && !(arr[j - 1] == '(' || arr[j - 1] == ')')) {
                        changeArr[j] = ' ';
                    } else {
                        changeArr[j] = arr[j];
                    }
                } else {
                    changeArr[j] = arr[j];
                }
            }

            String changedCoordinates = new String(changeArr);
            log.debug("changed coordinates : '{}'", changedCoordinates);

            return changedCoordinates;

        } catch (Exception e) {
            throw new NgsiLdBadRequestException(ErrorCode.INVALID_PARAMETER, "invalid coordinates");
        }
    }


    /**
     * ?????? Query??? ?????? ?????? ??????
     *
     * @param queryVO
     * @return
     */
    public QueryVO generateQuery(DataModelCacheVO entitySchemaCacheVO, QueryVO queryVO) {

        List<String> queryResultList = new ArrayList<>();

        try {
            String q_query = queryVO.getQ();

            //???????????? ?????? ??????
            q_query = q_query.replace("\"", "'").replace("\\", "");

            //???????????? ?????? ?????? ???(;)??? ????????? ?????????
            String[] splitedAndQuery = q_query.split(";");

            for (String andQueryItem : splitedAndQuery) {

                String[] splitedOrQuery = andQueryItem.split("\\|");

                List<String> orQueryList = new ArrayList<>();
                for (String orQuery : splitedOrQuery) {
                    orQueryList.add("(" + makeFragmentQuery(orQuery, entitySchemaCacheVO) + ")");
                }

                queryResultList.add(String.join(" OR ", orQueryList));
            }
        } catch (Exception e) {
            throw new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR, "invalid q-query params");
        }

        if (queryResultList.size() > 0) {
            String query = String.join(" AND ", queryResultList);

            log.debug("q-query : '{}'", query);

            queryVO.setQuery(query);
        }
        return queryVO;
    }

    /**
     * ';' ????????? ????????? ?????? query????????? SQL??? ??????
     *
     * @param q_query
     * @return
     * @throws UnsupportedEncodingException
     */
    private String makeFragmentQuery(String q_query, DataModelCacheVO entitySchemaCacheVO) throws UnsupportedEncodingException {

        q_query = q_query.replace("%x", "%");
        q_query = URLDecoder.decode(q_query, Constants.CHARSET_ENCODING);

        String qOperator = null;
        String resultFragmentQuery = null;

        // operator??? EQUAL(==) ?????? ??????
        if (q_query.contains(QueryOperator.EQUAL.getSign())
                || q_query.contains(QueryOperator.EQUAL.getUnicode())) {

            qOperator = QueryOperator.EQUAL.getSign();
            String attrName = q_query.split(qOperator)[0];
            String qValue = q_query.split(qOperator)[1];

//            //CompEqualityValue
//            qOperator = QueryOperator.EQUAL.getSign();

            if (qValue.contains(QueryOperator.DOTS.getSign())) {
                //between ??? ??????

                String leftValue = qValue.split("\\.\\.")[0];
                String rightValue = qValue.split("\\.\\.")[1];

                //value??? timestamp??? ??? ??????, ' ??? ?????????
                if (isTimestampValue(leftValue)) {
                    leftValue = "'" + leftValue + "'";
                }

                if (isTimestampValue(rightValue)) {
                    rightValue = "'" + rightValue + "'";
                }

                // ?????? ????????? array[]?????? ??????,
                if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                    resultFragmentQuery = makeBetweenQueryWithColumnArrType(getColumnNameWithType(attrName), leftValue, rightValue);
                } else {
                    resultFragmentQuery = makeBetweenQuery(getColumnName(attrName), leftValue, rightValue);
                }

            } else if (qValue.contains(QueryOperator.COMMA.getSign())
                    || qValue.contains(QueryOperator.COMMA.getUnicode())) {

                //ValueList (,) ??? ??????
                qOperator = QueryOperator.COMMA.getSign();

                List<String> splitedValueList = Arrays.asList(qValue.split(qOperator));
                for (String itemValue : splitedValueList) {

                    if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                        splitedValueList.add(makeQueryWithColumnArrType(getColumnNameWithType(attrName), itemValue, qOperator));
                    } else {
                        splitedValueList.add(makeQuery(getColumnName(attrName), itemValue, qOperator));
                    }
                }

                resultFragmentQuery = String.join(" AND ", splitedValueList);

            } else {

                // ?????? EQUAL??? (==) ??????
                qOperator = QueryOperator.SINGLE_EQUAL.getSign();

                //value??? timestamp??? ??? ??????, ' ??? ?????????
                if (isTimestampValue(qValue)) {
                    qValue = "'" + qValue + "'";
                }

                if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                    resultFragmentQuery = makeQueryWithColumnArrType(getColumnNameWithType(attrName), qValue, qOperator);
                } else {
                    resultFragmentQuery = makeQuery(getColumnName(attrName), qValue, qOperator);
                }
            }

            return resultFragmentQuery;

        } else if (q_query.contains(QueryOperator.UNEQUAL.getSign()) || q_query.contains(QueryOperator.UNEQUAL.getUnicode())) {

            // operator??? unequal(!=) ??? ??????
            qOperator = QueryOperator.UNEQUAL.getSign();

            String attrName = q_query.split(qOperator)[0];
            String qValue = q_query.split(qOperator)[1];

            //value??? timestamp??? ??? ??????, ' ??? ?????????
            if (isTimestampValue(qValue)) {

                qValue = "'" + qValue + "'";
            }

            qOperator = QueryOperator.UNEQUAL.getSign();

            if (qValue.contains(QueryOperator.DOTS.getSign())
                    || qValue.contains(QueryOperator.DOTS.getUnicode())) {
                //between  ??? ??????
                String leftValue = qValue.split("\\.\\.")[0];
                String rightValue = qValue.split("\\.\\.")[1];

                if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                    resultFragmentQuery = makeNotBetweenQueryWithColumnArrType(getColumnNameWithType(attrName), leftValue, rightValue);
                } else {
                    resultFragmentQuery = makeNotBetweenQuery(getColumnName(attrName), leftValue, rightValue);
                }

            } else if (qValue.equals(QueryOperator.COMMA.getSign())) {

                //ValueList ??? ??????
                List<String> splitedValueList = Arrays.asList(qValue.split(QueryOperator.COMMA.getSign()));
                for (String itemValue : splitedValueList) {

                    //value??? timestamp??? ??? ??????, ' ??? ?????????
                    if (isTimestampValue(itemValue)) {
                        itemValue = "'" + itemValue + "'";
                    }

                    if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                        splitedValueList.add(makeQueryWithColumnArrType(getColumnNameWithType(attrName), itemValue, qOperator));
                    } else {
                        splitedValueList.add(makeQuery(getColumnName(attrName), itemValue, qOperator));
                    }
                }

                resultFragmentQuery = String.join(" AND ", splitedValueList);

            } else {

                //value??? timestamp??? ??? ??????, ' ??? ?????????
                if (isTimestampValue(qValue)) {
                    qValue = "'" + qValue + "'";
                }

                // ????????? ??????
                if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
                    resultFragmentQuery = makeQueryWithColumnArrType(getColumnNameWithType(attrName), qValue, qOperator);
                } else {
                    resultFragmentQuery = makeQuery(getColumnName(attrName), qValue, qOperator);
                }
            }

            return resultFragmentQuery;
        }

        if (q_query.contains(QueryOperator.GREATEREQ.getSign())
                || q_query.contains(QueryOperator.GREATEREQ.getUnicode())) {
            //greaterEq
            qOperator = QueryOperator.GREATEREQ.getSign();

        } else if (q_query.contains(QueryOperator.LESSEQ.getSign())
                || q_query.contains(QueryOperator.LESSEQ.getUnicode())) {
            //lessEq
            qOperator = QueryOperator.LESSEQ.getSign();

        } else if (q_query.contains(QueryOperator.GREATER.getSign()) || q_query.contains(QueryOperator.GREATER.getUnicode())) {
            //greater
            qOperator = QueryOperator.GREATER.getSign();

        } else if (q_query.contains(QueryOperator.LESS.getSign()) || q_query.contains(QueryOperator.LESS.getUnicode())) {
            //greater
            qOperator = QueryOperator.LESS.getSign();
        }

        // ????????? ??????
        String attrName = q_query.split(qOperator)[0];
        String qValue = q_query.split(qOperator)[1];

        //value??? timestamp??? ??? ??????, ' ??? ?????????
        if (isTimestampValue(qValue)) {
            qValue = "'" + qValue + "'";
        }

        if (isArrayTypeColumn(attrName, entitySchemaCacheVO)) {
            resultFragmentQuery = makeQueryWithColumnArrType(getColumnNameWithType(attrName), qValue, qOperator);
        } else {
            resultFragmentQuery = makeQuery(getColumnName(attrName), qValue, qOperator);
        }

        return resultFragmentQuery;
    }

    /**
     * q ????????? value??? timestamp ????????? ??????
     *
     * @param qValue
     * @return
     */
    private boolean isTimestampValue(String qValue) {

        Pattern p = Pattern.compile("([12]\\d{3}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01]))");
        Matcher m = p.matcher(qValue);

        if (m.find()) {
            return true;
        }

        return false;
    }

    private String makeQuery(String colName, String value, String operator) {

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(colName);
        stringBuilder.append(" ");
        stringBuilder.append(operator);
        stringBuilder.append(" ");
        stringBuilder.append(value);

        return stringBuilder.toString();
    }

    /**
     * DB column??? [] ????????? ??? ??? ??????, any()??? ?????? ?????? ???.
     * ???,??? ?????? [value = any(?????????)]
     */
    private String makeQueryWithColumnArrType(String colName, String value, String operator) {

        colName = colName.split("::")[0];

        if (operator.equalsIgnoreCase(">=")) {
            operator = "<=";
        } else if (operator.equalsIgnoreCase(">")) {
            operator = "<";
        } else if (operator.equalsIgnoreCase("<=")) {
            operator = ">=";
        } else if (operator.equalsIgnoreCase("<")) {
            operator = ">";
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(value);
        stringBuilder.append(" ");
        stringBuilder.append(operator);
        stringBuilder.append(" ");
        stringBuilder.append("any(");
        stringBuilder.append(colName);
        stringBuilder.append(")");

        return stringBuilder.toString();
    }

    /**
     * EQUAL??? ??? ?????????, SQL ??????
     *
     * @param colName
     * @param leftValue
     * @param rightValue
     * @return
     */
    private String makeBetweenQuery(String colName, String leftValue, String rightValue) {

//        WHERE Price BETWEEN 10 AND 20;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(colName);
        stringBuilder.append(" BETWEEN ");
        stringBuilder.append(leftValue);
        stringBuilder.append(" AND ");
        stringBuilder.append(rightValue);

        return stringBuilder.toString();
    }


    /**
     * EQUAL????????? target ????????? arr ??? ?????????, SQL ??????
     *
     * @param colName
     * @param leftValue
     * @param rightValue
     * @return
     */
    private String makeBetweenQueryWithColumnArrType(String colName, String leftValue, String rightValue) {

        colName = colName.split("::")[0];
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(leftValue);
        stringBuilder.append(" ");
        stringBuilder.append("<=");
        stringBuilder.append(" ");
        stringBuilder.append("any(");
        stringBuilder.append(colName);
        stringBuilder.append(")");
        stringBuilder.append(" AND ");
        stringBuilder.append(rightValue);
        stringBuilder.append(" ");
        stringBuilder.append(">=");
        stringBuilder.append(" ");
        stringBuilder.append("any(");
        stringBuilder.append(colName);
        stringBuilder.append(")");

        return stringBuilder.toString();
    }


    /**
     * UNEQUAL??? ??? ?????????,  SQL ??????
     *
     * @param colName
     * @param leftValue
     * @param rightValue
     * @return
     */
    private String makeNotBetweenQuery(String colName, String leftValue, String rightValue) {

//        WHERE Price BETWEEN 10 AND 20;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(colName);
        stringBuilder.append(" NOT BETWEEN ");
        stringBuilder.append(leftValue);
        stringBuilder.append(" AND ");
        stringBuilder.append(rightValue);

        return stringBuilder.toString();
    }


    /**
     * UNEQUAL????????? target ????????? arr ??? ?????????,  SQL ??????
     *
     * @param colName
     * @param leftValue
     * @param rightValue
     * @return
     */
    private String makeNotBetweenQueryWithColumnArrType(String colName, String leftValue, String rightValue) {
        colName = colName.split("::")[0];
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(leftValue);
        stringBuilder.append(" ");
        stringBuilder.append(">");
        stringBuilder.append(" ");
        stringBuilder.append("any(");
        stringBuilder.append(colName);
        stringBuilder.append(")");
        stringBuilder.append(" AND ");
        stringBuilder.append(rightValue);
        stringBuilder.append(" ");
        stringBuilder.append("<");
        stringBuilder.append(" ");
        stringBuilder.append("any(");
        stringBuilder.append(colName);
        stringBuilder.append(")");

        return stringBuilder.toString();
    }


    /**
     * Attribute ??? DB Column ????????? Array ?????? ????????? ??????
     *
     * @param attrName
     * @return
     */
    public boolean isArrayTypeColumn(String attrName, DataModelCacheVO dataModelCacheVO) {

        List<Attribute> rootAttributes = dataModelCacheVO.getDataModelVO().getAttributes();

        for (Attribute rootAttribute : rootAttributes) {

            String rootAttributeId = rootAttribute.getName();

            if (rootAttributeId.equalsIgnoreCase(attrName)) {

                if (rootAttribute.getValueType() == AttributeValueType.ARRAY_DOUBLE
                        || rootAttribute.getValueType() == AttributeValueType.ARRAY_INTEGER
                        || rootAttribute.getValueType() == AttributeValueType.ARRAY_STRING)
                    return true;
            }

            if (rootAttribute.getObjectMembers() != null) {

                List<ObjectMember> objectMembers = rootAttribute.getObjectMembers();

                for (ObjectMember objectMember : objectMembers) {


                    String childAttributeId = objectMember.getName();

                    if (childAttributeId.equalsIgnoreCase(attrName)) {

                        return true;

                    }
                }

            }

        }


        return false;
    }


    /**
     * Attribute ??? DB Column ??? ??????
     *
     * @param attrName
     * @return
     */
    public String getColumnName(String attrName) {

        attrName = attrName.replace("[", "_");
        attrName = attrName.replace("]", "");


        String columnName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, attrName);

        return columnName;
    }


    /**
     * Attribute ??? DB Column ?????? ????????? ??????
     * ex) NAME::text
     *
     * @param attrName
     * @return
     */
    public String getColumnNameWithType(String attrName) {
        String columnName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, attrName);

        return columnName;
    }


    /**
     * timerel ????????? ?????? query ??????
     *
     * @param queryVO
     * @return
     */
    public QueryVO convertTimerel(QueryVO queryVO) {

        /*
            4.11
            The Temporal Property to which the temporal query is to be applied can be specified by timeproperty.
            If no timeproperty is specified, the temporal query is applied to the default property observedAt.

            timerel = beforeRel / afterRel / betweenRel beforeRel = "before"
            afterRel = "after"
            betweenRel = "between"
        */
        String colName = null;

        if (queryVO.getType() != null && queryVO.getTimeproperty() != null) {

            if (PropertyKey.MODIFIED_AT.getCode().equalsIgnoreCase(queryVO.getTimeproperty())) {
                colName = DefaultDbColumnName.MODIFIED_AT.getCode();
            } else if(PropertyKey.OBSERVED_AT.getCode().equalsIgnoreCase(queryVO.getTimeproperty())) {
            	// observedAt??? ?????? getEntityTimeColumnNameList?????? ??????????????? ????????????
            } else {
            	throw new NgsiLdBadRequestException(ErrorCode.REQUEST_MESSAGE_PARSING_ERROR, "Invalid timerel params. timeproperty value must be 'observedAt' or 'modifiedAt'");
            }
        }

        String timerel = queryVO.getTimerel();
        String timeAt = null;
        if (queryVO.getTimeAt() != null) {
            timeAt = ConvertTimeParamUtil.dateTimeToLocalDateTime(queryVO.getTimeAt());
        }

        String endTimeAt = null;
        if (queryVO.getEndTimeAt() != null) {
            endTimeAt = ConvertTimeParamUtil.dateTimeToLocalDateTime(queryVO.getEndTimeAt());
        }

        ConvertTimeParamUtil.checkTimeRelParams(timerel, timeAt, endTimeAt);

        if (queryVO.getTimerel() != null) {

            if (colName != null) {
                if (timerel.equalsIgnoreCase(TemporalOperator.BETWEEN_REL.getCode())) {
                    queryVO.setTimeQuery(makeFragmentBetweenTimeQuery(timerel, colName, timeAt, endTimeAt));
                } else {
                    queryVO.setTimeQuery(makeFragmentTimeQuery(timerel, colName, timeAt));
                }

            } else {
                List<String> entityTimeProperies = getEntityTimeColumnNameList(queryVO);

                List<String> resultQueryList = new ArrayList<>();

                for (String item : entityTimeProperies) {
                    if (timerel.equalsIgnoreCase(TemporalOperator.BETWEEN_REL.getCode())) {
                        resultQueryList.add(makeFragmentBetweenTimeQuery(timerel, item, timeAt, endTimeAt));
                    } else {
                        resultQueryList.add(makeFragmentTimeQuery(timerel, item, timeAt));
                    }
                }

                if (resultQueryList.size() > 1) {
                    String resultQuery = String.join(" OR ", resultQueryList);
                    queryVO.setTimeQuery("(" + resultQuery + ")");
                } else if (resultQueryList.size() == 1) {
                    queryVO.setTimeQuery(resultQueryList.get(0));
                }
            }
        }
        return queryVO;
    }


    /**
     * timerel (AFTER, BEFORE ) ????????? ??????, query ??????
     *
     * @param timerel
     * @param colName
     * @param timeStr
     * @return
     */
    private String makeFragmentTimeQuery(String timerel, String colName, String timeStr) {

        StringBuilder timeQuery = new StringBuilder();
        if (timerel.equalsIgnoreCase(TemporalOperator.AFTER_REL.getCode())) {
            timeQuery.append(colName);
            timeQuery.append(" ");
            timeQuery.append(" > ");
            timeQuery.append("'" + timeStr + "'");
        } else if (timerel.equalsIgnoreCase(TemporalOperator.BEFORE_REL.getCode())) {
            timeQuery.append(colName);
            timeQuery.append(" ");
            timeQuery.append(" < ");
            timeQuery.append("'" + timeStr + "'");
        }

        log.debug("timeQuery : '{}'", timeQuery);

        return timeQuery.toString();
    }

    /**
     * timerel (Between) ????????? ??????, query ??????
     *
     * @param timerel
     * @param colName
     * @param timeStr
     * @param endTimeStr
     * @return
     */
    private String makeFragmentBetweenTimeQuery(String timerel, String colName, String timeStr, String endTimeStr) {
        StringBuilder timeQuery = new StringBuilder();
        timeQuery.append(colName);
        timeQuery.append(" ");
        timeQuery.append(timerel);
        timeQuery.append(" ");
        timeQuery.append("'" + timeStr + "'");
        timeQuery.append(" and ");
        timeQuery.append("'" + endTimeStr + "'");
        return timeQuery.toString();

    }


    /**
     * ???????????? ????????? ?????? ?????? ????????? ??? ?????? (full?????? ?????? partial ?????? ?????????)
     *
     * @param dbTableName
     * @return
     */
    private String getHistoryTableName(String dbTableName) {


        StringBuilder tableNameBuilder = new StringBuilder();


        if (UseYn.YES.getCode().equals(retrieveFullHistoryYn)) {
            tableNameBuilder.append(dbTableName).append(Constants.FULL_HIST_TABLE_PREFIX);

        } else {
            tableNameBuilder.append(dbTableName).append(Constants.PARTIAL_HIST_TABLE_PREFIX);

        }

        return tableNameBuilder.toString();

    }


    /**
     * Geo-Query???, ????????? ?????? Geo Column?????? ?????????
     *
     * ?????? ??????
     *  ?????? ?????? 1. application-properties??? ????????? ???????????? ????????? ?????? ??????
     *          2. 1?????? ???????????? x ??????, type??? RootAttribute ??? GEO_PROPERTY ??? ???????????? ???????????? ??????
     * @param dataModelCacheVO
     * @return
     */
    private String getDefaultLocationColName(DataModelCacheVO dataModelCacheVO) {

        String locationColName = null;

        Attribute locationAttr = dataModelCacheVO.getRootAttribute(defaultLocationAttrName);

        if (locationAttr != null) {
            locationColName = locationAttr.getName() + Constants.GEO_PREFIX_4326;

        } else {
            for (Attribute rootAttribute : dataModelCacheVO.getDataModelVO().getAttributes()) {
                if (rootAttribute.getAttributeType() == AttributeType.GEO_PROPERTY) {
                	locationColName = rootAttribute.getName() + Constants.GEO_PREFIX_4326;
                    break;
                }
            }
        }
        return locationColName;
    }

}
