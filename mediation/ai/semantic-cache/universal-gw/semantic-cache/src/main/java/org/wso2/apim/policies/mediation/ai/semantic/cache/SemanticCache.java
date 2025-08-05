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
 * Semantic Cache mediator.
 * <p>
 * A mediator that implements semantic caching for API requests by generating embeddings of request content
 * and storing/retrieving responses from a vector database. This enables intelligent caching where similar
 * requests (based on semantic similarity) can return cached responses even if they are not exact matches.
 * <p>
 * The mediator generates embeddings for incoming request content using an embedding provider service,
 * searches for semantically similar cached responses within a configurable similarity threshold,
 * and serves cached responses when found. For cache misses, the request proceeds normally and the
 * response is cached for future similar requests.
 * <p>
 * Configuration supports JSON path expressions to target specific parts of JSON payloads for embedding
 * generation, similarity threshold settings, and various HTTP caching headers control.
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
     * <p>
     * Sets up the embedding provider and vector database services, creates the necessary vector index
     * with appropriate embedding dimensions, and initializes the caching infrastructure.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing Semantic Cache mediator.");
        }
        
        this.embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();
        this.vectorDBProvider = ServiceReferenceHolder.getInstance().getVectorDBProvider();
        
        try {
            int embeddingDimension = this.embeddingProvider.getEmbeddingDimension();
            Map<String, String> indexConfig = new HashMap<>();
            indexConfig.put(SemanticCacheConstants.EMBEDDING_DIMENSION, String.valueOf(embeddingDimension));
            this.vectorDBProvider.createIndex(indexConfig);
        } catch (APIManagementException e) {
            logger.error("Error initializing Semantic Cache mediator.", e);
            throw new RuntimeException("Failed to initialize Semantic Cache", e);
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Semantic Cache mediator initialized successfully.");
        }
    }

    /**
     * Destroys the SemanticCache mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // No specific resources to release
    }

    /**
     * Executes the SemanticCache mediation logic.
     * <p>
     * For request messages, attempts to find semantically similar cached responses and serves them
     * if found within the similarity threshold. For response messages, caches the response with
     * the request embeddings for future similar requests.
     *
     * @param messageContext The message context containing the payload to process.
     * @return {@code true} if mediation should continue, {@code false} if processing should halt.
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Beginning semantic cache mediation.");
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
                processResponseMessage(messageContext);
            } else {
                result = processRequestMessage(messageContext);
            }
        } catch (Exception e) {
            logger.error("Exception occurred during semantic cache mediation.", e);
            return false;
        }
        
        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("End : SemanticCache mediator");
        }
        
        return result;
    }

    /**
     * Processes incoming request messages for semantic cache lookup.
     * <p>
     * Extracts content from the request, generates embeddings, and searches for semantically
     * similar cached responses. If a cache hit is found, serves the cached response immediately.
     * Otherwise, stores the request embeddings for response caching and continues processing.
     *
     * @param messageContext The message context containing the request.
     * @return {@code true} if processing should continue, {@code false} if cached response was served.
     * @throws APIManagementException If embedding generation or cache lookup fails.
     */
    private boolean processRequestMessage(MessageContext messageContext)
            throws APIManagementException {
        org.apache.axis2.context.MessageContext msgCtx =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        String contentToEmbed = extractContent(msgCtx);
        if (contentToEmbed == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("No JSON content found in the message context - skipping cache lookup.");
            }
            return true;
        }

        double[] embeddings = this.embeddingProvider.getEmbedding(contentToEmbed);
        Map<String, String> filter = new HashMap<>();
        filter.put(SemanticCacheConstants.API_ID, (String) messageContext.getProperty(SemanticCacheConstants.API_UUID));
        filter.put(SemanticCacheConstants.THRESHOLD, String.valueOf(this.threshold));

        CachableResponse cachedResponse = this.vectorDBProvider.retrieve(embeddings, filter);
        if (cachedResponse != null && cachedResponse.getResponsePayload() != null) {
            if (SemanticCacheConstants.HTTP_PROTOCOL_TYPE.equals(this.protocolType)
                    && cachedResponse.isCacheControlEnabled()) {
                return true;
            }

            messageContext.setResponse(true);
            replaceEnvelopeWithCachedResponse(messageContext, msgCtx, cachedResponse);
            return false;
        }

        // Cache miss - store embeddings for response caching
        messageContext.setProperty(SemanticCacheConstants.REQUEST_EMBEDDINGS, embeddings);
        return true;
    }

    /**
     * Extracts content from the message context for embedding generation.
     * <p>
     * Retrieves JSON content from the message context and optionally applies JsonPath
     * expressions to extract specific portions of the payload for embedding.
     *
     * @param msgCtx The Axis2 message context containing the payload.
     * @return The extracted content string, or null if no JSON content is found.
     */
    private String extractContent(org.apache.axis2.context.MessageContext msgCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("Extracting content from message context.");
        }

        if (JsonUtil.hasAJsonPayload(msgCtx)) {
            String jsonContent = JsonUtil.jsonPayloadToString(msgCtx);
            if (StringUtils.isBlank(this.jsonPath)) {
                return jsonContent;
            }

            try {
                String extracted = JsonPath.read(jsonContent, this.jsonPath).toString();
                return extracted.replaceAll(SemanticCacheConstants.TEXT_CLEAN_REGEX, "").trim();
            } catch (Exception e) {
                logger.warn("Failed to extract content using jsonPath: " + this.jsonPath, e);
                // Fall back to full JSON content
                return jsonContent;
            }
        }
        return null;
    }

    /**
     * Replaces the current message envelope with a cached response.
     * <p>
     * Reconstructs the message envelope using the cached response payload and headers,
     * sets appropriate HTTP status codes and headers, and sends the response back to the client.
     *
     * @param synCtx The Synapse message context.
     * @param msgCtx The Axis2 message context.
     * @param cachedResponse The cached response to serve.
     */
    private void replaceEnvelopeWithCachedResponse(MessageContext synCtx,
                                                   org.apache.axis2.context.MessageContext msgCtx,
                                                   CachableResponse cachedResponse) {
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
            logger.error("Error creating response OM from cache", e);
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
            if (cachedResponse.isAddAgeHeaderEnabled()) {
                setAgeHeader(cachedResponse, msgCtx);
            }
        }

        if (msgCtx.isDoingREST()) {
            msgCtx.removeProperty(PassThroughConstants.NO_ENTITY_BODY);
            msgCtx.removeProperty(Constants.Configuration.CONTENT_TYPE);
        }

        Map<String, Object> headerProperties = cachedResponse.getHeaderProperties();
        if (headerProperties != null) {
            Map<String, Object> clonedMap = new HashMap<>(headerProperties);
            msgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, clonedMap);
            msgCtx.setProperty(Constants.Configuration.MESSAGE_TYPE,
                    clonedMap.get(Constants.Configuration.MESSAGE_TYPE));
            msgCtx.setProperty(Constants.Configuration.CONTENT_TYPE,
                    headerProperties.get(CONTENT_TYPE));
        }

        synCtx.setTo(null);
        Axis2Sender.sendBack(synCtx);
    }

    /**
     * Sets the Age header for cached responses to indicate cache staleness.
     * <p>
     * Calculates the time elapsed since the response was cached and adds an Age header
     * to inform clients about the freshness of the cached content.
     *
     * @param cachedResponse The cached response containing the original fetch time.
     * @param msgCtx The Axis2 message context to set the header on.
     */
    public void setAgeHeader(CachableResponse cachedResponse,
                             org.apache.axis2.context.MessageContext msgCtx) {
        MultiValueMap excessHeaders = new MultiValueMap();
        long responseCachedTime = cachedResponse.getResponseFetchedTime();
        long age = Math.abs((System.currentTimeMillis() - responseCachedTime) / 1000);
        excessHeaders.put(HttpHeaders.AGE, String.valueOf(age));
        msgCtx.setProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS, excessHeaders);
    }

    /**
     * Processes outgoing response messages for caching.
     * <p>
     * Retrieves the request embeddings stored during request processing and caches the response
     * along with relevant metadata for future semantic cache lookups.
     *
     * @param messageContext The message context containing the response.
     * @throws java.text.ParseException If date parsing for cache headers fails.
     */
    private void processResponseMessage(MessageContext messageContext)
            throws java.text.ParseException {
        org.apache.axis2.context.MessageContext msgCtx =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        double[] embeddings = (double[]) messageContext.getProperty(SemanticCacheConstants.REQUEST_EMBEDDINGS);
        if (embeddings == null) {
            return;
        }

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

            if (response.isCacheControlEnabled() && isNoStore(msgCtx)) {
                response.clean();
                return;
            }

            if (httpStatus instanceof String) {
                statusCode = ((String) httpStatus).trim();
            } else if (httpStatus != null) {
                statusCode = String.valueOf(httpStatus);
            }

            if (statusCode != null) {
                if (statusCode.equals(SC_NOT_MODIFIED)) {
                    replaceEnvelopeWithCachedResponse(messageContext, msgCtx, response);
                    return;
                }

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
                    return;
                }
                response.setResponsePayload(responsePayload);
                response.setJson(true);
            }

            Map<String, String> headers = (Map<String, String>) msgCtx.getProperty(
                    org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            String messageType = (String) msgCtx.getProperty(Constants.Configuration.MESSAGE_TYPE);
            Map<String, Object> headerProperties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

            if (response.isCacheControlEnabled() || response.isAddAgeHeaderEnabled()) {
                try {
                    setResponseCachedTime(headers, response);
                } catch (ParseException e) {
                }
            }

            if (headers != null) {
                headerProperties.putAll(headers);
            }
            headerProperties.put(Constants.Configuration.MESSAGE_TYPE, messageType);
            headerProperties.put(SemanticCacheConstants.CACHE_KEY, response.getRequestHash());
            response.setHeaderProperties(headerProperties);
            msgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headerProperties);

            try {
                Map<String, String> filter = new HashMap<>();
                filter.put(SemanticCacheConstants.API_ID,
                        (String) messageContext.getProperty(SemanticCacheConstants.API_UUID));
                this.vectorDBProvider.store(embeddings, response, filter);
            } catch (APIManagementException e) {
                logger.error("Error storing response in vector database.", e);
                throw new RuntimeException("Failed to store response in cache", e);
            }
        } else {
            response.clean();
        }
    }

    /**
     * Sets the response cached timestamp based on the Date header or current time.
     * <p>
     * Parses the Date header from the response to determine when the response was generated,
     * or falls back to the current system time if no Date header is present.
     *
     * @param headers The response headers map.
     * @param response The cachable response object to set timestamp on.
     * @throws ParseException If the Date header cannot be parsed.
     * @throws java.text.ParseException If date parsing fails.
     */
    public void setResponseCachedTime(Map<String, String> headers, CachableResponse response)
            throws ParseException, java.text.ParseException {
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

    /**
     * Checks if the response contains a no-store cache control directive.
     * <p>
     * Examines the Cache-Control header to determine if the response should not be cached
     * according to HTTP caching standards.
     *
     * @param msgCtx The Axis2 message context containing response headers.
     * @return {@code true} if no-store directive is present, {@code false} otherwise.
     */
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

    public int getThreshold() {
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
