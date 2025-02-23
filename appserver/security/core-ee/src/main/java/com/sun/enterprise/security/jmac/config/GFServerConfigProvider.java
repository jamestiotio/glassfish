/*
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.security.jmac.config;

import com.sun.enterprise.deployment.runtime.common.MessageSecurityBindingDescriptor;
import com.sun.enterprise.deployment.runtime.web.SunWebApp;

import com.sun.enterprise.security.jauth.AuthParam;
import com.sun.enterprise.security.jauth.AuthPolicy;
import com.sun.enterprise.security.jauth.FailureException;
import com.sun.enterprise.security.jauth.HttpServletAuthParam;
import com.sun.enterprise.security.jauth.PendingException;
import com.sun.enterprise.security.jmac.AuthMessagePolicy;
import com.sun.enterprise.security.jmac.WebServicesDelegate;
import com.sun.logging.LogDomains;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.config.AuthConfig;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ClientAuthContext;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ClientAuthModule;
import jakarta.security.auth.message.module.ServerAuthModule;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import org.glassfish.internal.api.Globals;

/**
 * This class implements the interface AuthConfigProvider.
 *
 * @author Shing Wai Chan
 * @author Ronald Monzillo
 */
public class GFServerConfigProvider implements AuthConfigProvider {

    public static final String SOAP = "SOAP";
    public static final String HTTPSERVLET = "HttpServlet";

    protected static final String CLIENT = "client";
    protected static final String SERVER = "server";
    protected static final String MANAGES_SESSIONS_OPTION = "managessessions";

    private static final Logger LOG = LogDomains.getLogger(GFServerConfigProvider.class, LogDomains.SECURITY_LOGGER, false);
    private static final String DEFAULT_PARSER_CLASS = "com.sun.enterprise.security.jmac.config.ConfigDomainParser";

    // since old api does not have subject in PasswordValdiationCallback,
    // this is for old modules to pass group info back to subject
    private static final ThreadLocal<Subject> subjectLocal = new ThreadLocal<>();

    protected static final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    protected static final Map<String, String> layerDefaultRegisIDMap = new HashMap<>();

    // mutable statics should be kept package private to eliminate
    // the ability for subclasses to access them
    static int epoch;
    static String parserClassName = null;
    static ConfigParser parser;
    static boolean parserInitialized = false;
    static AuthConfigFactory slaveFactory = null;

    // keep the slave from being visible outside
    static AuthConfigProvider slaveProvider = null;

    protected AuthConfigFactory factory = null;
    private WebServicesDelegate wsdelegate = null;

