/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
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
 */

package org.wso2.apim.policies.mediation.ai.semantic.cache;

import java.io.Serializable;
import java.util.Map;

/**
 * This class represents a cached response in the API Management system.
 * It contains the response payload, timeout, HTTP headers, status code,
 * and other relevant information for caching purposes.
 */
public class CacheableResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * This holds the reference to the response for json
     */
    private byte[] responsePayload = null;

    /**
     * This holds the HTTP Header Properties of the response.
     */
    private Map<String, Object> headerProperties;

    /**
     * The HTTP status code number of the response
     */
    private String statusCode;

    /**
     * The HTTP response's Reason- Phrase that is sent by the backend.
     */
    private String statusReason;

    /**
     * This method gives the cached response payload for json as a byte array
     *
     * @return byte[] representing the cached response payload for json
     */
    public byte[] getResponsePayload() {
        return responsePayload;
    }

    /**
     * This method sets the response payload to the cache as a byte array
     *
     * @param responsePayload - response payload to be stored in to the cache as a byte array
     */
    public void setResponsePayload(byte[] responsePayload) {
        this.responsePayload = responsePayload;
    }


    /**
     * This method gives the HTTP Header Properties of the response
     *
     * @return Map<String, Object> representing the HTTP Header Properties
     */
    public Map<String, Object> getHeaderProperties() {
        return headerProperties;
    }

    /**
     * This method sets the HTTP Header Properties of the response
     *
     * @param headerProperties HTTP Header Properties to be stored in to cache as a map
     */
    public void setHeaderProperties(Map<String, Object> headerProperties) {
        this.headerProperties = headerProperties;
    }

    /**
     * @return HTTP status code number of the response
     */
    public String getStatusCode() {
        return statusCode;
    }

    /**
     * Sets the HTTP status code number of the response
     *
     * @param statusCode HTTP status code number of the response
     */
    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    /**
     * @return HTTP response's Reason- Phrase that is sent by the backend.
     */
    public String getStatusReason() {
        return statusReason;
    }

    /**
     * Sets the HTTP response's Reason-Phrase that is sent by the backend.
     *
     * @param statusReason HTTP response's Reason-Phrase that is sent by the backend.
     */
    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }
}