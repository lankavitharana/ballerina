/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.ballerinalang.net.http;

import org.apache.commons.lang3.StringUtils;
import org.ballerinalang.connector.api.BallerinaConnectorException;
import org.ballerinalang.logging.BLogManager;
import org.ballerinalang.model.values.BConnector;
import org.ballerinalang.model.values.BStruct;
import org.ballerinalang.net.http.util.ConnectorStartupSynchronizer;
import org.ballerinalang.net.ws.BallerinaWsServerConnectorListener;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.carbon.transport.http.netty.config.ConfigurationBuilder;
import org.wso2.carbon.transport.http.netty.config.ListenerConfiguration;
import org.wso2.carbon.transport.http.netty.config.Parameter;
import org.wso2.carbon.transport.http.netty.config.SenderConfiguration;
import org.wso2.carbon.transport.http.netty.config.TransportsConfiguration;
import org.wso2.carbon.transport.http.netty.contract.HttpClientConnector;
import org.wso2.carbon.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.carbon.transport.http.netty.contract.ServerConnector;
import org.wso2.carbon.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.carbon.transport.http.netty.contract.websocket.WebSocketClientConnector;
import org.wso2.carbon.transport.http.netty.contract.websocket.WsClientConnectorConfig;
import org.wso2.carbon.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.carbon.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.carbon.transport.http.netty.message.HTTPConnectorUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * {@code HttpConnectionManager} is responsible for managing all the server connectors with ballerina runtime.
 *
 * @since 0.94
 */
public class HttpConnectionManager {

    private static HttpConnectionManager instance = new HttpConnectionManager();
    private Map<String, org.wso2.carbon.transport.http.netty.contract.ServerConnector>
            startupDelayedHTTPServerConnectors = new HashMap<>();

    private Map<String, org.wso2.carbon.transport.http.netty.contract.ServerConnector>
            startedHTTPServerConnectors = new HashMap<>();
    private Map<String, HttpServerConnectorContext>
            serverConnectorPool = new HashMap<>();
    private ServerBootstrapConfiguration serverBootstrapConfiguration;
    private TransportsConfiguration trpConfig;
    private HttpWsConnectorFactory httpConnectorFactory = new HttpWsConnectorFactoryImpl();
    private static final String HTTP_TRANSPORT_CONF = "transports.netty.conf";
    private static final String CIPHERS = "ciphers";
    private static final String SSL_ENABLED_PROTOCOLS = "sslEnabledProtocols";
    private static final int OPTIONS_STRUCT_INDEX = 0;
    private static final int SSL_STRUCT_INDEX = 1;
    private static final int FOLLOW_REDIRECT_STRUCT_INDEX = 0;
    private static final int FOLLOW_REDIRECT_INDEX = 0;
    private static final int MAX_REDIRECT_COUNT = 0;
    private static final int TRUST_STORE_FILE_INDEX = 0;
    private static final int TRUST_STORE_PASSWORD_INDEX = 1;
    private static final int KEY_STORE_FILE_INDEX = 2;
    private static final int KEY_STORE_PASSWORD_INDEX = 3;
    private static final int SSL_ENABLED_PROTOCOLS_INDEX = 4;
    private static final int CIPHERS_INDEX = 5;
    private static final int SSL_PROTOCOL_INDEX = 6;

    private HttpConnectionManager() {
        String nettyConfigFile = System.getProperty(HTTP_TRANSPORT_CONF,
                "conf" + File.separator + "transports" +
                        File.separator + "netty-transports.yml");
        trpConfig = ConfigurationBuilder.getInstance().getConfiguration(nettyConfigFile);
        serverBootstrapConfiguration = HTTPConnectorUtil
                .getServerBootstrapConfiguration(trpConfig.getTransportProperties());

        if (System.getProperty(BLogManager.HTTP_TRACE_LOGGER) != null) {
            try {
                ((BLogManager) BLogManager.getLogManager()).setHttpTraceLogHandler();
            } catch (IOException e) {
                throw new BallerinaConnectorException("Error in configuring HTTP trace log");
            }
        }
    }

    public static HttpConnectionManager getInstance() {
        return instance;
    }

    public Set<ListenerConfiguration> getDefaultListenerConfiugrationSet() {
        Set<ListenerConfiguration> listenerConfigurationSet = new HashSet<>();
        for (ListenerConfiguration listenerConfiguration : trpConfig.getListenerConfigurations()) {
            listenerConfiguration.setId(listenerConfiguration.getHost() == null ?
                    "0.0.0.0" : listenerConfiguration.getHost() + ":" + listenerConfiguration.getPort());
            listenerConfigurationSet.add(listenerConfiguration);
        }
        return listenerConfigurationSet;
    }

