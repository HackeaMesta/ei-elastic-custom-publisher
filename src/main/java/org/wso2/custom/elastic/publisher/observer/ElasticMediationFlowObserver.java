/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.custom.elastic.publisher.observer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import org.apache.synapse.aspects.flow.statistics.publishing.PublishingFlow;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.das.messageflow.data.publisher.observer.MessageFlowObserver;

import org.wso2.custom.elastic.publisher.publish.ElasticStatisticsPublisher;
import org.wso2.custom.elastic.publisher.services.PublisherThread;
import org.wso2.custom.elastic.publisher.util.ElasticObserverConstants;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class ElasticMediationFlowObserver implements MessageFlowObserver {

    private static final Log log = LogFactory.getLog(ElasticMediationFlowObserver.class);

    // Defines elasticsearch Transport Client as client
    private TransportClient client = null;

    // Thread to publish jsons to Elasticsearch
    PublisherThread publisherThread;

    int queueSize;
    Exception exp = null;


    /**
     * Instantiates the TransportClient as this class is instantiates
     */
    public ElasticMediationFlowObserver() {

        ServerConfiguration serverConf = ServerConfiguration.getInstance();

        String clusterName = serverConf.getFirstProperty(ElasticObserverConstants.OBSERVER_CLUSTER_NAME);
        String host = serverConf.getFirstProperty(ElasticObserverConstants.OBSERVER_HOST);
        String portString = serverConf.getFirstProperty(ElasticObserverConstants.OBSERVER_PORT);
        String queueSizeString = serverConf.getFirstProperty(ElasticObserverConstants.QUEUE_SIZE);


        // Elasticsearch settings object
        Settings settings = Settings.builder().put("cluster.name", clusterName).build();

        client = new PreBuiltTransportClient(settings);

        try {

            int port = Integer.parseInt(portString);
            queueSize = Integer.parseInt(queueSizeString);

            client.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port));

        } catch (UnknownHostException e) {

            exp = e;
            log.error("Unknown Elasticsearch Host");

        } catch (NumberFormatException e) {

            exp = e;
            log.error("Invalid port number");

        } finally {

            // Only if there is no exception, publisher thread is started
            if (exp == null) {
                startPublishing();
            }

        }

    }

    /**
     * TransportClient gets closed
     */
    @Override
    public void destroy() {

        publisherThread.shutdown();

        if (client != null) {
            client.close();
        }

        if (log.isDebugEnabled()) {
            log.debug("Shutting down the mediation statistics observer of Elasticsearch");
        }

    }

    /**
     * Method is called when this observer is notified.
     * Invokes the process method for the publishing flow considering whether there are any nodes connected.
     *
     * @param publishingFlow PublishingFlow object is passed when notified.
     */
    @Override
    public void updateStatistics(PublishingFlow publishingFlow) {

        if (exp == null) {
            try {

                // Statistics should only be processed if the publishing thread is alive and no shut down requested
                if (publisherThread.isAlive() && !(publisherThread.getShutdown())) {

                    // If no connected nodes queue size will be limited
                    if (client.connectedNodes().isEmpty()) {

                        if (ElasticStatisticsPublisher.getAllMappingsQueue().size() < queueSize) {
                            ElasticStatisticsPublisher.process(publishingFlow);
                            log.info(ElasticStatisticsPublisher.getAllMappingsQueue().toString());
                        }

                    } else {

                        ElasticStatisticsPublisher.process(publishingFlow);

                    }

                }

            } catch (Exception e) {

                log.error("Failed to update statics from Elasticsearch publisher", e);

            }

        }
    }


    private void startPublishing() {
        publisherThread = new PublisherThread();
        publisherThread.setClient(client);
        publisherThread.start();
    }

}