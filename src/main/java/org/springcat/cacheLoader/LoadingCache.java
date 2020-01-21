package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Builder
@Data
public class LoadingCache<K, V> {

    //缓存名称
    private String cacheName;

    //缓存容量
    @Builder.Default
    private int cacheSize = Integer.MAX_VALUE;

    //查询缓存操作
    private Function<CacheRequest<K, V>, V> cacheGetter;

    //加载数据操作
    private Function<CacheRequest<K, V>, V> cacheLoader;

    //加载缓存操作
    private Consumer<CacheRequest<K, V>> cachePutter;

    //时间默认单位秒
    private Long expireTime;

    //是否启动空值缓存
    @Builder.Default
    private boolean emptyElementCached = false;

    //空值缓存的值
    private V emptyElement;

    //空值缓存的过期时间
    private long emptyElementExpireTime;

    //随机过期时间，在expireTime的基础上随机增加或者减少randomExpireTime秒，防止缓存集中过期造成系统突刺
    private long randomExpireTime;

    //loader并发加载数,默认无限制
    private Semaphore loaderConcurrency;

    //loader并发加载数满后等待时长，默认等待5分钟,设置为0，则不等待立即返回
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeout = 5 * 60L;

    //单个key上是否并发加载
    @Builder.Default
    private boolean isLoaderConcurrencyOnKey = true;

    //单个key，并发加载数满后等待时长，默认等待5分钟,设置为0，则不等待立即返回
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeoutOnKey = 5 * 60L;

    @Builder.Default
    private KeyLockPool<K, V> keyLockPool;

    public LoadingCache(String cacheName, int cacheSize, Function<CacheRequest<K, V>, V> cacheGetter, Function<CacheRequest<K, V>, V> cacheLoader, Consumer<CacheRequest<K, V>> cachePutter, Long expireTime, boolean emptyElementCached, V emptyElement, long emptyElementExpireTime, long randomExpireTime, Semaphore loaderConcurrency, Long loaderConcurrencyPolicyTimeout, boolean isLoaderConcurrencyOnKey, Long loaderConcurrencyPolicyTimeoutOnKey, KeyLockPool<K, V> keyLockPool) {
        this.cacheName = cacheName;
        this.cacheSize = cacheSize;
        this.cacheGetter = cacheGetter;
        this.cacheLoader = cacheLoader;
        this.cachePutter = cachePutter;
        this.expireTime = expireTime;
        this.emptyElementCached = emptyElementCached;
        this.emptyElement = emptyElement;
        this.emptyElementExpireTime = emptyElementExpireTime;
        this.randomExpireTime = randomExpireTime;
        this.loaderConcurrency = loaderConcurrency;
        this.loaderConcurrencyPolicyTimeout = loaderConcurrencyPolicyTimeout;
        this.isLoaderConcurrencyOnKey = isLoaderConcurrencyOnKey;
        this.loaderConcurrencyPolicyTimeoutOnKey = loaderConcurrencyPolicyTimeoutOnKey;
        this.keyLockPool = keyLockPool;

        assertNotNull(cacheName);
        assertNotNull(cacheSize);
        assertNotNull(cacheGetter);
        assertNotNull(cacheLoader);
        assertNotNull(cachePutter);
        assertNotNull(expireTime);

        if(keyLockPool == null) {
            this.keyLockPool = new KeyLockPool(cacheSize, expireTime * 1000L);
        }
    }

    /**
     * 最简单的调用
     *
     * @param key
     * @return
     */
    public V getOnly(K key) {
        CacheRequest.CacheRequestBuilder<K, V> builder = CacheRequest.builder();
        CacheRequest<K, V> request = builder.key(key).build();
        try {
            return getOnly(request);
        } catch (InterruptedException exception) {
            return null;
        }
    }

    /**
     * 尽量暴露更多细节和可配置的的方法
     *
     * @param request
     * @return
     * @throws InterruptedException
     */
    public V getOnly(CacheRequest<K, V> request) throws InterruptedException {
        assertNotNull(request.getKey());
        return keyLockPool.doWithReadLock(loaderConcurrencyPolicyTimeoutOnKey, cacheGetter, request);
    }

    public V refresh(K key) {
        return refresh(key, null);
    }

    public V refresh(K key, Map<String, Object> requestParams) {
        CacheRequest.CacheRequestBuilder<K, V> requestBuilder = CacheRequest.builder();
        requestBuilder.key(key);
        if (requestParams != null) {
            requestBuilder.loaderParams(requestParams);
        }
        try {
            CacheResponse<K, V> response = refresh(requestBuilder.build());
            return response.getValue();
        } catch (InterruptedException exception) {
            return null;
        }
    }

