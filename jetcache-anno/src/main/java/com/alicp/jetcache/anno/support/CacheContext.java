/**
 * Created on  13-09-04 15:34
 */
package com.alicp.jetcache.anno.support;

import com.alicp.jetcache.*;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.EnableCache;
import com.alicp.jetcache.anno.SerialPolicy;
import com.alicp.jetcache.anno.method.CacheInvokeContext;
import com.alicp.jetcache.anno.method.ClassUtil;
import com.alicp.jetcache.factory.EmbeddedCacheFactory;
import com.alicp.jetcache.factory.ExternalCacheFactory;
import com.alicp.jetcache.support.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author <a href="mailto:yeli.hl@taobao.com">huangli</a>
 */
public class CacheContext {

    private static ThreadLocal<CacheThreadLocal> cacheThreadLocal = new ThreadLocal<CacheThreadLocal>() {
        @Override
        protected CacheThreadLocal initialValue() {
            return new CacheThreadLocal();
        }
    };
    private CacheManager cacheManager;
    private GlobalCacheConfig globalCacheConfig;
    private DefaultCacheMonitorManager defaultCacheMonitorManager;

    public CacheContext(GlobalCacheConfig globalCacheConfig) {
        this.globalCacheConfig = globalCacheConfig;
    }

    @PostConstruct
    public void init() {
        this.cacheManager = new CacheManager();
        defaultCacheMonitorManager = new DefaultCacheMonitorManager(15, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        defaultCacheMonitorManager.shutdown();
    }

    public DefaultCacheMonitorManager getDefaultCacheMonitorManager() {
        return defaultCacheMonitorManager;
    }

    public CacheInvokeContext createCacheInvokeContext() {
        CacheInvokeContext c = newCacheInvokeContext();
        c.setCacheFunction((invokeContext) -> {
            CacheAnnoConfig cacheAnnoConfig = invokeContext.getCacheInvokeConfig().getCacheAnnoConfig();
            String area = cacheAnnoConfig.getArea();
            String subArea = ClassUtil.getSubArea(cacheAnnoConfig.getVersion(),
                    invokeContext.getMethod(), invokeContext.getHiddenPackages());
            String cacheName = area + "_" + subArea;
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                if (cacheAnnoConfig.getCacheType() == CacheType.LOCAL) {
                    cache = buildLocal(cacheAnnoConfig, area);
                } else if (cacheAnnoConfig.getCacheType() == CacheType.REMOTE) {
                    cache = buildRemote(cacheAnnoConfig, area, subArea);
                } else {
                    Cache local = buildLocal(cacheAnnoConfig, area);
                    DefaultCacheMonitor localMonitor = new DefaultCacheMonitor(cacheName + "_local");
                    local = new MonitoredCache(local, localMonitor);

                    Cache remote = buildRemote(cacheAnnoConfig, area, subArea);
                    DefaultCacheMonitor remoteMonitor = new DefaultCacheMonitor(cacheName + "_remote");
                    remote = new MonitoredCache(remote, remoteMonitor);

                    defaultCacheMonitorManager.add(localMonitor, remoteMonitor);

                    cache = new MultiLevelCache(local, remote);
                }

                DefaultCacheMonitor monitor = new DefaultCacheMonitor(cacheName);
                cache = new MonitoredCache(cache, new DefaultCacheMonitor(cacheName));
                defaultCacheMonitorManager.add(monitor);

                cacheManager.addCache(cacheName, cache);
            }
            return cache;
        });

        return c;
    }

    private Cache buildRemote(CacheAnnoConfig cacheAnnoConfig, String area, String subArea) {
        ExternalCacheFactory cacheFactory = (ExternalCacheFactory) globalCacheConfig.getRemoteCacheFacotories().get(area);
        if (cacheFactory == null) {
            throw new CacheConfigException("no CacheFactory with name \"" + area + "\" defined in remoteCacheFacotories");
        }
        cacheFactory.setDefaultExpireInMillis(cacheAnnoConfig.getExpire() * 1000);
        cacheFactory.setKeyPrefix(subArea);
        if (SerialPolicy.KRYO.equals(cacheAnnoConfig.getSerialPolicy())) {
            cacheFactory.setValueEncoder(KryoValueEncoder.INSTANCE);
            cacheFactory.setValueDecoder(KryoValueDecoder.INSTANCE);
        } else if (SerialPolicy.JAVA.equals(cacheAnnoConfig.getSerialPolicy())) {
            cacheFactory.setValueEncoder(JavaValueEncoder.INSTANCE);
            cacheFactory.setValueDecoder(JavaValueDecoder.INSTANCE);
        } else if (SerialPolicy.FASTJSON.equals(cacheAnnoConfig.getSerialPolicy())) {
            //noinspection deprecation
            cacheFactory.setValueEncoder(FastjsonValueEncoder.INSTANCE);
            //noinspection deprecation
            cacheFactory.setValueDecoder(FastjsonValueDecoder.INSTANCE);
        } else {
            throw new CacheException(cacheAnnoConfig.getSerialPolicy());
        }
        return cacheFactory.buildCache();
    }

    private Cache buildLocal(CacheAnnoConfig cacheAnnoConfig, String area) {
        Cache cache;
        EmbeddedCacheFactory cacheFactory = (EmbeddedCacheFactory) globalCacheConfig.getLocalCacheFacotories().get(area);
        if (cacheFactory == null) {
            throw new CacheConfigException("no CacheFactory with name \"" + area + "\" defined in localCacheFactory");
        }
        cacheFactory.setLimit(cacheAnnoConfig.getLocalLimit());
        cacheFactory.setDefaultExpireInMillis(cacheAnnoConfig.getExpire() * 1000);
        cache = cacheFactory.buildCache();
        return cache;
    }

    protected CacheInvokeContext newCacheInvokeContext() {
        return new CacheInvokeContext();
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * Enable cache in current thread, for @Cached(enabled=false).
     *
     * @param callback
     * @see EnableCache
     */
    public static <T> T enableCache(Supplier<T> callback) {
        CacheThreadLocal var = cacheThreadLocal.get();
        try {
            var.setEnabledCount(var.getEnabledCount() + 1);
            return callback.get();
        } finally {
            var.setEnabledCount(var.getEnabledCount() - 1);
        }
    }

    protected static void enable() {
        CacheThreadLocal var = cacheThreadLocal.get();
        var.setEnabledCount(var.getEnabledCount() + 1);
    }

    protected static void disable() {
        CacheThreadLocal var = cacheThreadLocal.get();
        var.setEnabledCount(var.getEnabledCount() - 1);
    }

    protected static boolean isEnabled() {
        return cacheThreadLocal.get().getEnabledCount() > 0;
    }

}