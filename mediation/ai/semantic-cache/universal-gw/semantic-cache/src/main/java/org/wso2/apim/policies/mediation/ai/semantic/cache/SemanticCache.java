/*
 *
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.wso2.apim.policies.mediation.ai.semantic.cache;

import com.jayway.jsonpath.JsonPath;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.commons.collections.map.MultiValueMap;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.ParseException;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2Sender;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;
import org.wso2.carbon.apimgt.api.VectorDBProviderService;
import org.wso2.apim.policies.mediation.ai.semantic.cache.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.CachableResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * Regex Semantic cache mediator.
 * <p>
 * A mediator that performs piiEntities-based validation on payloads according to specified patterns.
 * This guardrail can be configured with JSON path expressions to target specific parts of JSON payloads
 * and apply piiEntities pattern validation against them. The validation result can be inverted if needed.
 * <p>
 * When validation fails, the mediator triggers a fault sequence and enriches the message context
 * with appropriate error details.
 */
public class SemanticCache extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(SemanticCache.class);

    private String protocolType = SemanticCacheConstants.HTTP_PROTOCOL_TYPE;
    private String responseCodes = SemanticCacheConstants.ANY_RESPONSE_CODE;
    private int maxMessageSize = SemanticCacheConstants.DEFAULT_SIZE;
    private boolean cacheControlEnabled = SemanticCacheConstants.DEFAULT_ENABLE_CACHE_CONTROL;
    private boolean addAgeHeaderEnabled = SemanticCacheConstants.DEFAULT_ADD_AGE_HEADER;
    private static final String CONTENT_TYPE = SemanticCacheConstants.CONTENT_TYPE;
    private static final String SC_NOT_MODIFIED = SemanticCacheConstants.SC_NOT_MODIFIED;

    private int threshold = SemanticCacheConstants.DEFAULT_THRESHOLD;
    private String jsonPath;

    private VectorDBProviderService vectorDBProvider;
    private EmbeddingProviderService embeddingProvider;

    /**
     * Initializes the SemanticCache mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing Semantic Cache.");
        }

        this.embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();
        this.vectorDBProvider = ServiceReferenceHolder.getInstance().getVectorDBProvider();

        // Create index in the vector database if not already created
        try {
            int embeddingDimension = this.embeddingProvider.getEmbeddingDimension();
            Map<String, String> indexConfig = new HashMap<>();
            indexConfig.put(SemanticCacheConstants.EMBEDDING_DIMENSION, String.valueOf(embeddingDimension));
            this.vectorDBProvider.createIndex(indexConfig);
        } catch (APIManagementException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Destroys the SemanticCache mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // No specific resources to release
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Beginning payload validation.");
        }

        SynapseLog synLog = getLog(messageContext);
        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Start : SemanticCache mediator");

            if (synLog.isTraceTraceEnabled()) {
                synLog.traceTrace("Message : " + messageContext.getEnvelope());
            }
        }

        boolean result = true;
        try {
            if (messageContext.isResponse()) {
                processResponseMessage(messageContext, synLog);
            } else {
                result = processRequestMessage(messageContext, synLog);
            }
        } catch (Exception e) {
            logger.error("Exception occurred during mediation.", e);
        }

        return result;
    }

    private boolean processRequestMessage(MessageContext messageContext, SynapseLog synLog)
            throws APIManagementException {
        org.apache.axis2.context.MessageContext msgCtx =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        // Extract content from body and get embeddings
        String contentToEmbed = extractContent(msgCtx);
        if (contentToEmbed == null) {
            // Proceed without caching
            logger.error("No json content found in the message context.");
            return true;
        }

        double[] embeddings = this.embeddingProvider.getEmbedding(contentToEmbed);
        // Check if cache hit
        Map<String, String> filter = new HashMap<>();
        filter.put(SemanticCacheConstants.API_ID, (String) messageContext.getProperty(SemanticCacheConstants.API_UUID));
        filter.put(SemanticCacheConstants.THRESHOLD, String.valueOf(this.threshold));
        CachableResponse cachedResponse = this.vectorDBProvider.retrieve(embeddings, filter);

        if (cachedResponse != null && cachedResponse.getResponsePayload() != null) {
            // get the response from the cache and attach to the context and change the
            // direction of the message
            if (synLog.isTraceOrDebugEnabled()) {
                synLog.traceOrDebug("Cache-hit for message ID : " + messageContext.getMessageID());
            }
            //Validate the response based on max-age and no-cache headers.
            if (SemanticCacheConstants.HTTP_PROTOCOL_TYPE.equals(this.protocolType)
                    && cachedResponse.isCacheControlEnabled()) {
                return true;
            }
            // mark as a response and replace envelope from cache
            messageContext.setResponse(true);
            replaceEnvelopeWithCachedResponse(messageContext, synLog, msgCtx, cachedResponse);
            return false;
        }
        messageContext.setProperty(SemanticCacheConstants.REQUEST_EMBEDDINGS, embeddings);
        return true;
    }

    private String extractContent(org.apache.axis2.context.MessageContext msgCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("Extracting content from message context.");
        }

        // Case 1: JSON Payload
        if (JsonUtil.hasAJsonPayload(msgCtx)) {
            String jsonContent = JsonUtil.jsonPayloadToString(msgCtx);

            if (this.jsonPath == null || this.jsonPath.trim().isEmpty()) {
                return jsonContent;
            }

            try {
                String extracted = JsonPath.read(jsonContent, this.jsonPath).toString();
                return extracted.replaceAll(SemanticCacheConstants.TEXT_CLEAN_REGEX, "").trim();
            } catch (Exception e) {
                logger.warn("Failed to extract content using jsonPath: " + this.jsonPath, e);
            }
        }

        return null;
    }

    private void replaceEnvelopeWithCachedResponse(MessageContext synCtx, SynapseLog synLog,
                                                   org.apache.axis2.context.MessageContext msgCtx,
                                                   CachableResponse cachedResponse) {
        Map<String, Object> headerProperties;
        try {
            if (cachedResponse.isJson()) {
                byte[] payload = cachedResponse.getResponsePayload();
                OMElement response = JsonUtil.getNewJsonPayload(msgCtx, payload, 0,
                        payload.length, false, false);
                if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
                    msgCtx.getEnvelope().getBody().getFirstElement().detach();
                }
                msgCtx.getEnvelope().getBody().addChild(response);

            }
        } catch (AxisFault e) {
            handleException("Error creating response OM from cache - ", synCtx);
        }
        if (SemanticCacheConstants.HTTP_PROTOCOL_TYPE.equals(this.protocolType)) {
            if (cachedResponse.getStatusCode() != null) {
                msgCtx.setProperty(NhttpConstants.HTTP_SC,
                        Integer.parseInt(cachedResponse.getStatusCode()));
            }
            if (cachedResponse.getStatusReason() != null) {
                msgCtx.setProperty(PassThroughConstants.HTTP_SC_DESC, cachedResponse.getStatusReason());
            }
            //Set Age header to the cached response.
            if (cachedResponse.isAddAgeHeaderEnabled()) {
                this.setAgeHeader(cachedResponse, msgCtx);
            }
        }
        if (msgCtx.isDoingREST()) {

            msgCtx.removeProperty(PassThroughConstants.NO_ENTITY_BODY);
            msgCtx.removeProperty(Constants.Configuration.CONTENT_TYPE);
        }
        if ((headerProperties = cachedResponse.getHeaderProperties()) != null) {
            Map<String, Object> clonedMap = new HashMap<>(headerProperties);
            msgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, clonedMap);
            msgCtx.setProperty(Constants.Configuration.MESSAGE_TYPE,
                    clonedMap.get(Constants.Configuration.MESSAGE_TYPE));
            msgCtx.setProperty(Constants.Configuration.CONTENT_TYPE,
                    headerProperties.get(CONTENT_TYPE));
        }

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Request message " + synCtx.getMessageID() +
                    " was served from the cache");
        }
        // send the response back if there is not onCacheHit is specified
        synCtx.setTo(null);
        Axis2Sender.sendBack(synCtx);
    }

    public void setAgeHeader(CachableResponse cachedResponse,
                             org.apache.axis2.context.MessageContext msgCtx) {
        Map excessHeaders = new MultiValueMap();
        long responseCachedTime = cachedResponse.getResponseFetchedTime();
        long age = Math.abs((responseCachedTime - System.currentTimeMillis()) / 1000);
        excessHeaders.put(HttpHeaders.AGE, String.valueOf(age));

        msgCtx.setProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS, excessHeaders);
    }

    private void processResponseMessage(MessageContext messageContext, SynapseLog synLog) throws java.text.ParseException {
        org.apache.axis2.context.MessageContext msgCtx = ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        double[] embeddings = (double[]) messageContext.getProperty(SemanticCacheConstants.REQUEST_EMBEDDINGS);

        if (embeddings != null) {
            // Init cache response
            CachableResponse response = new CachableResponse();
            String httpMethod = (String) msgCtx.getProperty(Constants.Configuration.HTTP_METHOD);
            response.setHttpMethod(httpMethod);
            response.setProtocolType(protocolType);
            response.setResponseCodePattern(responseCodes);
            response.setMaxMessageSize(maxMessageSize);
            response.setCacheControlEnabled(cacheControlEnabled);
            response.setAddAgeHeaderEnabled(addAgeHeaderEnabled);

            boolean toCache = true;
            if (SemanticCacheConstants.HTTP_PROTOCOL_TYPE.equals(response.getProtocolType())) {
                Object httpStatus = msgCtx.getProperty(NhttpConstants.HTTP_SC);
                String statusCode = null;
                //Honor no-store header if cacheControlEnabled.
                // If "no-store" header presents in the response, returned response can not be cached.
                if (response.isCacheControlEnabled() && this.isNoStore(msgCtx)) {
                    response.clean();
                    return;
                }
                //Need to check the data type of HTTP_SC to avoid classcast exceptions.
                if (httpStatus instanceof String) {
                    statusCode = ((String) httpStatus).trim();
                } else if (httpStatus != null) {
                    statusCode = String.valueOf(httpStatus);
                }

                if (statusCode != null) {
                    //If status code is SC_NOT_MODIFIED then return the cached response.
                    // TODO: What is happening here?
                    if (statusCode.equals(SC_NOT_MODIFIED)) {
                        replaceEnvelopeWithCachedResponse(messageContext, synLog, msgCtx, response);
                        return;
                    }
                    // Now create matcher object.
                    Matcher m = response.getResponseCodePattern().matcher(statusCode);
                    if (m.matches()) {
                        response.setStatusCode(statusCode);
                        response.setStatusReason((String) msgCtx.getProperty(PassThroughConstants.HTTP_SC_DESC));
                    } else {
                        toCache = false;
                    }
                }
            }
            if (toCache) {
                if (JsonUtil.hasAJsonPayload(msgCtx)) {
                    byte[] responsePayload = JsonUtil.jsonPayloadToByteArray(msgCtx);
                    if (response.getMaxMessageSize() > -1 &&
                            responsePayload.length > response.getMaxMessageSize()) {
                        synLog.traceOrDebug(
                                "Message size exceeds the upper bound for caching, "
                                        + "request will not be cached");
                        return;
                    }
                    response.setResponsePayload(responsePayload);
                    response.setJson(true);
                }

                if (synLog.isTraceOrDebugEnabled()) {
                    synLog.traceOrDebug(
                            "Storing the response for the message with ID : "
                                    + messageContext.getMessageID() + " " + "with request hash ID : "
                                    + response.getRequestHash() + " in the cache");
                }

                Map<String, String> headers =
                        (Map<String, String>) msgCtx.getProperty(
                                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
                String messageType = (String) msgCtx.getProperty(Constants.Configuration.MESSAGE_TYPE);
                Map<String, Object> headerProperties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

                //Store the response fetched time.
                if (response.isCacheControlEnabled() || response.isAddAgeHeaderEnabled()) {
                    try {
                        this.setResponseCachedTime(headers, response);
                    } catch (ParseException e) {
                        synLog.auditWarn("Error occurred while parsing the date." + e.getMessage());
                    }
                }
                //Individually copying All TRANSPORT_HEADERS to headerProperties Map instead putting whole
                //TRANSPORT_HEADERS map as single Key/Value pair to fix hazelcast serialization issue.
                headerProperties.putAll(headers);
                headerProperties.put(Constants.Configuration.MESSAGE_TYPE, messageType);
                headerProperties.put(SemanticCacheConstants.CACHE_KEY, response.getRequestHash());
                response.setHeaderProperties(headerProperties);
                msgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headerProperties);

                // Store cachedResponse in the vectorDB
                try {
                    Map<String, String> filter = new HashMap<>();
                    filter.put("api_id", (String) messageContext.getProperty("API_UUID"));
                    this.vectorDBProvider.store(embeddings, response, filter);
                } catch (APIManagementException e) {
                    throw new RuntimeException(e);
                }
            } else {
                response.clean();
            }
        } else {
            synLog.auditWarn("A response message without a valid mapping to the " +
                    "request hash found. Unable to store the response in cache");
        }
    }

    public void setResponseCachedTime(Map<String, String> headers, CachableResponse response) throws
            ParseException, java.text.ParseException {
        long responseFetchedTime;
        String dateHeaderValue;
        if (headers != null && (dateHeaderValue = headers.get(HttpHeaders.DATE)) != null) {
            SimpleDateFormat format = new SimpleDateFormat(SemanticCacheConstants.DATE_PATTERN);
            Date d = format.parse(dateHeaderValue);
            responseFetchedTime = d.getTime();
        } else {
            responseFetchedTime = System.currentTimeMillis();
        }
        response.setResponseFetchedTime(responseFetchedTime);
    }

    public boolean isNoStore(org.apache.axis2.context.MessageContext msgCtx) {
        ConcurrentHashMap<String, Object> headerProperties = new ConcurrentHashMap<>();
        Map<String, String> headers = (Map<String, String>) msgCtx.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String cacheControlHeaderValue = null;

        //Copying All TRANSPORT_HEADERS to headerProperties Map.
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerProperties.put(entry.getKey(), entry.getValue());
        }
        if (headerProperties.get(HttpHeaders.CACHE_CONTROL) != null) {
            cacheControlHeaderValue = String.valueOf(headerProperties.get(HttpHeaders.CACHE_CONTROL));
        }

        return StringUtils.isNotEmpty(cacheControlHeaderValue)
                && cacheControlHeaderValue.contains(SemanticCacheConstants.NO_STORE_STRING);
    }

    public double getThreshold() {

        return threshold;
    }

    public void setThreshold(int threshold) {

        this.threshold = threshold;
    }

    public String getJsonPath() {

        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {

        this.jsonPath = jsonPath;
    }
}
