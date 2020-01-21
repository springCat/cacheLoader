package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    //loader并发加载数,默认无限制
    private Semaphore loaderConcurrency;

    //loader并发加载数满后等待时长，默认等待5分钟,设置为0，则不等待立即返回
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeout = 5*60L;

    //单个key上是否并发加载
    @Builder.Default
    private boolean isLoaderConcurrencyOnKey = true;

    //单个key，并发加载数满后等待时长，默认等待5分钟,设置为0，则不等待立即返回
    @Builder.Default
    private Long loaderConcurrencyPolicyTimeoutOnKey = 5 * 60L;

    @Builder.Default
    private KeyLockPool<K> keyLockPool = new KeyLockPool<K>();

    public V getOnly(K key) {
        CacheRequest.CacheRequestBuilder<K,V> builder = CacheRequest.builder();
        CacheRequest<K, V> request = builder
                .key(key)
                .build();

        return getOnly(request);
    }

    public V getOnly(CacheRequest<K,V> request) {
        Optional<V> v = getWithKeyLock(request);
        if(v != null && v.isPresent()){
            return v.get();
        }
        return null;
    }

    public V refresh(K key){
        return refresh(key,null);
    }

    public V refresh(K key, Map<String,Object> requestParams){
        CacheRequest.CacheRequestBuilder<K, V> requestBuilder = CacheRequest.builder();
        requestBuilder.key(key);
         if(requestParams != null){
             requestBuilder.loaderParams(requestParams);
         }
        CacheResponse<K, V> response = refresh(requestBuilder.build());
        return response.getValue();
    }

    public CacheResponse<K,V> refresh(CacheRequest<K,V> request) {
        return refreshWithCacheLock(request);
    }

    public V getWithLoader(K key){
        return getWithLoader(key,null);
    }

    public V getWithLoader(K key, Map<String,Object> requestParams){
        CacheRequest.CacheRequestBuilder<K,V> requestBuilder = CacheRequest.builder();
        requestBuilder.key(key);
        if(requestParams != null){
            requestBuilder.loaderParams(requestParams);
        }
        CacheResponse<K, V> response = getWithLoader( requestBuilder.build());
        return response.getValue();
    }

    public CacheResponse<K,V> getWithLoader(CacheRequest<K,V> request){
        Optional<V> cacheValueOptional = getWithKeyLock(request);
        if(cacheValueOptional != null){
            if(cacheValueOptional.isPresent()) {
                CacheResponse.CacheResponseBuilder<K, V> responseBuilder = CacheResponse.builder();
                responseBuilder.cacheRequest(request);
                responseBuilder.value(cacheValueOptional.get());
                return responseBuilder.build();
            }else {
                //特殊处理，在开启key上并发控制时，没有获得锁的请求，或者等待超时的情况时，直接返回null
                return null;
            }
        }
        return refresh(request);
    }

    /******************************************private****************************************************/
    /**
     * 控制单cache层面的并发
     * @param request
     * @return
     */
    @SneakyThrows
    private CacheResponse<K,V> refreshWithCacheLock(CacheRequest<K,V> request) {
        //无限制
        if(loaderConcurrency == null){
            return refreshWithKeyLock(request);
        }
        boolean getLock = loaderConcurrency.tryAcquire(loaderConcurrencyPolicyTimeout, TimeUnit.SECONDS);
        //没有获得锁
        if(!getLock) {
            return null;
        }
        //获得锁
        try {
            CacheResponse<K, V> response = refreshWithKeyLock(request);
            return response;
        }finally{
            //一定要释放信号量
            loaderConcurrency.release();
        }
    }

    /**
     * 控制单key层面的并发
     * @param request
     * @return
     */
    @SneakyThrows
    private CacheResponse<K,V> refreshWithKeyLock(CacheRequest<K,V> request) {
        //允许并发加载，无限制
        if(isLoaderConcurrencyOnKey){
            return refreshRaw(request);
        }
        ReentrantReadWriteLock.WriteLock writeLock = keyLockPool.getWriteLock(request.getKey());
        boolean getLock = writeLock.tryLock(loaderConcurrencyPolicyTimeoutOnKey, TimeUnit.SECONDS);
        //没有获得锁就直接退出，说明有其他loader在加载了
        if(!getLock) {
            return null;
        }
        try {
            CacheResponse<K, V> response = refreshRaw(request);
            return response;
        }finally{
            //一定要释放信号量
            writeLock.unlock();
        }
    }

    /**
     * 缓存刷新核心逻辑
     * @param request
     * @return
     */
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

    @SneakyThrows
    private Optional<V> getWithKeyLock(CacheRequest<K,V> request) {
        V apply = getRaw(request);
        if(apply != null){
            return Optional.of(apply);
        }
        //允许并发加载，无限制
        if(isLoaderConcurrencyOnKey){
            return null;
        }

        ReentrantReadWriteLock.ReadLock readLock = keyLockPool.getReadLock(request.getKey());
        boolean getLock = readLock.tryLock(getLoaderConcurrencyPolicyTimeoutOnKey(), TimeUnit.SECONDS);
        //没有获得锁就退出，Optional.empty()表示后续无需再进行缓存刷新
        if(!getLock) {
            return Optional.empty();
        }
        try {
            V value = getRaw(request);
            return value == null ? null : Optional.of(value);
        }finally{
            //一定要释放信号量
            readLock.unlock();
        }
    }

    private V getRaw(CacheRequest<K,V> request) {
        return cacheGetter.apply(request);
    }
}
