# Semantic Cache Mediator for WSO2 API Manager Universal Gateway

The **Semantic Cache** is a custom Synapse mediator for **WSO2 API Manager Universal Gateway** that enables intelligent caching for API requests and responses using semantic similarity. Instead of relying solely on exact matches, this mediator uses embeddings and a vector database to cache and retrieve responses for requests that are semantically similar.

## Features

- Semantic caching for API requests and responses
- Uses embeddings to determine similarity between requests
- Configurable threshold for cache matching
- Supports JSONPath to target specific parts of payloads for embedding
- Integrates with pluggable embedding and vector DB providers
- Honors HTTP caching headers and cache-control directives

## Prerequisites

- Java 11 (JDK)
- Maven 3.6.x or later
- WSO2 API Manager or Synapse-compatible runtime
- Embedding provider and vector DB provider configured

## Building the Project

To compile and package the mediator:

```bash
mvn clean install
```

> ℹ️ This will generate a `.jar` file in the `target/` directory containing the mediator.

## How to Use

Follow these steps to integrate the Semantic Cache policy into your WSO2 API Manager instance:

1. **Copy the Mediator JAR**

```bash
cp target/org.wso2.apim.policies.mediation.ai.semantic-cache-<version>.jar \
   $APIM_HOME/repository/components/dropins/
```

2. **Register the Policy in Publisher**

- Use the `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.
- Place them in your custom policy deployment directory or register them using the REST API.

3. **Apply and Deploy the Policy**

- Go to **API Publisher**
- Select your API
- Go to **Develop > API Configurations > Policies > Request/Response Flow**
- Click **Add Policy** and choose **Semantic Cache**
- Configure the policy parameters (threshold, jsonpath, etc.)
- **Save and Deploy** the API

## Example Policy Configuration

Add the following to the `$APIM_HOME/repository/conf/deployment.toml` file to configure your embedding and vector DB providers:

```toml
[[apim.ai.embedding_provider]]
type = "<your-embedding-provider-type>"
[apim.ai.embedding_provider.properties]
# Add provider-specific properties here

[[apim.ai.vector_db_provider]]
type = "<your-vector-db-provider-type>"
[apim.ai.vector_db_provider.properties]
# Add provider-specific properties here
```

Example policy parameters:

| Field                     | Example                  |
|---------------------------|--------------------------|
| `Semantic Cache Policy Name` | `SemanticCache`           |
| `Threshold`               | `0.35`                   |
| `JSONPath`                | `$.messages[-1].content` |

**Threshold**: Dissimilarity threshold as a decimal value for semantic matching, determining the required similarity for cache matches. The Semantic Cache mediator uses L2 (Euclidean) distance to measure similarity between embeddings.

- For **normalized embeddings** (where vectors are unit length), the typical threshold range is **0.0** (exact match) up to **2.0** (maximum possible distance).
- For **unnormalized embeddings**, the range depends on the embedding model and data, and may be much larger.

Lower threshold values (closer to 0) enforce stricter semantic similarity, while higher values allow weaker matches. Always refer to your embedding provider's documentation for recommended threshold values and normalization details.


### Example Usage

1. Create an API and add the **Semantic Cache** policy in the request flow with the following configuration:

| Field                     | Example                  |
|---------------------------|--------------------------|
| `Semantic Cache Policy Name` | `SemanticCache`           |
| `Threshold`               | `0.35`                   |
| `JSONPath`                | `$.messages[-1].content` |

2. Save and re-deploy the API.
3. When a request is received, the mediator will:
   - Extract the relevant content using JSONPath
   - Generate embeddings
   - Search for semantically similar cached responses
   - Serve cached response if found, otherwise cache the new response

### Example Request Payload

```json
{
  "model": "mistral-small-latest",
  "messages": [
    {
      "role": "user",
      "content": "What is the weather like in Paris today?"
    }
  ]
}
```

If a previous request with similar meaning (e.g., "Tell me today's weather in Paris") was made, the cached response will be served.
