/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.apache.pulsar.broker.lookup;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.common.api.Commands.newLookupErrorResponse;
import static org.apache.pulsar.common.api.Commands.newLookupResponse;

import io.netty.buffer.ByteBuf;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.ws.rs.Encoded;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.web.PulsarWebResource;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopicResponse.LookupType;
import org.apache.pulsar.common.api.proto.PulsarApi.ServerError;
import org.apache.pulsar.common.lookup.data.LookupData;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicLookupBase extends PulsarWebResource {

    private static final String LOOKUP_PATH_V1 = "/lookup/v2/destination/";
    private static final String LOOKUP_PATH_V2 = "/lookup/v2/topic/";

    protected void internalLookupTopicAsync(TopicName topicName, boolean authoritative, AsyncResponse asyncResponse) {
        if (!pulsar().getBrokerService().getLookupRequestSemaphore().tryAcquire()) {
            log.warn("No broker was found available for topic {}", topicName);
            asyncResponse.resume(new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE));
            return;
        }

        try {
            validateClusterOwnership(topicName.getCluster());
            checkConnect(topicName);
            validateGlobalNamespaceOwnership(topicName.getNamespaceObject());
        } catch (WebApplicationException we) {
            // Validation checks failed
            log.error("Validation check failed: {}", we.getMessage());
            completeLookupResponseExceptionally(asyncResponse, we);
            return;
        } catch (Throwable t) {
            // Validation checks failed with unknown error
            log.error("Validation check failed: {}", t.getMessage(), t);
            completeLookupResponseExceptionally(asyncResponse, new RestException(t));
            return;
        }

        CompletableFuture<Optional<LookupResult>> lookupFuture = pulsar().getNamespaceService()
                .getBrokerServiceUrlAsync(topicName, authoritative);

        lookupFuture.thenAccept(optionalResult -> {
            if (optionalResult == null || !optionalResult.isPresent()) {
                log.warn("No broker was found available for topic {}", topicName);
                completeLookupResponseExceptionally(asyncResponse,
                        new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE));
                return;
            }

            LookupResult result = optionalResult.get();
            // We have found either a broker that owns the topic, or a broker to which we should redirect the client to
            if (result.isRedirect()) {
                boolean newAuthoritative = this.isLeaderBroker();
                URI redirect;
                try {
                    String redirectUrl = isRequestHttps() ? result.getLookupData().getHttpUrlTls()
                            : result.getLookupData().getHttpUrl();
                    checkNotNull(redirectUrl, "Redirected cluster's service url is not configured");
                    String lookupPath = topicName.isV2() ? LOOKUP_PATH_V2 : LOOKUP_PATH_V1;
                    redirect = new URI(String.format("%s%s%s?authoritative=%s", redirectUrl, lookupPath,
                            topicName.getLookupName(), newAuthoritative));
                } catch (URISyntaxException | NullPointerException e) {
                    log.error("Error in preparing redirect url for {}: {}", topicName, e.getMessage(), e);
                    completeLookupResponseExceptionally(asyncResponse, e);
                    return;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Redirect lookup for topic {} to {}", topicName, redirect);
                }
                completeLookupResponseExceptionally(asyncResponse,
                        new WebApplicationException(Response.temporaryRedirect(redirect).build()));

            } else {
                // Found broker owning the topic
                if (log.isDebugEnabled()) {
                    log.debug("Lookup succeeded for topic {} -- broker: {}", topicName, result.getLookupData());
                }
                completeLookupResponseSuccessfully(asyncResponse, result.getLookupData());
            }
        }).exceptionally(exception -> {
            log.warn("Failed to lookup broker for topic {}: {}", topicName, exception.getMessage(), exception);
            completeLookupResponseExceptionally(asyncResponse, exception);
            return null;
        });
    }

    protected String internalGetNamespaceBundle(TopicName topicName) {
        validateSuperUserAccess();
        try {
            NamespaceBundle bundle = pulsar().getNamespaceService().getBundle(topicName);
            return bundle.getBundleRange();
        } catch (Exception e) {
            log.error("[{}] Failed to get namespace bundle for {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
    }

    /**
     *
     * Lookup broker-service address for a given namespace-bundle which contains given topic.
     *
     * a. Returns broker-address if namespace-bundle is already owned by any broker b. If current-broker receives
     * lookup-request and if it's not a leader then current broker redirects request to leader by returning
     * leader-service address. c. If current-broker is leader then it finds out least-loaded broker to own namespace
     * bundle and redirects request by returning least-loaded broker. d. If current-broker receives request to own the
     * namespace-bundle then it owns a bundle and returns success(connect) response to client.
     *
     * @param pulsarService
     * @param topicName
     * @param authoritative
     * @param clientAppId
     * @param requestId
     * @return
     */
    public static CompletableFuture<ByteBuf> lookupTopicAsync(PulsarService pulsarService, TopicName topicName,
            boolean authoritative, String clientAppId, AuthenticationDataSource authenticationData, long requestId) {

        final CompletableFuture<ByteBuf> validationFuture = new CompletableFuture<>();
        final CompletableFuture<ByteBuf> lookupfuture = new CompletableFuture<>();
        final String cluster = topicName.getCluster();

        // (1) validate cluster
        getClusterDataIfDifferentCluster(pulsarService, cluster, clientAppId).thenAccept(differentClusterData -> {

            if (differentClusterData != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Redirecting the lookup call to {}/{} cluster={}", clientAppId,
                            differentClusterData.getBrokerServiceUrl(), differentClusterData.getBrokerServiceUrlTls(),
                            cluster);
                }
                validationFuture.complete(newLookupResponse(differentClusterData.getBrokerServiceUrl(),
                        differentClusterData.getBrokerServiceUrlTls(), true, LookupType.Redirect, requestId, false));
            } else {
                // (2) authorize client
                try {
                    checkAuthorization(pulsarService, topicName, clientAppId, authenticationData);
                } catch (RestException authException) {
                    log.warn("Failed to authorized {} on cluster {}", clientAppId, topicName.toString());
                    validationFuture.complete(newLookupErrorResponse(ServerError.AuthorizationError,
                            authException.getMessage(), requestId));
                    return;
                } catch (Exception e) {
                    log.warn("Unknown error while authorizing {} on cluster {}", clientAppId, topicName.toString());
                    validationFuture.completeExceptionally(e);
                    return;
                }
                // (3) validate global namespace
                checkLocalOrGetPeerReplicationCluster(pulsarService, topicName.getNamespaceObject())
                        .thenAccept(peerClusterData -> {
                            if (peerClusterData == null) {
                                // (4) all validation passed: initiate lookup
                                validationFuture.complete(null);
                                return;
                            }
                            // if peer-cluster-data is present it means namespace is owned by that peer-cluster and
                            // request should be redirect to the peer-cluster
                            if (StringUtils.isBlank(peerClusterData.getBrokerServiceUrl())
                                    && StringUtils.isBlank(peerClusterData.getBrokerServiceUrl())) {
                                validationFuture.complete(newLookupErrorResponse(ServerError.MetadataError,
                                        "Redirected cluster's brokerService url is not configured", requestId));
                                return;
                            }
                            validationFuture.complete(newLookupResponse(peerClusterData.getBrokerServiceUrl(),
                                    peerClusterData.getBrokerServiceUrlTls(), true, LookupType.Redirect, requestId,
                                    false));

                        }).exceptionally(ex -> {
                            validationFuture.complete(
                                    newLookupErrorResponse(ServerError.MetadataError, ex.getMessage(), requestId));
                            return null;
                        });
            }
        }).exceptionally(ex -> {
            validationFuture.completeExceptionally(ex);
            return null;
        });

        // Initiate lookup once validation completes
        validationFuture.thenAccept(validaitonFailureResponse -> {
            if (validaitonFailureResponse != null) {
                lookupfuture.complete(validaitonFailureResponse);
            } else {
                pulsarService.getNamespaceService().getBrokerServiceUrlAsync(topicName, authoritative)
                        .thenAccept(lookupResult -> {

                            if (log.isDebugEnabled()) {
                                log.debug("[{}] Lookup result {}", topicName.toString(), lookupResult);
                            }

                            if (!lookupResult.isPresent()) {
                                lookupfuture.complete(newLookupErrorResponse(ServerError.ServiceNotReady,
                                        "No broker was available to own " + topicName, requestId));
                                return;
                            }

                            LookupData lookupData = lookupResult.get().getLookupData();
                            if (lookupResult.get().isRedirect()) {
                                boolean newAuthoritative = isLeaderBroker(pulsarService);
                                lookupfuture.complete(
                                        newLookupResponse(lookupData.getBrokerUrl(), lookupData.getBrokerUrlTls(),
                                                newAuthoritative, LookupType.Redirect, requestId, false));
                            } else {
                                // When running in standalone mode we want to redirect the client through the service
                                // url, so that the advertised address configuration is not relevant anymore.
                                boolean redirectThroughServiceUrl = pulsarService.getConfiguration()
                                        .isRunningStandalone();

                                lookupfuture.complete(newLookupResponse(lookupData.getBrokerUrl(),
                                        lookupData.getBrokerUrlTls(), true /* authoritative */, LookupType.Connect,
                                        requestId, redirectThroughServiceUrl));
                            }
                        }).exceptionally(ex -> {
                            if (ex instanceof CompletionException && ex.getCause() instanceof IllegalStateException) {
                                log.info("Failed to lookup {} for topic {} with error {}", clientAppId,
                                        topicName.toString(), ex.getCause().getMessage());
                            } else {
                                log.warn("Failed to lookup {} for topic {} with error {}", clientAppId,
                                        topicName.toString(), ex.getMessage(), ex);
                            }
                            lookupfuture.complete(
                                    newLookupErrorResponse(ServerError.ServiceNotReady, ex.getMessage(), requestId));
                            return null;
                        });
            }

        }).exceptionally(ex -> {
            if (ex instanceof CompletionException && ex.getCause() instanceof IllegalStateException) {
                log.info("Failed to lookup {} for topic {} with error {}", clientAppId, topicName.toString(),
                        ex.getCause().getMessage());
            } else {
                log.warn("Failed to lookup {} for topic {} with error {}", clientAppId, topicName.toString(),
                        ex.getMessage(), ex);
            }

            lookupfuture.complete(newLookupErrorResponse(ServerError.ServiceNotReady, ex.getMessage(), requestId));
            return null;
        });

        return lookupfuture;
    }

    private void completeLookupResponseExceptionally(AsyncResponse asyncResponse, Throwable t) {
        pulsar().getBrokerService().getLookupRequestSemaphore().release();
        asyncResponse.resume(t);
    }

    private void completeLookupResponseSuccessfully(AsyncResponse asyncResponse, LookupData lookupData) {
        pulsar().getBrokerService().getLookupRequestSemaphore().release();
        asyncResponse.resume(lookupData);
    }

    protected TopicName getTopicName(String topicDomain, String tenant, String cluster, String namespace,
            @Encoded String encodedTopic) {
        String decodedName = Codec.decode(encodedTopic);
        return TopicName.get(TopicDomain.getEnum(topicDomain).value(), tenant, cluster, namespace, decodedName);
    }

    protected TopicName getTopicName(String topicDomain, String tenant, String namespace,
            @Encoded String encodedTopic) {
        String decodedName = Codec.decode(encodedTopic);
        return TopicName.get(TopicDomain.getEnum(topicDomain).value(), tenant, namespace, decodedName);
    }

    private static final Logger log = LoggerFactory.getLogger(TopicLookupBase.class);
}