    public org.wso2.carbon.transport.http.netty.contract.ServerConnector createHttpServerConnector(
            ListenerConfiguration listenerConfig) {
        String listenerInterface = listenerConfig.getHost() + ":" + listenerConfig.getPort();
        HttpServerConnectorContext httpServerConnectorContext =
                serverConnectorPool.get(listenerInterface);
        if (httpServerConnectorContext != null) {
            if (checkForConflicts(listenerConfig, httpServerConnectorContext)) {
                throw new BallerinaConnectorException("Conflicting configuration detected for listener " +
                        "configuration id " + listenerConfig.getId());
            }
            httpServerConnectorContext.incrementReferenceCount();
            return httpServerConnectorContext.getServerConnector();
        }

        if (System.getProperty(BLogManager.HTTP_TRACE_LOGGER) != null) {
            listenerConfig.setHttpTraceLogEnabled(true);
        }

        serverBootstrapConfiguration = HTTPConnectorUtil
                .getServerBootstrapConfiguration(trpConfig.getTransportProperties());
        org.wso2.carbon.transport.http.netty.contract.ServerConnector serverConnector =
                httpConnectorFactory.createServerConnector(serverBootstrapConfiguration, listenerConfig);

        httpServerConnectorContext = new HttpServerConnectorContext(serverConnector, listenerConfig);
        serverConnectorPool.put(serverConnector.getConnectorID(), httpServerConnectorContext);
        httpServerConnectorContext.incrementReferenceCount();
        addStartupDelayedHTTPServerConnector(listenerInterface, serverConnector);
        return serverConnector;
    }

    /**
     * Add a HTTP ServerConnector which startup is delayed at the service deployment time.
     *
     * @param serverConnector ServerConnector
     */
    public void addStartupDelayedHTTPServerConnector(String id,
            org.wso2.carbon.transport.http.netty.contract.ServerConnector serverConnector) {
        startupDelayedHTTPServerConnectors.put(id, serverConnector);
    }

    /**
     * Start all the ServerConnectors which startup is delayed at the service deployment time.
     *
     * @throws ServerConnectorException if exception occurs while starting at least one connector.
     */
    public void startPendingHTTPConnectors() throws ServerConnectorException {
        ConnectorStartupSynchronizer startupSyncer =
                new ConnectorStartupSynchronizer(new CountDownLatch(startupDelayedHTTPServerConnectors.size()));

        for (Map.Entry<String, ServerConnector> serverConnectorEntry : startupDelayedHTTPServerConnectors.entrySet()) {
            ServerConnector serverConnector = serverConnectorEntry.getValue();
            ServerConnectorFuture connectorFuture = serverConnector.start();
            setConnectorListeners(connectorFuture, serverConnector.getConnectorID(), startupSyncer);
            startedHTTPServerConnectors.put(serverConnector.getConnectorID(), serverConnector);
        }

        try {
            // Wait for all the connectors to start
            startupSyncer.getCountDownLatch().await();
        } catch (InterruptedException e) {
            throw new BallerinaConnectorException("Error in starting HTTP server connector(s)");
        }

        validateConnectorStartup(startupSyncer);
        startupDelayedHTTPServerConnectors.clear();
    }

    public HttpClientConnector getHTTPHttpClientConnector(String scheme, BConnector bConnector) {
        Map<String, Object> properties = HTTPConnectorUtil.getTransportProperties(trpConfig);
        SenderConfiguration senderConfiguration =
                HTTPConnectorUtil.getSenderConfiguration(trpConfig, scheme);

        if (System.getProperty(BLogManager.HTTP_TRACE_LOGGER) != null) {
            senderConfiguration.setHttpTraceLogEnabled(true);
        }

        BStruct options = (BStruct) bConnector.getRefField(OPTIONS_STRUCT_INDEX);
        BStruct ssl = (BStruct) options.getRefField(SSL_STRUCT_INDEX);
        BStruct followRedirects = (BStruct) options.getRefField(FOLLOW_REDIRECT_STRUCT_INDEX);
        int followRedirect = followRedirects.getBooleanField(FOLLOW_REDIRECT_INDEX);
        Long maxRedirectCount = followRedirects.getIntField(MAX_REDIRECT_COUNT);
        String trustStoreFile = ssl.getStringField(TRUST_STORE_FILE_INDEX);
        String trustStorePassword = ssl.getStringField(TRUST_STORE_PASSWORD_INDEX);
        String keyStoreFile = ssl.getStringField(KEY_STORE_FILE_INDEX);
        String keyStorePassword = ssl.getStringField(KEY_STORE_PASSWORD_INDEX);
        String sslEnabledProtocols = ssl.getStringField(SSL_ENABLED_PROTOCOLS_INDEX);
        String ciphers = ssl.getStringField(CIPHERS_INDEX);
        String sslProtocol = ssl.getStringField(SSL_PROTOCOL_INDEX);

        senderConfiguration.setFollowRedirect(followRedirect == 1 ? true : false);
        senderConfiguration.setMaxRedirectCount(maxRedirectCount.intValue());
        if (StringUtils.isNotBlank(trustStoreFile)) {
            senderConfiguration.setTrustStoreFile(trustStoreFile);
        }
        if (StringUtils.isNotBlank(trustStorePassword)) {
            senderConfiguration.setTrustStorePass(trustStorePassword);
        }
        if (StringUtils.isNotBlank(keyStoreFile)) {
            senderConfiguration.setKeyStoreFile(keyStoreFile);
        }
        if (StringUtils.isNotBlank(keyStorePassword)) {
            senderConfiguration.setKeyStorePassword(keyStorePassword);
        }
        List<Parameter> clientParams = new ArrayList<>();
        if (StringUtils.isNotBlank(sslEnabledProtocols)) {
            Parameter clientProtocols = new Parameter(SSL_ENABLED_PROTOCOLS, sslEnabledProtocols);
            clientParams.add(clientProtocols);
        }
        if (StringUtils.isNotBlank(ciphers)) {
            Parameter clientCiphers = new Parameter(CIPHERS, ciphers);
            clientParams.add(clientCiphers);
        }
        if (StringUtils.isNotBlank(sslProtocol)) {
            senderConfiguration.setSslProtocol(sslProtocol);
        }
        if (!clientParams.isEmpty()) {
            senderConfiguration.setParameters(clientParams);
        }
        return httpConnectorFactory.createHttpClientConnector(properties, senderConfiguration);
    }

