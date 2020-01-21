package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

@Builder
@Data
public class LoadingCache<K, V> {

    //缓存名称
    private String cacheName;

    //查询缓存操作
    private Function<CacheRequest<K,V>,V> cacheGetter;

    //加载数据操作
    private Function<CacheRequest<K,V>,V> cacheLoader;

    //加载缓存操作
    private Consumer<CacheRequest<K,V>> cachePutter;

    //时间默认单位秒
    private long expireTime;

    //是否启动空值缓存
    private boolean emptyElementCached;

    //空值缓存的值
    private V emptyElement;

    //空值缓存的过期时间
    private long emptyElementExpireTime;

    //随机过期时间，在expireTime的基础上随机增加或者减少randomExpireTime秒，防止缓存集中过期造成系统突刺
    private long randomExpireTime;

    //loader并发加载数  默认无限制
    private Semaphore loaderConcurrency;

    // loader并发加载数满后的策略
    @Builder.Default
    private LoaderConcurrencyPolicy loaderConcurrencyPolicy = LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy;

    //等待策略时到等待时长，默认等待5分钟
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeout = 5*60L;

    //单个key上是否并发加载
    @Builder.Default
    private boolean isLoaderConcurrencyOnKey = true;

    //单个key上是否并发到限制后，加载策略
    @Builder.Default
    private LoaderConcurrencyPolicy loaderConcurrencyPolicyOnKey = LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy;

    //单个key，等待策略时到等待时长，默认等待5分钟
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeoutOnKey = 5*60L;

    enum LoaderConcurrencyPolicy {
        WaitLoaderConcurrencyPolicy(1),
        RejectLoaderConcurrencyPolicy(2);
        LoaderConcurrencyPolicy(int type) {
            this.type = type;
        }
        private int type;
    }

    //用于控制单个key上单并发,利用ConcurrentHashMap，后续考虑切换到lruCache，目前的缓存存在被撑爆的风险
    @Builder.Default
    private Map<String, ReentrantLock> keyLockPool = new ConcurrentHashMap<String, ReentrantLock>();

    public V get(K key) {
        CacheRequest.CacheRequestBuilder<K,V> builder = CacheRequest.builder();
        CacheRequest<K, V> request = builder.key(key).build();
        return  cacheGetter.apply(request);
    }

    public V get(CacheRequest<K,V> request) {
        return cacheGetter.apply(request);
    }

    public V refresh(K key){
        return refresh(key,null);
    }

    public V refresh(K key, Map<String,Object> requestParams){
        CacheRequest.CacheRequestBuilder<K, V> requestBuilder = CacheRequest.builder();
        requestBuilder
                .key(key);
         if(requestParams != null){
             requestBuilder.requestParams(requestParams);
         }
        CacheResponse<K, V> response = refresh(requestBuilder.build());
        return response.getValue();
    }

    public CacheResponse<K,V> refresh(CacheRequest<K,V> request) {
        return refreshCache(request);
    }

    @SneakyThrows
    public CacheResponse<K,V> refreshCache(CacheRequest<K,V> request) {
        //无限制
        if(loaderConcurrency == null){
            return refreshOnKey(request);
        }

        //等待策略，需要等待线程
        long reqLoaderConcurrencyPolicyTimeout = 0;
        if (loaderConcurrencyPolicy == LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy) {
            reqLoaderConcurrencyPolicyTimeout =loaderConcurrencyPolicyTimeout;
        }
        //拒绝策略，需要获得信号量
        else if(loaderConcurrencyPolicy == LoaderConcurrencyPolicy.RejectLoaderConcurrencyPolicy){
            reqLoaderConcurrencyPolicyTimeout = 0;
        }
       boolean getLock = loaderConcurrency.tryAcquire(reqLoaderConcurrencyPolicyTimeout, TimeUnit.SECONDS);
        //没有获得锁
        if(!getLock) {
            return null;
        }
        //获得锁
        try {
            CacheResponse<K, V> response = refreshOnKey(request);
            return response;
        }finally{
            //一定要释放信号量
            loaderConcurrency.release();
        }
    }

    @SneakyThrows
    public CacheResponse<K,V> refreshOnKey(CacheRequest<K,V> request) {
        //允许并发加载，无限制
        if(isLoaderConcurrencyOnKey){
            return refreshRaw(request);
        }
        ReentrantLock reentrantLock = keyLockPool.computeIfAbsent(cacheName, cacheName -> new ReentrantLock());
        long reqLoaderConcurrencyPolicyTimeoutOnKey = 0;
        if (loaderConcurrencyPolicy == LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy) {
            reqLoaderConcurrencyPolicyTimeoutOnKey = loaderConcurrencyPolicyTimeoutOnKey;
        }
        if(loaderConcurrencyPolicy == LoaderConcurrencyPolicy.RejectLoaderConcurrencyPolicy){
            reqLoaderConcurrencyPolicyTimeoutOnKey = 0;
        }
        boolean getLock = reentrantLock.tryLock(reqLoaderConcurrencyPolicyTimeoutOnKey, TimeUnit.SECONDS);
        //没有获得锁就推出
        if(!getLock) {
            return null;
        }
        try {
            CacheResponse<K, V> response = refreshRaw(request);
            return response;
        }finally{
            //一定要释放信号量
            reentrantLock.unlock();
        }
    }

    private CacheResponse<K,V> refreshRaw(CacheRequest<K,V> request){
        //获取实际的CacheLoader，request中的优先级最高
        Function<CacheRequest<K,V>,  V> reqCacheLoader = request.getCacheLoader() == null ? cacheLoader : request.getCacheLoader();
        V value = reqCacheLoader.apply(request);

        //构造response
        CacheResponse.CacheResponseBuilder<K,V> responseBuilder = CacheResponse.builder();
        responseBuilder.cacheRequest(request);

        //处理期时间，request中的优先级最高
        long reqExpireTime =  request.getExpireTime() == null ? expireTime:request.getExpireTime();
        //处理随机过期时间
        if(randomExpireTime > 0) {
            reqExpireTime = ThreadLocalRandom.current().nextLong(reqExpireTime - randomExpireTime, reqExpireTime + randomExpireTime);
        }

        //加载失败，无空值缓存
        if(value == null && !emptyElementCached){
            return responseBuilder.build();
        }
        //加载失败，需要空值缓存
        if(value == null && emptyElementCached) {
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

    public V getWithLoader(K key){
        return getWithLoader(key,null);
    }

    public V getWithLoader(K key, Map<String,Object> requestParams){
        CacheRequest.CacheRequestBuilder<K,V> requestBuilder = CacheRequest.builder();
        requestBuilder.key(key);
        if(requestParams != null){
            requestBuilder.requestParams(requestParams);
        }
        CacheResponse<K, V> response = getWithLoader( requestBuilder.build());
        return response.getValue();
    }

    public CacheResponse<K,V> getWithLoader(CacheRequest<K,V> request){
        V cacheValue = get(request);
        if(cacheValue != null){
            CacheResponse.CacheResponseBuilder<K,V> responseBuilder = CacheResponse.builder();
            responseBuilder.cacheRequest(request);
            responseBuilder.value(cacheValue);
            return responseBuilder.build();
        }
        return refresh(request);
    }
}
