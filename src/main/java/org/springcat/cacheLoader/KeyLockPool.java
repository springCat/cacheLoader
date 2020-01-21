package org.springcat.cacheLoader;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LRUCache;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;


public class KeyLockPool<K,V> {

    private LRUCache<K, ReentrantReadWriteLock> keyLockPool;

    /**
     *
     * @param capacity 容量
     * @param timeout 过期时长，单位：毫秒
     */
    public KeyLockPool(int capacity, long timeout){
        keyLockPool = CacheUtil.newLRUCache(capacity,timeout);
    }

    public ReentrantReadWriteLock.ReadLock getReadLock(K lockName){
        ReentrantReadWriteLock lock = keyLockPool.get(lockName, ReentrantReadWriteLock::new);
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        return readLock;
    }

    public V doWithReadLock(long timeout, Function<CacheRequest<K,V>,V> function,CacheRequest<K,V> request) throws InterruptedException{
        ReentrantReadWriteLock.ReadLock readLock = getReadLock(request.getKey());
        boolean getLock = readLock.tryLock(timeout, TimeUnit.SECONDS);
        //没有获得锁就退出，Optional.empty()表示后续无需再进行缓存刷新
        if(!getLock) {
            throw new InterruptedException("keylock");
        }
        try {
            return function.apply(request);
        }finally{
            //一定要释放信号量
            readLock.unlock();
        }
    }

    public ReentrantReadWriteLock.WriteLock getWriteLock(K lockName){
        ReentrantReadWriteLock lock = keyLockPool.get(lockName, ReentrantReadWriteLock::new);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        return writeLock;
    }

    public CacheResponse<K,V> doWithWriteLock(long timeout,Function<CacheRequest<K,V>,CacheResponse<K,V>> function,CacheRequest<K,V> request) throws InterruptedException{
        ReentrantReadWriteLock.WriteLock writeLock = getWriteLock(request.getKey());
        boolean getLock = writeLock.tryLock(timeout, TimeUnit.SECONDS);
        //没有获得锁就退出，Optional.empty()表示后续无需再进行缓存刷新
        if(!getLock) {
            throw new InterruptedException("keylock");
        }
        try {
            return function.apply(request);
        }finally{
            //一定要释放信号量
            writeLock.unlock();
        }
    }
}
