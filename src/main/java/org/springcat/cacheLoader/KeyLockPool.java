package org.springcat.cacheLoader;

import lombok.SneakyThrows;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 *    暂时利用ConcurrentHashMap，后续考虑切换到lruCache，目前的缓存被恶意刷时被撑爆的风险
 * @param <K>
 */
public class KeyLockPool<K,V> {

    private Map<K, ReentrantReadWriteLock> keyLockPool = new ConcurrentHashMap<K, ReentrantReadWriteLock>();

    public ReentrantReadWriteLock.ReadLock getReadLock(K lockName){
        ReentrantReadWriteLock lock = keyLockPool.computeIfAbsent(lockName, cacheName -> new ReentrantReadWriteLock());
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
        ReentrantReadWriteLock lock = keyLockPool.computeIfAbsent(lockName, cacheName -> new ReentrantReadWriteLock());
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
