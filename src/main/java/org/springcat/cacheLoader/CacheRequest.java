package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Builder
@Data
public class CacheRequest<K,V> {

    //缓存key
    private K key;

    //缓存value
    private V value;

    //过期时间，单位(秒)
    private Long expireTime;

    /**
     * todo 后续重构代码，让各个操作更灵活
     * 缓存操作等级
     *  ReadCache(1),                    只读取缓存
     *  ReadCacheAndLoad(3),             读取缓存和执行loader
     *  ReadCacheAndLoadAndPutCache(7);  读取缓存，执行loader，加载缓存
     */
    //private Integer opsLevel;

    //请求参数，额外的参数，供loader使用
    private Map<String,Object> requestParams;

    //缓存loader，如果有值则本次请求覆盖掉LoadingCache中的cacheLoader
    private Function<CacheRequest<K,V>, V> cacheLoader;

    //返回参数，存储，loader中一些需要返回的参数
    @Builder.Default
    private Map<String,Object> attributes = new HashMap<String,Object>();

}