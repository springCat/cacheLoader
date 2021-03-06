# cacheLoader
---------------------------------------------

现在使用的缓存技术很多，比如*Redis*、 *Memcache* 、 *EhCache*等，甚至还有使用*ConcurrentHashMap* 或 *HashTable* 来实现缓存。但在缓存的使用上，每个人都有自己的实现方式。目前有2种困境

1. spring和大部分开源的想法都是根据aop+annotation，来实现自动缓存。但是实际，通过注解实现只是把java代码换个地方写，代码中过多的注解让代码的可读性大大折扣，而且并不灵活。
2. 直接在代码中写缓存操作和数据加载到缓存的操作，又往往每个人的写法并不一样，通用的功能到处拷贝代码


### 设计思想及原理

*  参考guava Cache的loadingCache的方式，简单封装常用的获取数据的逻辑
*  充分利java的lamda表达式，让代码灵活并和具体的cache操作充分解耦
*  封装一些常用的功能，解决代码重复编写的问题，让代码更清晰易懂。


### cacheLoader获取数据到流程：
从缓存获取数据 -> 从数据源加载数据 -> 加载数据到缓存 

### cacheLoader目前实现的功能：
1. 支持任意的缓存
2. 支持空值缓存，空值缓存自定义值和时间
3. 加载并发数控制以及到达设定的数量值后的策略，比如等待或者抛弃,控制粒度可以是cache级别，也可是key级别
4. 支持设定过期时间的随机变化和变化范围，防止缓存集中过期造成系统突刺和雪崩
5. 灵活的操作方式，充分利java的lamda表达式，支持用户自定义缓存的获取方法，数据方法，缓存的加载方法，特别针对redis这种数据结构比较多样和灵活的缓存，更加的适合
6. 独立于缓存key之外，灵活的额外参数传入，和方法级别的缓存加载，缓存时间操作
7. 简单清晰的源码，基本没有学习成本
8. cache context，其中参数可以从最初到最后一路传递
9. 全局线程池和异步的缓存加载
10. key生成器（在生成loadingcache实例时传入，然后调用request中的getGenKey获取生成的key）

### cacheLoader待实现的功能：



### 使用方法：
```java
LoadingCache.LoadingCacheBuilder<String,Object> builder = LoadingCache.builder();

        LoadingCache<String,Object> loadingCache = builder
                .cacheName("testCache")
                .cacheGetter(request -> {
                    System.out.println("cacheGetter here request:"+request);
                    request.getAttributes().put("cacheGetter", "cacheGetter");
                    return {cachevalue};
                })
                .cacheLoader(request -> {
                    System.out.println("cacheLoader here request:"+request);
                    request.getAttributes().put("cacheLoader", "cacheLoader");
                    return {cachevalue};
                })
                .cachePutter(request -> {
                    System.out.println("cachePutter here request:"+request);;
                })
                .keyGenerator(s -> {
                    return "prefix"+s.getKey()+"suffix";
                })
                .randomExpireTime(5L)
                .expireTime(60L)
                .emptyElementCached(true)
                .emptyElement("empty")
                .emptyElementExpireTime(5L)
                .loaderConcurrency(new Semaphore(2))
                .build();
                
 String cacheValue = (String) loadingCache.getWithLoader("cacheKey");                
```
例如结合redisTemplate操作如下：

```java
    @Autowired
        private RedisTemplate<String, Object> redisTemplate;
    
        @Test
        public void test() {
            LoadingCache.LoadingCacheBuilder<String,Object> builder = LoadingCache.builder();
    
            LoadingCache<String,Object> loadingCache = builder
                    .cacheName("testCache")
                    .cacheGetter(request -> {
                        Object o = redisTemplate.opsForValue().get(request.getGenKey());
                        request.getAttributes().put("cacheGetter", "cacheGetter");
                        return o;
                    })
                    .cacheLoader(request -> {
                        request.getAttributes().put("cacheLoader", "cacheLoader");
                        //睡5秒
                        //get from db
                        System.out.println("get from db");
                        sleep(2000L);
                        return request.getGenKey()+" value";
                    })
                    .cachePutter(request -> {
                        redisTemplate.opsForValue().set(request.getGenKey(),request.getValue(),request.getExpireTime());
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
```
