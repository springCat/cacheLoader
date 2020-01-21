package org.springcat.cacheLoader;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class CacheResponse<K,V> {

    //缓存的value
    private V value;

    //在计算随机时长后的实际过期时间，单位(秒)，暂时没想到有啥用，所以先取消传递
    //private Long expireTime;

    //是否刷新了cache
    private Boolean isRefresh;

    //是否是空值
    private Boolean isEmptyElement;

    //请求
    private CacheRequest<K,V> cacheRequest;
}
