/*
 * Copyright (c) 2006, 2022 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.admin.amx.impl.j2ee.loader;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.Servers;
import org.glassfish.admin.amx.base.DomainRoot;
import org.glassfish.admin.amx.config.AMXConfigConstants;
import org.glassfish.admin.amx.core.proxy.ProxyFactory;
import org.glassfish.admin.amx.impl.config.ConfigBeanRegistry;
import org.glassfish.admin.amx.impl.j2ee.DASJ2EEServerImpl;
import org.glassfish.admin.amx.impl.j2ee.J2EEDomainImpl;
import org.glassfish.admin.amx.impl.j2ee.Metadata;
import org.glassfish.admin.amx.impl.j2ee.MetadataImpl;
import org.glassfish.admin.amx.impl.util.ImplUtil;
import org.glassfish.admin.amx.impl.util.InjectedValues;
import org.glassfish.admin.amx.impl.util.ObjectNameBuilder;
import org.glassfish.admin.amx.j2ee.J2EEDomain;
import org.glassfish.admin.amx.j2ee.J2EETypes;
import org.glassfish.admin.amx.util.FeatureAvailability;
import org.glassfish.api.amx.AMXLoader;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.data.ApplicationRegistry;
import jakarta.inject.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.*;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.beans.PropertyChangeEvent;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.admin.amx.j2ee.AMXEELoggerInfo;


/**
 * Startup service that loads support for AMX config MBeans.
 * How this is to be triggered is not yet clear.
 */
@Service
public final class AMXJ2EEStartupService
        implements org.glassfish.hk2.api.PostConstruct,
        org.glassfish.hk2.api.PreDestroy,
        AMXLoader, ConfigListener {

    private static void debug(final String s) {
        System.out.println(s);
    }

    @Inject
    private MBeanServer mMBeanServer;

    @Inject
    InjectedValues mCore;

    @Inject
    private Domain domain;

    private static final Logger logger = AMXEELoggerInfo.getLogger();

    public InjectedValues getCore() {
        return mCore;
    }

    @Inject
    ServiceLocator mHabitat;

    @Inject
    private ApplicationRegistry mAppsRegistry;

    public ApplicationRegistry getApplicationRegistry() {
        return mAppsRegistry;
    }


    public AMXJ2EEStartupService() {
        //debug( "AMXStartupService.AMXStartupService()" );
    }

    @Override
    public void postConstruct() {
        addListenerToServer();
    }

    private void addListenerToServer() {
        Servers servers = domain.getServers();
        ObservableBean bean = (ObservableBean) ConfigSupport.getImpl(servers);
        bean.addListener(this);
    }

    @Override
    public UnprocessedChangeEvents changed(PropertyChangeEvent[] propertyChangeEvents) {
        return ConfigSupport.sortAndDispatch(propertyChangeEvents, new PropertyChangeHandler(propertyChangeEvents), logger);
    }

    class PropertyChangeHandler implements Changed {

        private PropertyChangeHandler(PropertyChangeEvent[] events) {
        }

        /**
         * Notification of a change on a configuration object
         *
         * @param type            type of change : ADD mean the changedInstance was added to the parent
         *                        REMOVE means the changedInstance was removed from the parent, CHANGE means the
         *                        changedInstance has mutated.
         * @param changedType     type of the configuration object
         * @param changedInstance changed instance.
         */
        @Override
        public <T extends ConfigBeanProxy> NotProcessed changed(TYPE type, Class<T> changedType, T changedInstance) {
            switch (type) {
                case ADD:
                    if (changedInstance instanceof Server) {
                        Server server = (Server) changedInstance;
                        String serverName = server.getName();

                        MetadataImpl meta = new MetadataImpl();
                        meta.setCorrespondingConfig(ConfigBeanRegistry.getInstance().getObjectNameForProxy(server));
                        final DASJ2EEServerImpl impl = new DASJ2EEServerImpl(getJ2EEDomain(), meta);
                        ObjectName serverObjectName = new ObjectNameBuilder(mMBeanServer, getJ2EEDomain()).buildChildObjectName(J2EETypes.J2EE_SERVER, serverName);
                        try {
                            ObjectName instance = mMBeanServer.registerMBean(impl, serverObjectName).getObjectName();
                        }
                        catch (JMException e) {
                            throw new Error(e);
                        }
                    }
                    break;

                case REMOVE:
                    if (changedInstance instanceof Server) {
                        Server server = (Server) changedInstance;
                        String serverName = server.getName();

                        ObjectName serverObjectName = new ObjectNameBuilder(mMBeanServer, getJ2EEDomain()).buildChildObjectName(J2EETypes.J2EE_SERVER, serverName);

                        try {
                            Set serverSet = mMBeanServer.queryNames(new ObjectName(serverObjectName.toString() + ",*"), null);
                            Iterator it = serverSet.iterator();
                            while (it.hasNext()) {
                                ObjectName element = (ObjectName) it.next();
                                mMBeanServer.unregisterMBean(element);
                            }
                        }
                        catch (JMException e) {
                            throw new Error(e);
                        }
                    }
                    break;

                default:
                    break;
            }
            return null;
        }
    }

    @Override
    public void preDestroy() {
        unloadAMXMBeans();
    }


    private DomainRoot getDomainRootProxy() {
        return ProxyFactory.getInstance(mMBeanServer).getDomainRootProxy();
    }


    public ObjectName getJ2EEDomain() {
        return getDomainRootProxy().child(J2EETypes.J2EE_DOMAIN).extra().objectName();
    }


    private J2EEDomain getJ2EEDomainProxy() {
        return ProxyFactory.getInstance(mMBeanServer).getProxy(getJ2EEDomain(), J2EEDomain.class);
    }


    @Override
    public synchronized ObjectName loadAMXMBeans() {
        FeatureAvailability.getInstance().waitForFeature(FeatureAvailability.AMX_CORE_READY_FEATURE, "" + this);
        FeatureAvailability.getInstance().waitForFeature(AMXConfigConstants.AMX_CONFIG_READY_FEATURE, "" + this);

        final DomainRoot domainRootProxy = ProxyFactory.getInstance(mMBeanServer).getDomainRootProxy(false);
        final ObjectName domainRoot = domainRootProxy.objectName();
        final ObjectNameBuilder objectNames = new ObjectNameBuilder(mMBeanServer, domainRoot);

        final Metadata metadata = new MetadataImpl();
        metadata.add(Metadata.CORRESPONDING_CONFIG, ConfigBeanRegistry.getInstance().getObjectNameForProxy(domain));

        String serverName = mHabitat.<Server> getService(Server.class).getName();

        final J2EEDomainImpl impl = new J2EEDomainImpl(domainRoot, metadata);
        impl.setServerName(serverName);
        ObjectName objectName = objectNames.buildChildObjectName(J2EEDomain.class);
        try {
            objectName = mMBeanServer.registerMBean(impl, objectName).getObjectName();
        } catch (JMException e) {
            throw new Error(e);
        }

        logger.log(Level.INFO, AMXEELoggerInfo.domainRegistered, objectName);
        return objectName;
    }


    @Override
    public synchronized void unloadAMXMBeans() {
        final J2EEDomain j2eeDomain = getJ2EEDomainProxy();
        if (j2eeDomain != null) {
            ImplUtil.unregisterAMXMBeans(j2eeDomain);
        }
    }
}
