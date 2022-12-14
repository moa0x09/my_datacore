package kr.re.keti.sc.dataservicebroker.subscription.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.re.keti.sc.dataservicebroker.common.code.Constants;
import kr.re.keti.sc.dataservicebroker.common.code.DataServiceBrokerCode;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdBadRequestException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdContextNotAvailableException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdNoExistTypeException;
import kr.re.keti.sc.dataservicebroker.common.exception.ngsild.NgsiLdResourceNotFoundException;
import kr.re.keti.sc.dataservicebroker.subscription.service.SubscriptionSVC;
import kr.re.keti.sc.dataservicebroker.subscription.vo.SubscriptionVO;
import kr.re.keti.sc.dataservicebroker.subscription.vo.SubscriptionVO.EntityInfo;
import kr.re.keti.sc.dataservicebroker.util.HttpHeadersUtil;
import kr.re.keti.sc.dataservicebroker.util.LogExecutionTime;
import kr.re.keti.sc.dataservicebroker.util.ValidateUtil;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class SubscriptionController {

    @Autowired
    private SubscriptionSVC subscriptionSVC;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${datacore.http.binding.response.log.yn:N}")
    private String isResponseLog;
    @Value("${entity.retrieve.default.limit:1000}")
    private Integer defaultLimit;
    @Value("${entity.default.storage}")
    protected DataServiceBrokerCode.BigDataStorageType bigDataStorageType;
    @Value("${entity.retrieve.include.context:Y}")
    private String includeContext;

    /**
     * ?????? ????????? ?????? (Query Subscriptions)
     * @param response
     * @param accept
     * @param limit   Maximum number of subscriptions to be retrieved
     * @throws Exception
     */
    @LogExecutionTime
    @GetMapping(value = "/subscriptions")
    public @ResponseBody void querySubscriptions(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 @RequestHeader(HttpHeaders.ACCEPT) String accept,
                                                 @RequestParam(value = "limit", required = false) Integer limit,
                                                 @RequestParam(value = "offset", required = false) Integer offset ) throws Exception {

        log.info("query Subscriptions request. accept={}, limit={}, offset={}", accept, limit, offset);

        // 1. subscription ??????
        Integer totalCount = subscriptionSVC.querySubscriptionsCount(limit, offset, DataServiceBrokerCode.JsonLdType.SUBSCRIPTION);
        List<SubscriptionVO> resultList = subscriptionSVC.querySubscriptions(limit, offset, DataServiceBrokerCode.JsonLdType.SUBSCRIPTION);

        // 2. ????????? property ????????? ?????? response body??? ????????? ??????
        if (DataServiceBrokerCode.UseYn.YES.getCode().equalsIgnoreCase(isResponseLog)) {
            log.info("response body : " + objectMapper.writeValueAsString(resultList));
        }

        HttpHeadersUtil.addPaginationLinkHeader(bigDataStorageType, request, response, accept, limit, offset, totalCount, defaultLimit);

        String primaryAccept = HttpHeadersUtil.getPrimaryAccept(accept);
        
        //TODO: ?????? ??? ????????? link??? ?????? ????????? ?????? ?????? ??????
        
        if(!ValidateUtil.isEmptyData(resultList)) {
       		if(!Constants.APPLICATION_LD_JSON_VALUE.equals(primaryAccept)) {
       			//Link??? Context??? ??????????????? subscription ???????????? ?????? Context??? ?????? ??? ?????? Link ????????? ?????? ??? ??????. URI??? ??????
           		for (SubscriptionVO subscriptionVO : resultList) {
                		subscriptionVO.expandTerm(null);
                		subscriptionVO.setContext(null);
                }
            } else {//Accept??? ld+json ??? ??????
            	if (DataServiceBrokerCode.UseYn.NO.getCode().equalsIgnoreCase(includeContext)) {
            		for (SubscriptionVO subscriptionVO : resultList) {
            			subscriptionVO.expandTerm(null);
            			subscriptionVO.setContext(null);
                    }
            	}
        	}  
        }

        response.getWriter().print(objectMapper.writeValueAsString(resultList));
    }

    /**
     * ?????? ?????? by subscriptionId
     * @param subscriptionId ?????? subscriptionId
     * @return
     */
    @LogExecutionTime
    @GetMapping("/subscriptions/{subscriptionId}")
    public @ResponseBody
    void retrieveSubscription(	HttpServletResponse response, 
					    		@RequestHeader(HttpHeaders.ACCEPT) String accept, 
					    		@PathVariable String subscriptionId) throws Exception {

        log.info("retrieve Subscriptions request. accept={}, subscriptionId={}", accept, subscriptionId);

        // 1. ?????? ?????? ??????
        SubscriptionVO result = subscriptionSVC.retrieveSubscription(subscriptionId);

        // 2. ????????? subscription??? ?????? ??????, ResourceNotFound ??????
        // It is used when a client provided a subscription identifier (URI) not known to the system, see clause 6.3.2.
        if (result == null) {
            throw new NgsiLdResourceNotFoundException(DataServiceBrokerCode.ErrorCode.NOT_EXIST_ID, "There is no an existing Subscription which id");
        }
        // 3. ????????? property ????????? ?????? response body??? ????????? ??????
        if (DataServiceBrokerCode.UseYn.YES.getCode().equalsIgnoreCase(isResponseLog)) {
            log.info("response body : " + objectMapper.writeValueAsString(result));
        }
        
        String primaryAccept = HttpHeadersUtil.getPrimaryAccept(accept);
        
        //TODO: ?????? ??? ????????? link??? ?????? ????????? ?????? ?????? ??????
        
        if(!Constants.APPLICATION_LD_JSON_VALUE.equals(primaryAccept)) {
        	if (DataServiceBrokerCode.UseYn.YES.getCode().equalsIgnoreCase(includeContext)) {//Context??? Link??? ?????? ??????
        		HttpHeadersUtil.addContextLinkHeader(response, primaryAccept, result.getContext());
        		result.setContext(null);
           	}
        	else {// term expansion ?????? ??????
        		result.expandTerm(null);
        		result.setContext(null);
        	}
        } else {//Accept??? ld+json ??? ??????
        	if (DataServiceBrokerCode.UseYn.NO.getCode().equalsIgnoreCase(includeContext)) {
        		result.expandTerm(null);
        		result.setContext(null);
        	}
        }

        response.getWriter().print(objectMapper.writeValueAsString(result));

    }

    /**
     * ?????? ?????? (Create Subscription)
     * @param response
     * @param subscriptionVO ????????? ?????? body
     */
    @PostMapping("/subscriptions")
    public void createSubscription(	HttpServletRequest request,
    								HttpServletResponse response,
                                    @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                    @RequestHeader(value = HttpHeaders.LINK, required = false) String link,
    								@RequestBody SubscriptionVO subscriptionVO) throws Exception {

        log.info("create Subscription request. contentType={}, link={}, subscriptionVO={}", contentType, link, subscriptionVO);

        // 1. ?????? type ?????? (* ContextSourceRegistration??? ??????)
        validateTypeParameter(subscriptionVO.getType());
        subscriptionVO.setType(DataServiceBrokerCode.JsonLdType.SUBSCRIPTION.getCode());

        Integer result = null;
        try {
            // 1. context ?????? ????????? ??????
            validateContext(subscriptionVO, contentType);

            // 2. ?????? ?????? ??????
            List<String> links = HttpHeadersUtil.extractLinkUris(link);
            result = subscriptionSVC.createSubscription(subscriptionVO, links);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.ALREADY_EXISTS, "Already Exists. subscriptionID=" + subscriptionVO.getId(), e);
        } catch (NgsiLdNoExistTypeException e) {
            throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER, e.getMessage(), e);
        } catch (NgsiLdContextNotAvailableException e) {
        	throw e;
        }

        // 3. ?????? ?????? ??????
        if (result != null) {
            response.setStatus(HttpStatus.CREATED.value());
            SubscriptionVO resultVO = new SubscriptionVO();
            resultVO.setId(subscriptionVO.getId());
            response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(Constants.CHARSET_ENCODING);
            response.getWriter().print(objectMapper.writeValueAsString(resultVO));
        } else {
            //  ????????? ????????? ?????? ??????, ?????? ??????
            log.error("HTTP Binding ERROR");
        }
    }

    private void validateTypeParameter(String type) {
        if(!DataServiceBrokerCode.JsonLdType.SUBSCRIPTION.getCode().equals(type)) {
            throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER,
                    "should equal type=" + DataServiceBrokerCode.JsonLdType.SUBSCRIPTION.getCode());
        }
    }

    /**
     * ?????? ??????  (Delete Subscription)
     * @param response
     * @param subscriptionId ?????? ?????? subscriptionId
     */
    @DeleteMapping("/subscriptions/{subscriptionId}")
    public void delete(HttpServletRequest request,
                       HttpServletResponse response,
                       @PathVariable String subscriptionId) throws Exception {

        log.info("delete Subscription request. subscriptionId={}", subscriptionId);

        // 1. ????????? ?????? ??????
        Integer result = subscriptionSVC.deleteSubscription(subscriptionId);
        // 2. ?????? ?????? ??????
        if (result > 0) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
        } else {
            //???????????? subscriptionId ?????? ??????
            throw new NgsiLdResourceNotFoundException(DataServiceBrokerCode.ErrorCode.NOT_EXIST_ID, "There is no an existing Subscription which id");
        }
    }


    /**
     * ?????? ???????????? (Update Subscription)
     * @param response
     * @param subscriptionId ???????????? ?????? attribute ??????
     * @param subscriptionVO ????????? ?????? body
     */
    @PatchMapping("/subscriptions/{subscriptionId}")
    public void updateSubscription(HttpServletRequest request,
                                   HttpServletResponse response,
                                   @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                   @RequestHeader(value = HttpHeaders.LINK, required = false) String link,
                                   @RequestBody SubscriptionVO subscriptionVO,
                                   @PathVariable("subscriptionId") String subscriptionId) throws Exception {

        log.info("update Subscription request. contentType={}, link={}, subscriptionVO={}, subscriptionId={}",
                contentType, link, subscriptionVO, subscriptionId);

        // 1. ?????? type ?????? (* ContextSourceRegistration??? ??????)
        subscriptionVO.setId(subscriptionId);
        subscriptionVO.setType(DataServiceBrokerCode.JsonLdType.SUBSCRIPTION.getCode());

        // 2. context ?????? ????????? ??????
        validateContext(subscriptionVO, contentType);

        // 3. ?????? ???????????? ??????
        List<String> links = HttpHeadersUtil.extractLinkUris(link);
        Integer resultCnt = subscriptionSVC.updateSubscription(subscriptionId, subscriptionVO, links);

        // 3. ?????? ?????? ??????
        if (resultCnt > 0) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
        } else {
            //???????????? subscriptionId ?????? ??????
            throw new NgsiLdResourceNotFoundException(DataServiceBrokerCode.ErrorCode.NOT_EXIST_ID, "There is no an existing Subscription which id");
        }

    }



    private void validateContext(SubscriptionVO subscriptionVO, String contentType) {
        // accept??? application/json ??? ??????
        if(Constants.APPLICATION_JSON_VALUE.equals(contentType)) {
            // contentType??? application/json??? ?????? @context ????????????
            if(!ValidateUtil.isEmptyData(subscriptionVO.getContext())) {
                throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER,
                        "Invalid Request Content. @context parameter cannot be used in contentType=application/json");
            }

        // accept??? application/ld+json ??? ??????
        } else if(Constants.APPLICATION_LD_JSON_VALUE.equals(contentType)) {
            if(ValidateUtil.isEmptyData(subscriptionVO.getContext())) {
                throw new NgsiLdBadRequestException(DataServiceBrokerCode.ErrorCode.INVALID_PARAMETER,
                        "Invalid Request Content. @context is empty. contentType=application/ld+json");
            }
        }
    }
}
