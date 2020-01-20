package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.apachecommons.CommonsLog;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Builder
@Data
@CommonsLog
public class LoadingCache<K, V> {

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

    //loader并发加载数  默认0，无限制
    private Semaphore loaderConcurrency;

    // loader并发加载数满后的策略
    @Builder.Default
    private LoaderConcurrencyPolicy loaderConcurrencyPolicy = LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy;

    enum LoaderConcurrencyPolicy {
        WaitLoaderConcurrencyPolicy(1),RejectLoaderConcurrencyPolicy(2);
        LoaderConcurrencyPolicy(int type) {
            this.type = type;
        }
        private int type;
    }

    public V get(K key) {
        CacheRequest.CacheRequestBuilder<K,V> builder = CacheRequest.builder();
        CacheRequest<K, V> request = builder.key(key).build();
        V cacheValue = cacheGetter.apply(request);
        return cacheValue;
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
        return response.getCacheValue();
    }

    @SneakyThrows
    public CacheResponse<K,V> refresh(CacheRequest<K,V> request) {
        //无限制
        if(loaderConcurrency == null){
            return refreshSingle(request);
        }
        //等待策略，需要等待线程
        if (loaderConcurrencyPolicy == LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy) {
            loaderConcurrency.acquire();
        //拒绝策略，需要获得信号量
        }else if(loaderConcurrencyPolicy == LoaderConcurrencyPolicy.RejectLoaderConcurrencyPolicy && loaderConcurrency.tryAcquire()){

        //拒绝策略，获得不到信号量，直接放弃返回
        }else {
            return null;
        }
        //具体执行
        try {
            CacheResponse<K, V> response = refreshSingle(request);
            return response;
        }catch (Exception exception){
            log.error(exception);
            return null;
        } finally{
            //一定要释放信号量
            loaderConcurrency.release();
        }
    }

    private CacheResponse<K,V> refreshSingle(CacheRequest<K,V> request){
        //获取实际的CacheLoader，request中的优先级最高
        Function<CacheRequest<K,V>,  V> reqCacheLoader = request.getCacheLoader() == null ? cacheLoader : request.getCacheLoader();
        V cacheValue = reqCacheLoader.apply(request);

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
        if(cacheValue == null && !emptyElementCached){
            return responseBuilder.build();
        }
        //加载失败，需要空值缓存
        if(cacheValue == null && emptyElementCached) {
            request.setCacheValue(emptyElement);
            request.setExpireTime(reqExpireTime);
            cachePutter.accept(request);
            responseBuilder.cacheValue(emptyElement);
            responseBuilder.isEmptyElement(true);
            responseBuilder.isRefresh(true);
            return responseBuilder.build();
        }
        //加载成功
        request.setCacheValue(cacheValue);
        request.setExpireTime(reqExpireTime);
        cachePutter.accept(request);
        responseBuilder.cacheValue(cacheValue);
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
        return response.getCacheValue();
    }

    public CacheResponse<K,V> getWithLoader(CacheRequest<K,V> request){
        V cacheValue = get(request);
        if(cacheValue != null){
            CacheResponse.CacheResponseBuilder<K,V> responseBuilder = CacheResponse.builder();
            responseBuilder.cacheRequest(request);
            responseBuilder.cacheValue(cacheValue);
            return responseBuilder.build();
        }
        return refresh(request);
    }
}
