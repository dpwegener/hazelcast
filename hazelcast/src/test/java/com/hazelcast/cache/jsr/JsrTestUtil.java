/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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
 */

package com.hazelcast.cache.jsr;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.instance.HazelcastInstanceFactory;

import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

import static java.lang.String.format;
import static org.junit.Assert.fail;

/**
 * Utility class responsible for setup/cleanup of JSR member tests.
 */
public final class JsrTestUtil {

    /**
     * Keeps track of system properties set by this utility.
     * <p>
     * We have to manage the System properties by ourselves, since they are set in {@link org.junit.BeforeClass} methods,
     * which are invoked before our Hazelcast {@link org.junit.runner.Runner} classes are copying the System properties
     * to restore them for us.
     */
    private static final List<String> SYSTEM_PROPERTY_REGISTRY = new LinkedList<String>();

    private JsrTestUtil() {
    }

    public static void setup() {
        setSystemProperties("server");
    }

    public static void cleanup() {
        clearSystemProperties();
        clearCachingProviderRegistry();

        Hazelcast.shutdownAll();
        HazelcastInstanceFactory.terminateAll();
    }

    /**
     * Sets the System properties for JSR related tests.
     * <p>
     * Uses plain strings to avoid triggering any classloading of JSR classes with static code initializations.
     *
     * @param providerType "server" or "client" according to your test type
     */
    public static void setSystemProperties(String providerType) {
        /*
        If we don't set this parameter the HazelcastCachingProvider will try to determine if it has to
        create a client or server CachingProvider by looking for the client class. If you run the testsuite
        from IDEA across all modules, that class is available (even though you might want to start a server
        side test). This leads to a ClassCastException for server side tests, since a client CachingProvider
        will be created. So we explicitly set this property to ease the test setups for IDEA environments.
         */
        setSystemProperty("hazelcast.jcache.provider.type", providerType);

        setSystemProperty("javax.management.builder.initial", "com.hazelcast.cache.impl.TCKMBeanServerBuilder");
        setSystemProperty("CacheManagerImpl", "com.hazelcast.cache.HazelcastCacheManager");
        setSystemProperty("javax.cache.Cache", "com.hazelcast.cache.ICache");
        setSystemProperty("javax.cache.Cache.Entry", "com.hazelcast.cache.impl.CacheEntry");
        setSystemProperty("org.jsr107.tck.management.agentId", "TCKMbeanServer");
        setSystemProperty("javax.cache.annotation.CacheInvocationContext",
                "javax.cache.annotation.impl.cdi.CdiCacheKeyInvocationContextImpl");
    }

    /**
     * Clears the System properties for JSR related tests.
     */
    public static void clearSystemProperties() {
        for (String key : SYSTEM_PROPERTY_REGISTRY) {
            System.clearProperty(key);
        }
        SYSTEM_PROPERTY_REGISTRY.clear();
    }

    /**
     * Closes and removes the {@link javax.cache.spi.CachingProvider} from the static registry in {@link Caching}.
     */
    public static void clearCachingProviderRegistry() {
        try {
            for (CachingProvider cachingProvider : Caching.getCachingProviders()) {
                try {
                    cachingProvider.close();
                } catch (HazelcastInstanceNotActiveException ignored) {
                    // this is fine, since the instances can already be stopped
                }
            }

            // retrieve the CachingProviderRegistry instance
            Field providerRegistryField = Caching.class.getDeclaredField("CACHING_PROVIDERS");
            providerRegistryField.setAccessible(true);

            Class<?> providerRegistryClass = providerRegistryField.getType();
            Object providerRegistryInstance = providerRegistryField.get(Caching.class);

            // retrieve the map with the CachingProvider instances
            Field providerMapField = providerRegistryClass.getDeclaredField("cachingProviders");
            providerMapField.setAccessible(true);

            Class<?> providerMapClass = providerMapField.getType();
            Object providerMap = providerMapField.get(providerRegistryInstance);

            // clear the map
            Method clearMethod = providerMapClass.getDeclaredMethod("clear");
            clearMethod.invoke(providerMap);

            // retrieve the ClassLoader of the CachingProviderRegistry
            Field classLoaderField = providerRegistryClass.getDeclaredField("classLoader");
            classLoaderField.setAccessible(true);

            // set the ClassLoader to null
            classLoaderField.set(providerRegistryInstance, null);
        } catch (Exception e) {
            e.printStackTrace();
            fail(format("Could not cleanup CachingProvider registry: [%s] %s", e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    private static void setSystemProperty(String key, String value) {
        // we just want to set a System property, which has not been set already
        // this way you can always override a JSR setting manually
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            SYSTEM_PROPERTY_REGISTRY.add(key);
        }
    }
}
