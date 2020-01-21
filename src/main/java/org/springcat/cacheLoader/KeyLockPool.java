package org.springcat.cacheLoader;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class KeyLockPool<K> {

    private Map<K, ReentrantReadWriteLock> keyLockPool = new ConcurrentHashMap<K, ReentrantReadWriteLock>();

    public ReentrantReadWriteLock.ReadLock  getReadLock(K lockName){
        ReentrantReadWriteLock lock = keyLockPool.computeIfAbsent(lockName, cacheName -> new ReentrantReadWriteLock());
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        return readLock;
    }

    public ReentrantReadWriteLock.WriteLock getWriteLock(K lockName){
        ReentrantReadWriteLock lock = keyLockPool.computeIfAbsent(lockName, cacheName -> new ReentrantReadWriteLock());
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        return writeLock;
    }
}
