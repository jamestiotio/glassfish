/*
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.security.appclient;

import com.sun.enterprise.security.common.Util;
import com.sun.enterprise.security.jmac.AuthMessagePolicy;
import com.sun.enterprise.security.jmac.config.ConfigParser;
import com.sun.enterprise.security.jmac.config.GFServerConfigProvider;
import com.sun.enterprise.security.jmac.config.GFServerConfigProvider.InterceptEntry;

import jakarta.security.auth.message.MessagePolicy;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.glassfish.appclient.client.acc.config.ClientContainer;
import org.glassfish.appclient.client.acc.config.MessageSecurityConfig;
import org.glassfish.appclient.client.acc.config.Property;
import org.glassfish.appclient.client.acc.config.ProviderConfig;
import org.glassfish.appclient.client.acc.config.RequestPolicy;
import org.glassfish.appclient.client.acc.config.ResponsePolicy;
import org.glassfish.internal.api.Globals;

import static java.util.regex.Matcher.quoteReplacement;

/**
 * Parser for message-security-config in glassfish-acc.xml
 */
public class ConfigXMLParser implements ConfigParser {

    private static final Logger LOG = System.getLogger(ConfigXMLParser.class.getName());

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{\\{(.*?)}}|\\$\\{(.*?)}");

    // configuration info
    private final Map<String, InterceptEntry> configMap = new HashMap<>();
    private final Set<String> layersWithDefault = new HashSet<>();
    private List<MessageSecurityConfig> msgSecConfigs;
    private static final String ACC_XML = "glassfish-acc.xml.url";

    public ConfigXMLParser() throws IOException {
    }

    public void initialize(List<MessageSecurityConfig> msgConfigs) throws IOException {
        this.msgSecConfigs = msgConfigs;
        if (this.msgSecConfigs != null) {
            processClientConfigContext(configMap);
        }
    }

    private void processClientConfigContext(Map<String, InterceptEntry> newConfig) throws IOException {
        // auth-layer
        String intercept = null;

        List<MessageSecurityConfig> msgConfigs = this.msgSecConfigs;
        assert (msgConfigs != null);
        for (MessageSecurityConfig config : msgConfigs) {
            intercept = parseInterceptEntry(config, newConfig);
            List<ProviderConfig> pConfigs = config.getProviderConfig();
            for (ProviderConfig pConfig : pConfigs) {
                parseIDEntry(pConfig, newConfig, intercept);
            }
        }

    }

    @Override
    public Map<String, InterceptEntry> getConfigMap() {
        return configMap;
    }

    @Override
    public Set<String> getLayersWithDefault() {
        return layersWithDefault;
    }


    private String parseInterceptEntry(MessageSecurityConfig msgConfig, Map<String, InterceptEntry> newConfig)
        throws IOException {
        String intercept = null;
        String defaultServerID = null;
        String defaultClientID = null;
        MessageSecurityConfig clientMsgSecConfig = msgConfig;
        intercept = clientMsgSecConfig.getAuthLayer();
        defaultServerID = clientMsgSecConfig.getDefaultProvider();
        defaultClientID = clientMsgSecConfig.getDefaultClientProvider();

        LOG.log(Level.DEBUG, "Intercept Entry:\n intercept: {0}\n defaultServerID: {1}\n defaultClientID: {2}",
            intercept, defaultServerID, defaultClientID);

        if (defaultServerID != null || defaultClientID != null) {
            layersWithDefault.add(intercept);
        }

        GFServerConfigProvider.InterceptEntry intEntry = newConfig.get(intercept);
        if (intEntry != null) {
            throw new IOException("found multiple MessageSecurityConfig entries with the same auth-layer");
        }

        // create new intercept entry
        intEntry = new GFServerConfigProvider.InterceptEntry(defaultClientID, defaultServerID, null);
        newConfig.put(intercept, intEntry);
        return intercept;
    }