    public CacheResponse<K, V> refresh(CacheRequest<K, V> request) throws InterruptedException {
        assertNotNull(request.getKey());
        return refreshWithCacheLock(request);
    }

    public V getWithLoader(K key) {
        return getWithLoader(key, null);
    }

    public V getWithLoader(K key, Map<String, Object> requestParams) {
        CacheRequest.CacheRequestBuilder<K, V> requestBuilder = CacheRequest.builder();
        requestBuilder.key(key);
        if (requestParams != null) {
            requestBuilder.loaderParams(requestParams);
        }
        try {
            CacheResponse<K, V> response = getWithLoader(requestBuilder.build());
            return response.getValue();
        } catch (InterruptedException exception) {
            return null;
        }
    }

    /**
     * 尽量暴露更多细节和可配置的getWithLoader
     *
     * @param request
     * @return
     * @throws InterruptedException
     */
    public CacheResponse<K, V> getWithLoader(CacheRequest<K, V> request) throws InterruptedException {
        assertNotNull(request.getKey());
        V v = keyLockPool.doWithReadLock(loaderConcurrencyPolicyTimeoutOnKey, cacheGetter, request);
        if (v != null) {
            CacheResponse.CacheResponseBuilder<K, V> responseBuilder = CacheResponse.builder();
            responseBuilder.cacheRequest(request);
            responseBuilder.value(v);
            return responseBuilder.build();
        }
        //加载获取
        return refresh(request);
    }


    /******************************************private****************************************************/

    /**
     * 控制单cache层面的并发
     *
     * @param request
     * @return
     */
    private CacheResponse<K, V> refreshWithCacheLock(CacheRequest<K, V> request) throws InterruptedException {
        //无限制
        if (loaderConcurrency == null) {
            return refreshWithKeyLock(request);
        }

        boolean getLock = loaderConcurrency.tryAcquire(loaderConcurrencyPolicyTimeout, TimeUnit.SECONDS);
        //没有获得锁
        if (!getLock) {
            throw new InterruptedException("cachelock");
        }
        //获得锁
        try {
            CacheResponse<K, V> response = refreshWithKeyLock(request);
            return response;
        } finally {
            //一定要释放信号量
            loaderConcurrency.release();
        }
    }

    /**
     * 控制单key层面的并发
     *
     * @param request
     * @return
     */
    private CacheResponse<K, V> refreshWithKeyLock(CacheRequest<K, V> request) throws InterruptedException {
        //允许并发加载，无限制
        if (isLoaderConcurrencyOnKey) {
            return refreshRaw(request);
        }
        return keyLockPool.doWithWriteLock(0, this::refreshRaw, request);
    }

    /**
     * 缓存刷新核心逻辑
     *
     * @param request
     * @return
     */
    private CacheResponse<K, V> refreshRaw(CacheRequest<K, V> request) {
        //获取实际的CacheLoader，request中的优先级最高
        Function<CacheRequest<K, V>, V> reqCacheLoader = request.getCacheLoader() == null ? cacheLoader : request.getCacheLoader();
        V value = reqCacheLoader.apply(request);

        //构造response
        CacheResponse.CacheResponseBuilder<K, V> responseBuilder = CacheResponse.builder();
        responseBuilder.cacheRequest(request);

        //处理期时间，request中的优先级最高
        long reqExpireTime = request.getExpireTime() == null ? expireTime : request.getExpireTime();
        //处理随机过期时间
        if (randomExpireTime > 0) {
            reqExpireTime = ThreadLocalRandom.current().nextLong(reqExpireTime - randomExpireTime, reqExpireTime + randomExpireTime);
        }
        //加载失败，无空值缓存
        if (value == null && !emptyElementCached) {
            return responseBuilder.build();
        }
        //加载失败，需要空值缓存
        if (value == null && emptyElementCached) {
            request.setValue(emptyElement);
            request.setExpireTime(reqExpireTime);
            cachePutter.accept(request);
            responseBuilder.value(emptyElement);
            responseBuilder.isEmptyElement(true);
            responseBuilder.isRefresh(true);
            return responseBuilder.build();
        }
        //加载成功
        request.setValue(value);
        request.setExpireTime(reqExpireTime);
        cachePutter.accept(request);
        responseBuilder.value(value);
        return responseBuilder.build();
    }

    private void assertNotNull(Object object) throws IllegalArgumentException {
        if(Objects.isNull(object)){
            throw new IllegalArgumentException();
        }
    }
}
