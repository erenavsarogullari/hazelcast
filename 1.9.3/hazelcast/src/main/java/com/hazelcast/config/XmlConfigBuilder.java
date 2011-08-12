/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.config;

import com.hazelcast.impl.Util;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.logging.Level;

public class XmlConfigBuilder extends AbstractXmlConfigHelper implements ConfigBuilder {

    private final ILogger logger = Logger.getLogger(XmlConfigBuilder.class.getName());
    private boolean domLevel3 = true;
    private Config config;
    private InputStream in;
    private File configurationFile;
    private URL configurationUrl;
    boolean usingSystemConfig = false;

    public XmlConfigBuilder(String xmlFileName) throws FileNotFoundException {
        this(new FileInputStream(xmlFileName));
    }

    public XmlConfigBuilder(InputStream inputStream) {
        this.in = inputStream;
    }

    public XmlConfigBuilder() {
        String configFile = System.getProperty("hazelcast.config");
        try {
            if (configFile != null) {
                configurationFile = new File(configFile);
                logger.log(Level.INFO, "Using configuration file at " + configurationFile.getAbsolutePath());
                if (!configurationFile.exists()) {
                    String msg = "Config file at '" + configurationFile.getAbsolutePath() + "' doesn't exist.";
                    msg += "\nHazelcast will try to use the hazelcast.xml config file in the working directory.";
                    logger.log(Level.WARNING, msg);
                    configurationFile = null;
                }
            }
            if (configurationFile == null) {
                configFile = "hazelcast.xml";
                configurationFile = new File("hazelcast.xml");
                if (!configurationFile.exists()) {
                    configurationFile = null;
                }
            }
            if (configurationFile != null) {
                logger.log(Level.INFO, "Using configuration file at " + configurationFile.getAbsolutePath());
                try {
                    in = new FileInputStream(configurationFile);
                    configurationUrl = configurationFile.toURI().toURL();
                    usingSystemConfig = true;
                } catch (final Exception e) {
                    String msg = "Having problem reading config file at '" + configFile + "'.";
                    msg += "\nException message: " + e.getMessage();
                    msg += "\nHazelcast will try to use the hazelcast.xml config file in classpath.";
                    logger.log(Level.WARNING, msg);
                    in = null;
                }
            }
            if (in == null) {
                logger.log(Level.INFO, "Looking for hazelcast.xml config file in classpath.");
                configurationUrl = Config.class.getClassLoader().getResource("hazelcast.xml");
                if (configurationUrl == null) {
                    configurationUrl = Config.class.getClassLoader().getResource("hazelcast-default.xml");
                    logger.log(Level.WARNING, "Could not find hazelcast.xml in classpath.\nHazelcast will use hazelcast-default.xml config file in jar.");
                    if (configurationUrl == null) {
                        logger.log(Level.WARNING, "Could not find hazelcast-default.xml in the classpath!" +
                                "\nThis may be due to a wrong-packaged or corrupted jar file.");
                        return;
                    }
                }
                logger.log(Level.INFO, "Using configuration file " + configurationUrl.getFile() + " in the classpath.");
                in = configurationUrl.openStream();
                if (in == null) {
                    String msg = "Having problem reading config file hazelcast-default.xml in the classpath.";
                    msg += "\nHazelcast will start with default configuration.";
                    logger.log(Level.WARNING, msg);
                }
            }
        } catch (final Throwable e) {
            logger.log(Level.SEVERE, "Error while creating configuration:" + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    public Config build() {
        Config config = new Config();
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        return build(config);
    }

    public Config build(Config config) {
        return build(config, null);
    }

    public Config build(Element element) {
        Config config = new Config();
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        return build(config, element);
    }

    Config build(Config config, Element element) {
        try {
            parse(config, element);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        config.setConfigurationFile(configurationFile);
        config.setConfigurationUrl(configurationUrl);
        return config;
    }

    private void parse(final Config config, Element element) throws Exception {
        this.config = config;
        if (element == null) {
            final DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = null;
            try {
                doc = builder.parse(in);
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Util.streamXML(doc, baos);
                final byte[] bytes = baos.toByteArray();
                final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                config.setXmlConfig(Util.inputStreamToString(bais));
                if ("true".equals(System.getProperty("hazelcast.config.print"))) {
                    logger.log(Level.INFO, "Hazelcast config URL : " + config.getConfigurationUrl());
                    logger.log(Level.INFO, "=== Hazelcast config xml ===");
                    logger.log(Level.INFO, config.getXmlConfig());
                    logger.log(Level.INFO, "==============================");
                    logger.log(Level.INFO, "");
                }
            } catch (final Exception e) {
                String msgPart = "config file '" + config.getConfigurationFile() + "' set as a system property.";
                if (!usingSystemConfig) {
                    msgPart = "hazelcast-default.xml config file in the classpath.";
                }
                String msg = "Having problem parsing the " + msgPart;
                msg += "\nException: " + e.getMessage();
                msg += "\nHazelcast will start with default configuration.";
                logger.log(Level.WARNING, msg);
                return;
            }
            element = doc.getDocumentElement();
        }
        try {
            element.getTextContent();
        } catch (final Throwable e) {
            domLevel3 = false;
        }
        handleConfig(element);
    }

    private boolean checkTrue(final String value) {
        return "true".equalsIgnoreCase(value) ||
                "yes".equalsIgnoreCase(value) ||
                "on".equalsIgnoreCase(value);
    }

    private void handleConfig(final Element docElement) throws Exception {
        for (org.w3c.dom.Node node : new IterableNodeList(docElement.getChildNodes())) {
            final String nodeName = cleanNodeName(node.getNodeName());
            if ("network".equals(nodeName)) {
                handleNetwork(node);
            } else if ("group".equals(nodeName)) {
                handleGroup(node);
            } else if ("properties".equals(nodeName)) {
                handleProperties(node, config.getProperties());
            } else if ("executor-service".equals(nodeName)) {
                handleExecutor(node);
            } else if ("queue".equals(nodeName)) {
                handleQueue(node);
            } else if ("map".equals(nodeName)) {
                handleMap(node);
            } else if ("topic".equals(nodeName)) {
                handleTopic(node);
            } else if ("merge-policies".equals(nodeName)) {
                handleMergePolicies(node);
            }
        }
    }

    public void handleNetwork(final org.w3c.dom.Node node) throws Exception {
        for (org.w3c.dom.Node child : new IterableNodeList(node.getChildNodes())) {
            final String nodeName = cleanNodeName(child.getNodeName());
            if ("port".equals(nodeName)) {
                handlePort(child);
            } else if ("join".equals(nodeName)) {
                handleJoin(child);
            } else if ("interfaces".equals(nodeName)) {
                handleInterfaces(child);
            } else if ("symmetric-encryption".equals(nodeName)) {
                handleViaReflection(child, config.getNetworkConfig(), new SymmetricEncryptionConfig());
            } else if ("asymmetric-encryption".equals(nodeName)) {
                handleViaReflection(child, config.getNetworkConfig(), new AsymmetricEncryptionConfig());
            }
        }
    }

    private int getIntegerValue(final String parameterName, final String value,
                                final int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (final Exception e) {
            logger.log(Level.INFO, parameterName + " parameter value, [" + value
                    + "], is not a proper integer. Default value, [" + defaultValue
                    + "], will be used!");
            e.printStackTrace();
            return defaultValue;
        }
    }

    protected String getTextContent(final Node node) {
        if (domLevel3) {
            return node.getTextContent();
        } else {
            return getTextContent2(node);
        }
    }

    public void handleExecutor(final org.w3c.dom.Node node) throws Exception {
        final ExecutorConfig executorConfig = new ExecutorConfig();
        handleViaReflection(node, config, executorConfig);
    }

    public void handleGroup(final org.w3c.dom.Node node) {
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String value = getTextContent(n).trim();
            final String nodeName = cleanNodeName(n.getNodeName());
            if ("name".equals(nodeName)) {
                config.getGroupConfig().setName(value);
            } else if ("password".equals(nodeName)) {
                config.getGroupConfig().setPassword(value);
            }
        }
    }

    public void handleProperties(final org.w3c.dom.Node node, Properties properties) {
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String name = cleanNodeName(n.getNodeName());
            final String propertyName;
            if ("property".equals(name)) {
                propertyName = getTextContent(n.getAttributes().getNamedItem("name")).trim();
            } else {
                // old way - probably should be deprecated
                propertyName = name;
            }
            final String value = getTextContent(n).trim();
            properties.setProperty(propertyName, value);
        }
    }

    private void handleInterfaces(final org.w3c.dom.Node node) {
        final NamedNodeMap atts = node.getAttributes();
        final Interfaces interfaces = config.getNetworkConfig().getInterfaces();
        for (int a = 0; a < atts.getLength(); a++) {
            final org.w3c.dom.Node att = atts.item(a);
            if ("enabled".equals(att.getNodeName())) {
                final String value = att.getNodeValue();
                interfaces.setEnabled(checkTrue(value));
            }
        }
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            if ("interface".equalsIgnoreCase(cleanNodeName(n.getNodeName()))) {
                final String value = getTextContent(n).trim();
                interfaces.addInterface(value);
            }
        }
    }

    private void handleViaReflection(final org.w3c.dom.Node node, Object parent, Object target) throws Exception {
        final NamedNodeMap atts = node.getAttributes();
        if (atts != null) {
            for (int a = 0; a < atts.getLength(); a++) {
                final org.w3c.dom.Node att = atts.item(a);
                String methodName = "set" + getMethodName(att.getNodeName());
                Method method = getMethod(target, methodName);
                final String value = att.getNodeValue();
                invoke(target, method, value);
            }
        }
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String value = getTextContent(n).trim();
            String methodName = "set" + getMethodName(cleanNodeName(n.getNodeName()));
            Method method = getMethod(target, methodName);
            invoke(target, method, value);
        }
        String mName = "set" + target.getClass().getSimpleName();
        Method method = getMethod(parent, mName);
        if (method == null) {
            mName = "add" + target.getClass().getSimpleName();
            method = getMethod(parent, mName);
        }
        method.invoke(parent, new Object[]{target});
    }

