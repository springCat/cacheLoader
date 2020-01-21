package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import java.util.Map;
import java.util.Objects;
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
    private Long loaderConcurrencyPolicyTimeoutOnKey = 5 * 60L;

    enum LoaderConcurrencyPolicy {
        WaitLoaderConcurrencyPolicy(1),
        RejectLoaderConcurrencyPolicy(2);
        LoaderConcurrencyPolicy(int type) {
            this.type = type;
        }
        private int type;
    }

    //定义操作组合
    enum OpsType {
        //读取缓存
        READ(1),
        //执行loader
        LOAD(2),
        //读取缓存，执行loader
        READ_LOAD(3),
        //操作缓存
        WRITE(4),
        //读取缓存，加载缓存
        READ_WRITE(5),
        //加载loader，加载缓存
        LOAD_WRITE(6),
        //读取缓存，执行loader，加载缓存
        READ_LOAD_WRITE(7);
        OpsType(int type) {
            this.type = type;
        }
        private int type;
    }

    //用于控制单个key上单并发,利用ConcurrentHashMap，后续考虑切换到lruCache，目前的缓存存在被撑爆的风险
    @Builder.Default
    private Map<String, ReentrantReadWriteLock> keyLockPool = new ConcurrentHashMap<String, ReentrantReadWriteLock>();


    public V get(K key) {
        CacheRequest.CacheRequestBuilder<K,V> builder = CacheRequest.builder();
        CacheRequest<K, V> request = builder
                .key(key)
                .build();
        Optional<V> v = get(request);
        if(v != null && v.isPresent()){
            return v.get();
        }
        return null;
    }

    @SneakyThrows
    public Optional<V> get(CacheRequest<K,V> request) {

        V apply = cacheGetter.apply(request);
        if(apply != null){
            return Optional.of(apply);
        }

        //允许并发加载，无限制
        if(isLoaderConcurrencyOnKey){
            return null;
        }

        ReentrantReadWriteLock reentrantLock = keyLockPool.computeIfAbsent(Objects.toString(request.getKey()), cacheName -> new ReentrantReadWriteLock());
        ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

        long reqLoaderConcurrencyPolicyTimeoutOnKey = 0;
        if (loaderConcurrencyPolicy == LoaderConcurrencyPolicy.WaitLoaderConcurrencyPolicy) {
            reqLoaderConcurrencyPolicyTimeoutOnKey = loaderConcurrencyPolicyTimeoutOnKey;
        }
        if(loaderConcurrencyPolicy == LoaderConcurrencyPolicy.RejectLoaderConcurrencyPolicy){
            reqLoaderConcurrencyPolicyTimeoutOnKey = 0;
        }

        boolean getLock = readLock.tryLock(reqLoaderConcurrencyPolicyTimeoutOnKey, TimeUnit.SECONDS);
        //没有获得锁就退出，Optional.empty()表示后续无需再进行缓存刷新
        if(!getLock) {
            return Optional.empty();
        }
        try {
            V value = cacheGetter.apply(request);
            return value == null ? null : Optional.of(value);
        }finally{
            //一定要释放信号量
            readLock.unlock();
        }
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

    /**
     * 控制单cache层面的并发
     * @param request
     * @return
     */
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

    /**
     * 控制单key层面的并发
     * @param request
     * @return
     */
    @SneakyThrows
    public CacheResponse<K,V> refreshOnKey(CacheRequest<K,V> request) {
        //允许并发加载，无限制
        if(isLoaderConcurrencyOnKey){
            return refreshRaw(request);
        }
        ReentrantReadWriteLock reentrantLock = keyLockPool.computeIfAbsent(Objects.toString(request.getKey()), cacheName -> new ReentrantReadWriteLock());
        ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();

        boolean getLock = writeLock.tryLock();
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
        Optional<V> cacheValueOptional = get(request);
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
}
