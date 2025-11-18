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

/**
 * Constants for the SemanticCache mediator.
 * <p>
 * Contains configuration defaults, error codes, and other constant values used throughout
 * the semantic caching implementation.
 */
public class SemanticCacheConstants {

    // Protocol and Response Configuration
    public static final String STATUS_CODE_OK = "200";

    // Cache Configuration
    public static final String DEFAULT_THRESHOLD = "80";
    public static final String REQUEST_EMBEDDINGS = "requestEmbeddings";
    public static final String EMBEDDING_DIMENSION = "embedding_dimension";
    
    // HTTP Headers and Status
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String NO_STORE_STRING = "no-store";

    // API Configuration
    public static final String API_ID = "api_id";
    public static final String API_UUID = "API_UUID";
    public static final String THRESHOLD = "threshold";
    
    // Text Processing
    public static final String TEXT_CLEAN_REGEX = "^\"|\"$";
}
