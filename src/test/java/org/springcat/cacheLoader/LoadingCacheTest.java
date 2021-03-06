package org.springcat.cacheLoader;


import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public class LoadingCacheTest {

    @Test
    public void test() {
        LoadingCache.LoadingCacheBuilder<String,Object> builder = LoadingCache.builder();

        LoadingCache<String,Object> loadingCache = builder
                .cacheName("testCache")
                .cacheGetter(request -> {
                    System.out.println("cacheGetter request:"+request);
                    request.getAttributes().put("cacheGetter", "cacheGetter");
                    return null;
                })
                .cacheLoader(request -> {
                    System.out.println("cacheLoader request:"+request);
                    request.getAttributes().put("cacheLoader", "cacheLoader");
                    //睡5秒
                    sleep(2000L);
                    return request.getGenKey();
                })
                .cachePutter(request -> {
                    System.out.println("cachePutter request:"+request);;
                })
                .randomExpireTime(5L)
                .expireTime(60L)
                .emptyElementCached(false)
                .emptyElement("empty")
                .emptyElementExpireTime(5L)
                .loaderConcurrency(new Semaphore(2))
                .keyGenerator(request -> {
                    return "prefix"+request.getKey()+"suffix";
                })
                .build();

        ArrayList< Future<Object>> objects = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Future<Object> test = loadingCache.getWithLoaderAsync("test");
            objects.add(test);
        }

        objects.forEach(e -> {
            try {
                Object o = e.get();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } catch (ExecutionException ex) {
                ex.printStackTrace();
            }
        });

        String test = (String) loadingCache.getWithLoader("test");
        System.out.println(test);
        sleep(10000L);
    }


    private static Runnable execAsync(final Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(false);
        thread.start();
        return runnable;
    }

    private static boolean sleep(Number millis) {
        if (millis == null) {
            return true;
        }
        try {
            Thread.sleep(millis.longValue());
        } catch (InterruptedException e) {
            return false;
        }
        return true;
    }
}
