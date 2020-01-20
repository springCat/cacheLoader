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
    private V cacheValue;

    //过期时间，单位(秒)
    private Long expireTime;

    //请求参数，额外的参数，供loader使用
    private Map<String,Object> requestParams;

    //缓存loader，如果有值则本次请求覆盖掉LoadingCache中的cacheLoader
    private Function<CacheRequest<K,V>, V> cacheLoader;

    //返回参数，存储，loader中一些需要返回的参数
    @Builder.Default
    private Map<String,Object> attributes = new HashMap<String,Object>();

}