    private static class HttpServerConnectorContext {
        private org.wso2.carbon.transport.http.netty.contract.ServerConnector serverConnector;
        private ListenerConfiguration listenerConfiguration;
        private int referenceCount = 0;

        public HttpServerConnectorContext(org.wso2.carbon.transport.http.netty.contract.ServerConnector
                serverConnector, ListenerConfiguration listenerConfiguration) {
            this.serverConnector = serverConnector;
            this.listenerConfiguration = listenerConfiguration;
        }

        public void incrementReferenceCount() {
            this.referenceCount++;
        }

        public void decrementReferenceCount() {
            this.referenceCount--;
        }

        public org.wso2.carbon.transport.http.netty.contract.ServerConnector getServerConnector() {
            return this.serverConnector;
        }

        public ListenerConfiguration getListenerConfiguration() {
            return this.listenerConfiguration;
        }

        public int getReferenceCount() {
            return this.referenceCount;
        }
    }

    private boolean checkForConflicts(ListenerConfiguration listenerConfiguration,
            HttpServerConnectorContext context) {
        if (context == null) {
            return false;
        }
        if (listenerConfiguration.getScheme().equalsIgnoreCase("https")) {
            ListenerConfiguration config = context.getListenerConfiguration();
            if (!listenerConfiguration.getKeyStoreFile().equals(config.getKeyStoreFile())
                    || !listenerConfiguration.getKeyStorePass().equals(config.getKeyStorePass())
                    || !listenerConfiguration.getCertPass().equals(config.getCertPass())) {
                return true;
            }
        }
        return false;
    }

    public boolean closeIfLast(String connectorId) {
        HttpServerConnectorContext context = serverConnectorPool.get(connectorId);
        if (context.getReferenceCount() == 1) {
            return context.getServerConnector().stop();
        }
        context.decrementReferenceCount();
        return false;
    }

    public WebSocketClientConnector getWebSocketClientConnector(WsClientConnectorConfig configuration) {
        return  httpConnectorFactory.createWsClientConnector(configuration);
    }

    private void setConnectorListeners(ServerConnectorFuture connectorFuture, String serverConnectorId,
                                       ConnectorStartupSynchronizer startupSyncer) {
        connectorFuture.setHttpConnectorListener(new BallerinaHTTPConnectorListener());
        connectorFuture.setWSConnectorListener(new BallerinaWsServerConnectorListener());
        connectorFuture.setPortBindingEventListener(
                new HttpConnectorPortBindingListener(startupSyncer, serverConnectorId));
    }

    private void validateConnectorStartup(ConnectorStartupSynchronizer startupSyncer) {
        int noOfExceptions = startupSyncer.getExceptions().size();
        if (noOfExceptions <= 0) {
            return;
        }
        PrintStream console = System.err;
        String errMsg = "following host/port configurations are already in use: " +
                startupSyncer.getExceptions().keySet();

        if (noOfExceptions == startupDelayedHTTPServerConnectors.size()) {
            // If the no. of exceptions is equal to the no. of connectors to be started, then none of the
            // connectors have started properly and we can terminate the runtime
            throw new BallerinaConnectorException(errMsg);
        }
        console.println("ballerina: " + errMsg);
    }
}