    public GFServerConfigProvider(Map<String, String> properties, AuthConfigFactory factory) {
        this.factory = factory;
        initializeParser();

        if (factory != null) {
            boolean hasSlaveFactory = false;
            try {
                rwLock.readLock().lock();
                hasSlaveFactory = slaveFactory != null;
            } finally {
                rwLock.readLock().unlock();
            }

            if (!hasSlaveFactory) {
                try {
                    rwLock.writeLock().lock();
                    if (slaveFactory == null) {
                        slaveFactory = factory;
                    }
                } finally {
                    rwLock.writeLock().unlock();
                }
            }
        }

        boolean hasSlaveProvider = false;
        try {
            rwLock.readLock().lock();
            hasSlaveProvider = slaveProvider != null;
        } finally {
            rwLock.readLock().unlock();
        }

        if (!hasSlaveProvider) {
            try {
                rwLock.writeLock().lock();
                if (slaveProvider == null) {
                    slaveProvider = this;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
        wsdelegate = Globals.get(WebServicesDelegate.class);
    }

    private void initializeParser() {
        try {
            rwLock.readLock().lock();
            if (parserInitialized) {
                return;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        try {
            rwLock.writeLock().lock();
            if (!parserInitialized) {
                parserClassName = System.getProperty("config.parser", DEFAULT_PARSER_CLASS);
                loadParser(this, factory, null);
                parserInitialized = true;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Instantiate+initialize module class
     */
    static ModuleInfo createModuleInfo(Entry entry, CallbackHandler handler, String type, Map properties) throws AuthException {
        try {
            // instantiate module using no-arg constructor
            Object newModule = entry.newInstance();

            Map map = properties;
            Map entryOptions = entry.getOptions();

            if (entryOptions != null) {
                if (map == null) {
                    map = new HashMap();
                } else {
                    map = new HashMap(map);
                }
                map.putAll(entryOptions);
            }

            // no doPrivilege at this point, need to revisit
            if (SERVER.equals(type)) {
                if (newModule instanceof ServerAuthModule) {
                    ServerAuthModule sam = (ServerAuthModule) newModule;
                    sam.initialize(entry.getRequestPolicy(), entry.getResponsePolicy(), handler, map);
                } else if (newModule instanceof com.sun.enterprise.security.jauth.ServerAuthModule) {

                    com.sun.enterprise.security.jauth.ServerAuthModule sam0 = (com.sun.enterprise.security.jauth.ServerAuthModule) newModule;

                    AuthPolicy requestPolicy = entry.getRequestPolicy() != null ? new AuthPolicy(entry.getRequestPolicy()) : null;

                    AuthPolicy responsePolicy = entry.getResponsePolicy() != null ? new AuthPolicy(entry.getResponsePolicy()) : null;

                    sam0.initialize(requestPolicy, responsePolicy, handler, map);
                }
            } else if (newModule instanceof ClientAuthModule) {
                ClientAuthModule cam = (ClientAuthModule) newModule;
                cam.initialize(entry.getRequestPolicy(), entry.getResponsePolicy(), handler, map);
            } else if (newModule instanceof com.sun.enterprise.security.jauth.ClientAuthModule) {

                com.sun.enterprise.security.jauth.ClientAuthModule cam0 = (com.sun.enterprise.security.jauth.ClientAuthModule) newModule;

                AuthPolicy requestPolicy = new AuthPolicy(entry.getRequestPolicy());

                AuthPolicy responsePolicy = new AuthPolicy(entry.getResponsePolicy());

                cam0.initialize(requestPolicy, responsePolicy, handler, map);
            }

            return new ModuleInfo(newModule, map);
        } catch (Exception e) {
            if (e instanceof AuthException) {
                throw (AuthException) e;
            }
            AuthException ae = new AuthException();
            ae.initCause(e);
            throw ae;
        }
    }

    /**
     * Create an object of a given class.
     *
     * @param className
     *
     */
    private static Object createObject(final String className) {
        final ClassLoader loader = getClassLoader();
        if (System.getSecurityManager() != null) {
            try {
                return AccessController.doPrivileged(new PrivilegedExceptionAction() {
                    @Override
                    public Object run() throws Exception {
                        Class c = Class.forName(className, true, loader);
                        return c.newInstance();
                    }
                });
            } catch (PrivilegedActionException pae) {
                throw new RuntimeException(pae.getException());
            }
        }
        try {
            Class c = Class.forName(className, true, loader);
            return c.newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    Entry getEntry(String intercept, String id, MessagePolicy requestPolicy, MessagePolicy responsePolicy, String type) {

        // get the parsed module config and DD information

        Map configMap;

        try {
            rwLock.readLock().lock();
            configMap = parser.getConfigMap();
        } finally {
            rwLock.readLock().unlock();
        }

        if (configMap == null) {
            return null;
        }

        // get the module config info for this intercept

        InterceptEntry intEntry = (InterceptEntry) configMap.get(intercept);
        if (intEntry == null || intEntry.idMap == null) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("module config has no IDs configured for [" + intercept + "]");
            }
            return null;
        }

        // look up the DD's provider ID in the module config

        IDEntry idEntry = null;
        if (id == null || (idEntry = intEntry.idMap.get(id)) == null) {

            // either the DD did not specify a provider ID,
            // or the DD-specified provider ID was not found
            // in the module config.
            //
            // in either case, look for a default ID in the module config

            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("DD did not specify ID, " + "or DD-specified ID for [" + intercept + "] not found in config -- "
                        + "attempting to look for default ID");
            }

            String defaultID;
            if (CLIENT.equals(type)) {
                defaultID = intEntry.defaultClientID;
            } else {
                defaultID = intEntry.defaultServerID;
            }

            idEntry = intEntry.idMap.get(defaultID);
            if (idEntry == null) {

                // did not find a default provider ID

                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("no default config ID for [" + intercept + "]");
                }
                return null;
            }
        }

        // we found the DD provider ID in the module config
        // or we found a default module config

        // check provider-type
        if (idEntry.type.indexOf(type) < 0) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("request type [" + type + "] does not match config type [" + idEntry.type + "]");
            }
            return null;
        }

        // check whether a policy is set
        MessagePolicy reqP = requestPolicy != null || responsePolicy != null ? requestPolicy : idEntry.requestPolicy; // default;

        MessagePolicy respP = requestPolicy != null || responsePolicy != null ? responsePolicy : idEntry.responsePolicy; // default;

        // optimization: if policy was not set, return null
        if (reqP == null && respP == null) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("no policy applies");
            }
            return null;
        }

        // return the configured modules with the correct policies

        Entry entry = new Entry(idEntry.moduleClassName, reqP, respP, idEntry.options);

        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("getEntry for: " + intercept + " -- " + id + "\n    module class: " + entry.moduleClassName + "\n    options: "
                    + entry.options + "\n    request policy: " + entry.requestPolicy + "\n    response policy: " + entry.responsePolicy);
        }

        return entry;
    }

    /**
     * Class representing a single AuthModule entry configured for an ID, interception point, and stack.
     *
     * <p>
     * This class also provides a way for a caller to obtain an instance of the module listed in the entry by invoking the
     * <code>newInstance</code> method.
     */
    static class Entry {

        // for loading modules
        private static final Class[] PARAMS = {};
        private static final Object[] ARGS = {};

        private final String moduleClassName;
        private final MessagePolicy requestPolicy;
        private final MessagePolicy responsePolicy;
        private final Map options;

        /**
         * Construct a ConfigFile entry.
         *
         * <p>
         * An entry encapsulates a single module and its related information.
         *
         * @param moduleClassName the module class name
         * @param requestPolicy the request policy assigned to the module listed in this entry, which may be null.
         *
         * @param responsePolicy the response policy assigned to the module listed in this entry, which may be null.
         *
         * @param options the options configured for this module.
         */
        Entry(String moduleClassName, MessagePolicy requestPolicy, MessagePolicy responsePolicy, Map options) {
            this.moduleClassName = moduleClassName;
            this.requestPolicy = requestPolicy;
            this.responsePolicy = responsePolicy;
            this.options = options;
        }

        /**
         * Return the request policy assigned to this module.
         *
         * @return the policy, which may be null.
         */
        MessagePolicy getRequestPolicy() {
            return requestPolicy;
        }

        /**
         * Return the response policy assigned to this module.
         *
         * @return the policy, which may be null.
         */
        MessagePolicy getResponsePolicy() {
            return responsePolicy;
        }

        String getModuleClassName() {
            return moduleClassName;
        }

        Map getOptions() {
            return options;
        }

        /**
         * Return a new instance of the module contained in this entry.
         *
         * <p>
         * The default implementation of this method attempts to invoke the default no-args constructor of the module class.
         * This method may be overridden if a different constructor should be invoked.
         *
         * @return a new instance of the module contained in this entry.
         *
         * @exception AuthException if the instantiation failed.
         */
        Object newInstance() throws AuthException {
            try {
                final ClassLoader finalLoader = getClassLoader();
                Class c = Class.forName(moduleClassName, true, finalLoader);
                Constructor constructor = c.getConstructor(PARAMS);
                return constructor.newInstance(ARGS);
            } catch (Exception e) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Unable to load auth module {0}: {1}", new Object[] {moduleClassName, e});
                }

                AuthException ae = new AuthException();
                ae.initCause(e);
                throw ae;
            }
        }
    }

    public static class InterceptEntry {
        String defaultClientID;
        String defaultServerID;
        HashMap<String, IDEntry> idMap;

        public InterceptEntry(String defaultClientID, String defaultServerID, HashMap<String, IDEntry> idMap) {
            this.defaultClientID = defaultClientID;
            this.defaultServerID = defaultServerID;
            this.idMap = idMap;
        }

        public HashMap<String, IDEntry> getIdMap() {
            return idMap;
        }

        public void setIdMap(HashMap<String, IDEntry> map) {
            idMap = map;
        }

        public String getDefaultClientID() {
            return defaultClientID;
        }

        public String getDefaultServerID() {
            return defaultServerID;
        }
    }

    /**
     * parsed ID entry
     */
    public static class IDEntry {
        private final String type; // provider type (client, server, client-server)
        private final String moduleClassName;
        private final MessagePolicy requestPolicy;
        private final MessagePolicy responsePolicy;
        private final Map options;

        public String getModuleClassName() {
            return moduleClassName;
        }

        public Map getOptions() {
            return options;
        }

        public MessagePolicy getRequestPolicy() {
            return requestPolicy;
        }

        public MessagePolicy getResponsePolicy() {
            return responsePolicy;
        }

        public String getType() {
            return type;
        }

        public IDEntry(String type, String moduleClassName, MessagePolicy requestPolicy, MessagePolicy responsePolicy, Map options) {
            this.type = type;
            this.moduleClassName = moduleClassName;
            this.requestPolicy = requestPolicy;
            this.responsePolicy = responsePolicy;
            this.options = options;
        }
    }

    /**
     * A data object contains module object and the corresponding map.
     */
    protected static class ModuleInfo {
        private final Object module;
        private final Map map;

        ModuleInfo(Object module, Map map) {
            this.module = module;
            this.map = map;
        }

        Object getModule() {
            return module;
        }

        Map getMap() {
            return map;
        }
    }

    /**
     * Get an instance of ClientAuthConfig from this provider.
     *
     * <p>
     * The implementation of this method returns a ClientAuthConfig instance that describes the configuration of
     * ClientAuthModules at a given message layer, and for use in an identified application context.
     *
     * @param layer a String identifying the message layer for the returned ClientAuthConfig object. This argument must not
     * be null.
     *
     * @param appContext a String that identifies the messaging context for the returned ClientAuthConfig object. This
     * argument must not be null.
     *
     * @param handler a CallbackHandler to be passed to the ClientAuthModules encapsulated by ClientAuthContext objects
     * derived from the returned ClientAuthConfig. This argument may be null, in which case the implementation may assign a
     * default handler to the configuration.
     *
     * @return a ClientAuthConfig Object that describes the configuration of ClientAuthModules at the message layer and
     * messaging context identified by the layer and appContext arguments. This method does not return null.
     *
     * @exception AuthException if this provider does not support the assignment of a default CallbackHandler to the
     * returned ClientAuthConfig.
     *
     * @exception SecurityException if the caller does not have permission to retrieve the configuration.
     *
     * The CallbackHandler assigned to the configuration must support the Callback objects required to be supported by the
     * profile of this specification being followed by the messaging runtime. The CallbackHandler instance must be
     * initialized with any application context needed to process the required callbacks on behalf of the corresponding
     * application.
     */
    @Override
    public ClientAuthConfig getClientAuthConfig(String layer, String appContext, CallbackHandler handler) throws AuthException {
        return new GFClientAuthConfig(this, layer, appContext, handler);
    }

    /**
     * Get an instance of ServerAuthConfig from this provider.
     *
     * <p>
     * The implementation of this method returns a ServerAuthConfig instance that describes the configuration of
     * ServerAuthModules at a given message layer, and for a particular application context.
     *
     * @param layer a String identifying the message layer for the returned ServerAuthConfig object. This argument must not
     * be null.
     *
     * @param appContext a String that identifies the messaging context for the returned ServerAuthConfig object. This
     * argument must not be null.
     *
     * @param handler a CallbackHandler to be passed to the ServerAuthModules encapsulated by ServerAuthContext objects
     * derived from thr returned ServerAuthConfig. This argument may be null, in which case the implementation may assign a
     * default handler to the configuration.
     *
     * @return a ServerAuthConfig Object that describes the configuration of ServerAuthModules at a given message layer, and
     * for a particular application context. This method does not return null.
     *
     * @exception AuthException if this provider does not support the assignment of a default CallbackHandler to the
     * returned ServerAuthConfig.
     *
     * @exception SecurityException if the caller does not have permission to retrieve the configuration.
     * <p>
     * The CallbackHandler assigned to the configuration must support the Callback objects required to be supported by the
     * profile of this specification being followed by the messaging runtime. The CallbackHandler instance must be
     * initialized with any application context needed to process the required callbacks on behalf of the corresponding
     * application.
     */
    @Override
    public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler) throws AuthException {
        return new GFServerAuthConfig(this, layer, appContext, handler);
    }

    /**
     * Causes a dynamic configuration provider to update its internal state such that any resulting change to its state is
     * reflected in the corresponding authentication context configuration objects previously created by the provider within
     * the current process context.
     *
     * @exception AuthException if an error occured during the refresh.
     *
     * @exception SecurityException if the caller does not have permission to refresh the provider.
     */

    @Override
    public void refresh() {
        loadParser(this, factory, null);
    }

    /**
     * this method is intended to be called by the admin configuration system when the corresponding config object has
     * changed. It relies on the slaves, since it is a static method.
     *
     * @param config a config object of type understood by the parser. NOTE: there appears to be a thread saftey problem,
     * and this method will fail if a slaveProvider has not been established prior to its call.
     */
    public static void loadConfigContext(Object config) {

        boolean hasSlaveFactory = false;
        rwLock.readLock().lock();
        try {
            hasSlaveFactory = slaveFactory != null;
        } finally {
            rwLock.readLock().unlock();
        }

        if (slaveProvider == null) {
            if (LOG.isLoggable(Level.SEVERE)) {
                LOG.severe("No slave provider set.");
            }
            return;
        }

        if (!hasSlaveFactory) {
            rwLock.writeLock().lock();
            try {
                if (slaveFactory == null) {
                    slaveFactory = AuthConfigFactory.getFactory();
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        loadParser(slaveProvider, slaveFactory, config);
    }

    protected static void loadParser(AuthConfigProvider aProvider, AuthConfigFactory aFactory, Object config) {
        rwLock.writeLock().lock();
        try {
            ConfigParser nextParser;
            int next = epoch + 1;
            nextParser = (ConfigParser) createObject(parserClassName);
            nextParser.initialize(config);

            if (aFactory != null && aProvider != null) {
                Set<String> layerSet = nextParser.getLayersWithDefault();
                for (String layer : layerDefaultRegisIDMap.keySet()) {
                    if (!layerSet.contains(layer)) {
                        String regisID = layerDefaultRegisIDMap.remove(layer);
                        aFactory.removeRegistration(regisID);
                    }
                }

                for (String layer : layerSet) {
                    if (!layerDefaultRegisIDMap.containsKey(layer)) {
                        String regisID = aFactory.registerConfigProvider(aProvider, layer, null,
                                "GFServerConfigProvider: self registration");
                        layerDefaultRegisIDMap.put(layer, regisID);
                    }
                }
            }
            epoch = next == 0 ? 1 : next;
            parser = nextParser;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    protected static ClassLoader getClassLoader() {
        if (System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        }

        return (ClassLoader) AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Object run() {
                return Thread.currentThread().getContextClassLoader();
            }
        });
    }

    // for old API
    public static void setValidateRequestSubject(Subject subject) {
        subjectLocal.set(subject);
    }

    class GFAuthConfig implements AuthConfig {
        protected AuthConfigProvider provider = null;
        protected String layer = null;
        protected String appContext = null;
        protected CallbackHandler handler = null;
        protected String type = null;
        protected String providerID = null;
        protected boolean init = false;
        protected boolean onePolicy = false;
        //        protected boolean newHandler = false;
        protected MessageSecurityBindingDescriptor binding = null;
        protected SunWebApp sunWebApp = null;

        protected GFAuthConfig(AuthConfigProvider provider, String layer, String appContext, CallbackHandler handler, String type) {
            this.provider = provider;
            this.layer = layer;
            this.appContext = appContext;
            this.type = type;
            if (handler == null) {
                handler = AuthMessagePolicy.getDefaultCallbackHandler();
                //        this.newHandler = true;
            }
            this.handler = handler;
        }

        /**
         * Get the message layer name of this authentication context configuration object.
         *
         * @return the message layer name of this configuration object, or null if the configuration object pertains to an
         * unspecified message layer.
         */
        @Override
        public String getMessageLayer() {
            return layer;
        }

        /**
         * Get the application context identifier of this authentication context configuration object.
         *
         * @return the String identifying the application context of this configuration object or null if the configuration
         * object pertains to an unspecified application context.
         */
        @Override
        public String getAppContext() {
            return appContext;
        }

        /**
         * Get the authentication context identifier corresponding to the request and response objects encapsulated in
         * messageInfo.
         *
         * See method AuthMessagePolicy. getHttpServletPolicies() for more details on why this method returns the String's
         * "true" or "false" for AuthContextID.
         *
         * @param messageInfo a contextual Object that encapsulates the client request and server response objects.
         *
         * @return the authentication context identifier corresponding to the encapsulated request and response objects, or
         * null.
         *
         *
         * @throws IllegalArgumentException if the type of the message objects incorporated in messageInfo are not compatible
         * with the message types supported by this authentication context configuration object.
         */
        @Override
        public String getAuthContextID(MessageInfo messageInfo) {
            if (GFServerConfigProvider.HTTPSERVLET.equals(layer)) {
                String isMandatoryStr = (String) messageInfo.getMap().get(HttpServletConstants.IS_MANDATORY);
                return Boolean.valueOf(isMandatoryStr).toString();
            }
            if (GFServerConfigProvider.SOAP.equals(layer)) {
                if (wsdelegate != null) {
                    return wsdelegate.getAuthContextID(messageInfo);
                }
            }
            return null;
        }

        // we should be able to replace the following with a method on packet

        /**
         * Causes a dynamic anthentication context configuration object to update the internal state that it uses to process
         * calls to its <code>getAuthContext</code> method.
         *
         * @exception AuthException if an error occured during the update.
         *
         * @exception SecurityException if the caller does not have permission to refresh the configuration object.
         */
        @Override
        public void refresh() {
            loadParser(provider, factory, null);
        }

        /**
         * Used to determine whether or not the <code>getAuthContext</code> method of the authentication context configuration
         * will return null for all possible values of authentication context identifier.
         *
         * @return false when <code>getAuthContext</code> will return null for all possible values of authentication context
         * identifier. Otherwise, this method returns true.
         */
        @Override
        public boolean isProtected() {
            // XXX TBD
            return true;
        }

        protected AuthParam getAuthParam(MessageInfo info) throws AuthException {

            if (GFServerConfigProvider.HTTPSERVLET.equals(layer)) {
                return new HttpServletAuthParam(info);
            }
            if (GFServerConfigProvider.SOAP.equals(layer)) {
                if (wsdelegate != null) {
                    return wsdelegate.newSOAPAuthParam(info);
                }
            }
            throw new AuthException("unsupported AuthParam type");
        }

        CallbackHandler getCallbackHandler() {
            return handler;
        }

        protected ModuleInfo getModuleInfo(String authContextID, Map properties) throws AuthException {
            if (!init) {
                initialize(properties);
            }

            MessagePolicy[] policies = null;

            if (GFServerConfigProvider.HTTPSERVLET.equals(layer)) {

                policies = AuthMessagePolicy.getHttpServletPolicies(authContextID);

            } else {

                policies = AuthMessagePolicy.getSOAPPolicies(binding, authContextID, onePolicy);
            }

            MessagePolicy requestPolicy = policies[0];
            MessagePolicy responsePolicy = policies[1];

            Entry entry = getEntry(layer, providerID, requestPolicy, responsePolicy, type);

            return entry != null ? createModuleInfo(entry, handler, type, properties) : null;
        }

        // lazy initialize this as SunWebApp is not available in
        // RealmAdapter creation
        private void initialize(Map properties) {
            if (!init) {

                if (GFServerConfigProvider.HTTPSERVLET.equals(layer)) {
                    sunWebApp = AuthMessagePolicy.getSunWebApp(properties);
                    providerID = AuthMessagePolicy.getProviderID(sunWebApp);
                    onePolicy = true;
                } else {
                    binding = AuthMessagePolicy.getMessageSecurityBinding(layer, properties);
                    providerID = AuthMessagePolicy.getProviderID(binding);
                    onePolicy = AuthMessagePolicy.oneSOAPPolicy(binding);
                }

                // handlerContext need to be explictly set by caller
                init = true;
            }
        }
    }

    class GFServerAuthConfig extends GFAuthConfig implements ServerAuthConfig {

        protected GFServerAuthConfig(AuthConfigProvider provider, String layer, String appContext, CallbackHandler handler) {
            super(provider, layer, appContext, handler, SERVER);
        }

        @Override
        public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject, Map properties) throws AuthException {
            ServerAuthContext serverAuthContext = null;
            ModuleInfo moduleInfo = getModuleInfo(authContextID, properties);

            if (moduleInfo != null && moduleInfo.getModule() != null) {
                Object moduleObj = moduleInfo.getModule();
                Map map = moduleInfo.getMap();
                if (moduleObj instanceof ServerAuthModule) {
                    serverAuthContext = new GFServerAuthContext(this, (ServerAuthModule) moduleObj, map);
                } else {
                    serverAuthContext = new GFServerAuthContext(this, (com.sun.enterprise.security.jauth.ServerAuthModule) moduleObj, map);
                }
            }

            return serverAuthContext;
        }
    }

    class GFClientAuthConfig extends GFAuthConfig implements ClientAuthConfig {

        protected GFClientAuthConfig(AuthConfigProvider provider, String layer, String appContext, CallbackHandler handler) {
            super(provider, layer, appContext, handler, CLIENT);
        }

        @Override
        public ClientAuthContext getAuthContext(String authContextID, Subject clientSubject, Map properties) throws AuthException {
            ClientAuthContext clientAuthContext = null;
            ModuleInfo moduleInfo = getModuleInfo(authContextID, properties);

            if (moduleInfo != null && moduleInfo.getModule() != null) {
                Object moduleObj = moduleInfo.getModule();
                Map map = moduleInfo.getMap();
                if (moduleObj instanceof ClientAuthModule) {
                    clientAuthContext = new GFClientAuthContext(this, (ClientAuthModule) moduleObj, map);
                } else {
                    clientAuthContext = new GFClientAuthContext(this, (com.sun.enterprise.security.jauth.ClientAuthModule) moduleObj, map);
                }
            }

            return clientAuthContext;
        }
    }

    static protected class GFServerAuthContext implements ServerAuthContext {

        private final GFServerAuthConfig config;
        private final ServerAuthModule module;
        private final com.sun.enterprise.security.jauth.ServerAuthModule oldModule;

        private final Map map;
        boolean managesSession = false;

        GFServerAuthContext(GFServerAuthConfig config, ServerAuthModule module, Map map) {
            this.config = config;
            this.module = module;
            this.oldModule = null;
            this.map = map;
        }

        GFServerAuthContext(GFServerAuthConfig config, com.sun.enterprise.security.jauth.ServerAuthModule module, Map map) {
            this.config = config;
            this.module = null;
            this.oldModule = module;
            this.map = map;
            if (map != null) {
                String msStr = (String) map.get(GFServerConfigProvider.MANAGES_SESSIONS_OPTION);
                if (msStr != null) {
                    managesSession = Boolean.valueOf(msStr);
                }
            }
        }

        // for old modules
        private static void _setCallerPrincipals(Subject s, CallbackHandler handler, Subject pvcSubject) throws AuthException {

            if (handler != null) { // handler should be non-null
                Set<Principal> ps = s.getPrincipals();
                if (ps == null || ps.isEmpty()) {
                    return;
                }
                Iterator<Principal> it = ps.iterator();

                Callback[] callbacks = new Callback[] { new CallerPrincipalCallback(s, it.next().getName()) };
                if (pvcSubject != null) {
                    s.getPrincipals().addAll(pvcSubject.getPrincipals());
                }

                try {
                    handler.handle(callbacks);
                } catch (Exception e) {
                    AuthException aex = new AuthException();
                    aex.initCause(e);
                    throw aex;
                }
            }
        }

        // for old modules
        private static void setCallerPrincipals(final Subject s, final CallbackHandler handler, final Subject pvcSubject)
                throws AuthException {
            if (System.getSecurityManager() == null) {
                _setCallerPrincipals(s, handler, pvcSubject);
            } else {
                try {
                    AccessController.doPrivileged(new PrivilegedExceptionAction() {
                        @Override
                        public Object run() throws Exception {
                            _setCallerPrincipals(s, handler, pvcSubject);
                            return null;
                        }
                    });
                } catch (PrivilegedActionException pae) {
                    Throwable cause = pae.getCause();
                    AuthException aex = new AuthException();
                    aex.initCause(cause);
                    throw aex;
                }
            }
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException {
            if (module != null) {
                return module.validateRequest(messageInfo, clientSubject, serviceSubject);
            }

            if (oldModule == null) {
                throw new AuthException();
            }
            try {
                subjectLocal.remove();
                oldModule.validateRequest(config.getAuthParam(messageInfo), clientSubject, messageInfo.getMap());
                setCallerPrincipals(clientSubject, config.getCallbackHandler(), subjectLocal.get());
                if (!managesSession && GFServerConfigProvider.HTTPSERVLET.equals(config.getMessageLayer())) {
                    messageInfo.getMap().put(HttpServletConstants.REGISTER_WITH_AUTHENTICATOR, Boolean.TRUE.toString());
                }
                return AuthStatus.SUCCESS;
            } catch (PendingException pe) {
                return AuthStatus.SEND_CONTINUE;
            } catch (FailureException fe) {
                return AuthStatus.SEND_FAILURE;
            } finally {
                subjectLocal.remove();
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException {
            if (module != null) {
                return module.secureResponse(messageInfo, serviceSubject);
            }

            if (oldModule != null) {
                oldModule.secureResponse(config.getAuthParam(messageInfo), serviceSubject, messageInfo.getMap());
                return AuthStatus.SEND_SUCCESS;
            }
            throw new AuthException();
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException {
            if (module != null) {
                module.cleanSubject(messageInfo, subject);
            } else if (oldModule != null) {
                oldModule.disposeSubject(subject, messageInfo.getMap());
            } else {
                throw new AuthException();
            }
        }
    }

    static protected class GFClientAuthContext implements ClientAuthContext {

        private final GFClientAuthConfig config;
        private final ClientAuthModule module;
        private final com.sun.enterprise.security.jauth.ClientAuthModule oldModule;
        // private Map map;

        GFClientAuthContext(GFClientAuthConfig config, ClientAuthModule module, Map map) {
            this.config = config;
            this.module = module;
            this.oldModule = null;
            // this.map = map;
        }

        GFClientAuthContext(GFClientAuthConfig config, com.sun.enterprise.security.jauth.ClientAuthModule module, Map map) {
            this.config = config;
            this.module = null;
            this.oldModule = module;
            // this.map = map;
        }

        @Override
        public AuthStatus secureRequest(MessageInfo messageInfo, Subject clientSubject) throws AuthException {
            if (module != null) {
                return module.secureRequest(messageInfo, clientSubject);
            }

            if (oldModule != null) {
                oldModule.secureRequest(config.getAuthParam(messageInfo), clientSubject, messageInfo.getMap());
                return AuthStatus.SEND_SUCCESS;
            }
            throw new AuthException();
        }

        @Override
        public AuthStatus validateResponse(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException {
            if (module != null) {
                return module.validateResponse(messageInfo, clientSubject, serviceSubject);
            }

            if (oldModule != null) {
                oldModule.validateResponse(config.getAuthParam(messageInfo), clientSubject, messageInfo.getMap());
                return AuthStatus.SUCCESS;
            }
            throw new AuthException();
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException {
            if (module != null) {
                module.cleanSubject(messageInfo, subject);
            } else if (oldModule != null) {
                oldModule.disposeSubject(subject, messageInfo.getMap());
            } else {
                throw new AuthException();
            }
        }
    }
}