    // duplicate implementation for clientbeans config
    private void parseIDEntry(ProviderConfig pConfig, Map<String, InterceptEntry> newConfig, String intercept)
        throws IOException {
        String id = pConfig.getProviderId();
        String type = pConfig.getProviderType();
        String moduleClass = pConfig.getClassName();
        MessagePolicy requestPolicy = parsePolicy(pConfig.getRequestPolicy());
        MessagePolicy responsePolicy = parsePolicy(pConfig.getResponsePolicy());

        // get the module options

        Map<String, String> options = new HashMap<>();
        List<Property> props = pConfig.getProperty();
        for (Property prop : props) {
            try {
                options.put(prop.getName(), expand(prop.getValue()));
            } catch (IllegalStateException ee) {
                // log warning and give the provider a chance to
                // interpret value itself.
                LOG.log(Level.WARNING,
                    "SEC1200: Unable to expand provider property value, unexpanded value passed to provider.");
                options.put(prop.getName(), prop.getValue());
            }
        }

        LOG.log(Level.DEBUG,
            "ID Entry: \n module class: {0}\n id: {1}\n type: {2}\n"
                + " request policy: {3}\n response policy: {4}\n options: {5}",
            moduleClass, id, type, requestPolicy, responsePolicy, options);

        // create ID entry

        GFServerConfigProvider.IDEntry idEntry = new GFServerConfigProvider.IDEntry(type, moduleClass, requestPolicy,
            responsePolicy, options);

        GFServerConfigProvider.InterceptEntry intEntry = newConfig.get(intercept);
        if (intEntry == null) {
            throw new IOException("intercept entry for " + intercept + " must be specified before ID entries");
        }

        if (intEntry.getIdMap() == null) {
            intEntry.setIdMap(new HashMap<>());
        }

        // map id to Intercept
        intEntry.getIdMap().put(id, idEntry);
    }

    private String expand(String rawProperty) {
        Matcher propertyMatcher = PROPERTY_PATTERN.matcher(rawProperty);
        StringBuilder propertyBuilder = new StringBuilder();
        while (propertyMatcher.find()) {
            // Check if the ignore pattern matched
            if (propertyMatcher.group(1) != null) {
                // Ignore ${{...}} matched, so just append everything
                propertyMatcher.appendReplacement(propertyBuilder, quoteReplacement(propertyMatcher.group()));
            } else {

                String replacement = System.getProperty(propertyMatcher.group(2));
                if (replacement == null) {
                    throw new IllegalStateException("No system property for " + propertyMatcher.group(2));
                }

                // The replacement pattern matched
                propertyMatcher.appendReplacement(propertyBuilder, quoteReplacement(replacement));
            }
        }
        propertyMatcher.appendTail(propertyBuilder);

        return propertyBuilder.toString();
    }

    private MessagePolicy parsePolicy(Object policy) {
        if (policy == null) {
            return null;
        }

        String authSource = null;
        String authRecipient = null;

        if (policy instanceof RequestPolicy) {
            RequestPolicy clientRequestPolicy = (RequestPolicy) policy;
            authSource = clientRequestPolicy.getAuthSource();
            authRecipient = clientRequestPolicy.getAuthRecipient();
        } else if (policy instanceof ResponsePolicy) {
            ResponsePolicy clientResponsePolicy = (ResponsePolicy) policy;
            authSource = clientResponsePolicy.getAuthSource();
            authRecipient = clientResponsePolicy.getAuthRecipient();
        }

        return AuthMessagePolicy.getMessagePolicy(authSource, authRecipient);
    }

    @Override
    public void initialize(Object config) throws IOException {
        String sun_acc = System.getProperty(ACC_XML, "glassfish-acc.xml");
        List<MessageSecurityConfig> msgconfigs = null;
        if (Globals.getDefaultHabitat() == null && sun_acc != null && new File(sun_acc).exists()) {
            try (InputStream is = new FileInputStream(sun_acc)) {
                JAXBContext jc = JAXBContext.newInstance(ClientContainer.class);
                Unmarshaller u = jc.createUnmarshaller();
                ClientContainer cc = (ClientContainer) u.unmarshal(is);
                msgconfigs = cc.getMessageSecurityConfig();
            } catch (JAXBException ex) {
                LOG.log(Level.ERROR, "Failed to parse glassfish-acc.xml", ex);
            }
        } else {
            Util util = Util.getInstance();
            assert (util != null);
            msgconfigs = (List<MessageSecurityConfig>) util.getAppClientMsgSecConfigs();
        }

        this.initialize(msgconfigs);
    }
}