    private void invoke(Object target, Method method, String value) {
        if (method == null) return;
        Class<?>[] args = method.getParameterTypes();
        if (args == null || args.length == 0) return;
        Class<?> arg = method.getParameterTypes()[0];
        try {
            if (arg == String.class) {
                method.invoke(target, new Object[]{value});
            } else if (arg == int.class) {
                method.invoke(target, new Object[]{Integer.parseInt(value)});
            } else if (arg == long.class) {
                method.invoke(target, new Object[]{Long.parseLong(value)});
            } else if (arg == boolean.class) {
                method.invoke(target, new Object[]{Boolean.parseBoolean(value)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Method getMethod(Object target, String methodName) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (method.getName().equalsIgnoreCase(methodName)) {
                return method;
            }
        }
        return null;
    }

    private String getMethodName(String element) {
        StringBuilder sb = new StringBuilder();
        char[] chars = element.toCharArray();
        boolean upper = true;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '_' || c == '-' || c == '.') {
                upper = true;
            } else {
                if (upper) {
                    sb.append(Character.toUpperCase(c));
                    upper = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void handleJoin(final org.w3c.dom.Node node) {
        for (org.w3c.dom.Node child : new IterableNodeList(node.getChildNodes())) {
            final String name = cleanNodeName(child.getNodeName());
            if ("multicast".equals(name)) {
                handleMulticast(child);
            } else if ("tcp-ip".equals(name)) {
                handleTcpIp(child);
            }
        }
    }

    private void handleMulticast(final org.w3c.dom.Node node) {
        final NamedNodeMap atts = node.getAttributes();
        final Join join = config.getNetworkConfig().getJoin();
        for (int a = 0; a < atts.getLength(); a++) {
            final org.w3c.dom.Node att = atts.item(a);
            final String value = getTextContent(att).trim();
            if ("enabled".equalsIgnoreCase(att.getNodeName())) {
                join.getMulticastConfig().setEnabled(checkTrue(value));
            }
        }
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String value = getTextContent(n).trim();
            if ("multicast-group".equals(cleanNodeName(n.getNodeName()))) {
                join.getMulticastConfig().setMulticastGroup(value);
            } else if ("multicast-port".equals(cleanNodeName(n.getNodeName()))) {
                join.getMulticastConfig().setMulticastPort(Integer.parseInt(value));
            } else if ("multicast-timeout-seconds".equals(cleanNodeName(n.getNodeName()))) {
                join.getMulticastConfig().setMulticastTimeoutSeconds(Integer.parseInt(value));
            }
        }
    }

    private void handlePort(final org.w3c.dom.Node node) {
        final String portStr = getTextContent(node).trim();
        if (portStr != null && portStr.length() > 0) {
            config.setPort(Integer.parseInt(portStr));
        }
        final NamedNodeMap atts = node.getAttributes();
        for (int a = 0; a < atts.getLength(); a++) {
            final org.w3c.dom.Node att = atts.item(a);
            final String value = getTextContent(att).trim();
            if (att.getNodeName().equals("auto-increment")) {
                config.setPortAutoIncrement(checkTrue(value));
            }
        }
    }

    public void handleQueue(final org.w3c.dom.Node node) {
        final Node attName = node.getAttributes().getNamedItem("name");
        final String name = getTextContent(attName);
        final QueueConfig qConfig = new QueueConfig();
        qConfig.setName(name);
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String nodeName = cleanNodeName(n.getNodeName());
            final String value = getTextContent(n).trim();
            if ("backing-map-ref".equals(nodeName)) {
                qConfig.setBackingMapRef(value);
            } else if ("max-size-per-jvm".equals(nodeName)) {
                qConfig.setMaxSizePerJVM(getIntegerValue("max-size-per-jvm", value, QueueConfig.DEFAULT_MAX_SIZE_PER_JVM));
            }
        }
        this.config.addQueueConfig(qConfig);
    }

    public void handleMap(final org.w3c.dom.Node node) throws Exception {
        final Node attName = node.getAttributes().getNamedItem("name");
        final String name = getTextContent(attName);
        final MapConfig mapConfig = new MapConfig();
        mapConfig.setName(name);
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String nodeName = cleanNodeName(n.getNodeName());
            final String value = getTextContent(n).trim();
            if ("backup-count".equals(nodeName)) {
                mapConfig.setBackupCount(getIntegerValue("backup-count", value, MapConfig.DEFAULT_BACKUP_COUNT));
            } else if ("eviction-policy".equals(nodeName)) {
                mapConfig.setEvictionPolicy(value);
            } else if ("max-size".equals(nodeName)) {
                final MaxSizeConfig msc = mapConfig.getMaxSizeConfig();
                final Node maxSizePolicy = n.getAttributes().getNamedItem("policy");
                if (maxSizePolicy != null) {
                    msc.setMaxSizePolicy(getTextContent(maxSizePolicy));
                }
                int size = 0;
                if (value.length() < 2) {
                    size = Integer.parseInt(value);
                } else {
                    char last = value.charAt(value.length() - 1);
                    int type = 0;
                    if (last == 'g' || last == 'G') {
                        type = 1;
                    } else if (last == 'm' || last == 'M') {
                        type = 2;
                    }
                    if (type == 0) {
                        size = Integer.parseInt(value);
                    } else if (type == 1) {
                        size = Integer.parseInt(value.substring(0, value.length() - 1)) * 1000;
                    } else {
                        size = Integer.parseInt(value.substring(0, value.length() - 1));
                    }
                }
                msc.setSize(size);
            } else if ("eviction-percentage".equals(nodeName)) {
                mapConfig.setEvictionPercentage(getIntegerValue("eviction-percentage", value,
                        MapConfig.DEFAULT_EVICTION_PERCENTAGE));
            } else if ("eviction-delay-seconds".equals(nodeName)) {
                mapConfig.setEvictionDelaySeconds(getIntegerValue("eviction-delay-seconds", value,
                        MapConfig.DEFAULT_EVICTION_DELAY_SECONDS));
            } else if ("time-to-live-seconds".equals(nodeName)) {
                mapConfig.setTimeToLiveSeconds(getIntegerValue("time-to-live-seconds", value,
                        MapConfig.DEFAULT_TTL_SECONDS));
            } else if ("max-idle-seconds".equals(nodeName)) {
                mapConfig.setMaxIdleSeconds(getIntegerValue("max-idle-seconds", value,
                        MapConfig.DEFAULT_MAX_IDLE_SECONDS));
            } else if ("map-store".equals(nodeName)) {
                MapStoreConfig mapStoreConfig = createMapStoreConfig(n);
                mapConfig.setMapStoreConfig(mapStoreConfig);
            } else if ("near-cache".equals(nodeName)) {
                handleViaReflection(n, mapConfig, new NearCacheConfig());
            } else if ("merge-policy".equals(nodeName)) {
                mapConfig.setMergePolicy(value);
            } else if ("cache-value".equals(nodeName)) {
                mapConfig.setCacheValue(checkTrue(value));
            } else if ("read-backup-data".equals(nodeName)) {
                mapConfig.setReadBackupData(checkTrue(value));
            }
        }
        this.config.addMapConfig(mapConfig);
    }

    private MapStoreConfig createMapStoreConfig(final org.w3c.dom.Node node) {
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        final NamedNodeMap atts = node.getAttributes();
        for (int a = 0; a < atts.getLength(); a++) {
            final org.w3c.dom.Node att = atts.item(a);
            final String value = getTextContent(att).trim();
            if (att.getNodeName().equals("enabled")) {
                mapStoreConfig.setEnabled(checkTrue(value));
            }
        }
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String nodeName = cleanNodeName(n.getNodeName());
            final String value = getTextContent(n).trim();
            if ("class-name".equals(nodeName)) {
                mapStoreConfig.setClassName(value);
            } else if ("write-delay-seconds".equals(nodeName)) {
                mapStoreConfig.setWriteDelaySeconds(getIntegerValue("write-delay-seconds", value, MapStoreConfig.DEFAULT_WRITE_DELAY_SECONDS));
            }
        }
        handleProperties(node, mapStoreConfig.getProperties());
        return mapStoreConfig;
    }

    private void handleTcpIp(final org.w3c.dom.Node node) {
        final NamedNodeMap atts = node.getAttributes();
        final Join join = config.getNetworkConfig().getJoin();
        for (int a = 0; a < atts.getLength(); a++) {
            final org.w3c.dom.Node att = atts.item(a);
            final String value = getTextContent(att).trim();
            if (att.getNodeName().equals("enabled")) {
                join.getTcpIpConfig().setEnabled(checkTrue(value));
            } else if (att.getNodeName().equals("conn-timeout-seconds")) {
                join.getTcpIpConfig().setConnectionTimeoutSeconds(getIntegerValue("conn-timeout-seconds", value, 5));
            }
        }
        final NodeList nodelist = node.getChildNodes();
        for (int i = 0; i < nodelist.getLength(); i++) {
            final org.w3c.dom.Node n = nodelist.item(i);
            final String value = getTextContent(n).trim();
            if (cleanNodeName(n.getNodeName()).equals("required-member")) {
                join.getTcpIpConfig().setRequiredMember(value);
            } else if (cleanNodeName(n.getNodeName()).equals("hostname")) {
                join.getTcpIpConfig().addMember(value);
            } else if (cleanNodeName(n.getNodeName()).equals("address")) {
                int colonIndex = value.indexOf(':');
                if (colonIndex == -1) {
                    logger.log(Level.WARNING, "Address should be in the form of ip:port. Address [" + value + "] is not valid.");
                } else {
                    String hostStr = value.substring(0, colonIndex);
                    String portStr = value.substring(colonIndex + 1);
                    try {
                        join.getTcpIpConfig().addAddress(new Address(hostStr, Integer.parseInt(portStr), true));
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                }
            } else if ("interface".equals(cleanNodeName(n.getNodeName()))) {
                join.getTcpIpConfig().addMember(value);
            } else if ("member".equals(cleanNodeName(n.getNodeName()))) {
                join.getTcpIpConfig().addMember(value);
            } else if ("members".equals(cleanNodeName(n.getNodeName()))) {
                join.getTcpIpConfig().addMember(value);
            }
        }
    }

    public void handleTopic(final org.w3c.dom.Node node) {
        final Node attName = node.getAttributes().getNamedItem("name");
        final String name = getTextContent(attName);
        final TopicConfig tConfig = new TopicConfig();
        tConfig.setName(name);
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String value = getTextContent(n).trim();
            if (cleanNodeName(n.getNodeName()).equals("global-ordering-enabled")) {
                tConfig.setGlobalOrderingEnabled(checkTrue(value));
            }
        }
        config.addTopicConfig(tConfig);
    }

    public void handleMergePolicies(final org.w3c.dom.Node node) throws Exception {
        for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes())) {
            final String nodeName = cleanNodeName(n.getNodeName());
            if (nodeName.equals("map-merge-policy")) {
                handleViaReflection(n, config, new MergePolicyConfig());
            }
        }
    }
}