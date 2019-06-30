package com.ctrip.framework.apollo.spring.property;

import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.springframework.beans.factory.BeanFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @Value 注册中心，保存了他的 key/value 机构
 */
public class SpringValueRegistry {
    private static final long CLEAN_INTERVAL_IN_SECONDS = 5;
    private final Map<BeanFactory, Multimap<String, SpringValue>> registry = Maps.newConcurrentMap();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Object LOCK = new Object();

    public void register(BeanFactory beanFactory, String key, SpringValue springValue) {
        // 按照beanFactory来划分
        if (!registry.containsKey(beanFactory)) {
            // 即便是使用了concurrent map，这里还是上了锁，保证只有一个线程添加
            // 如果不加锁， 除了可能重复覆盖一个新new出来的map没别的了吧
            synchronized (LOCK) {
                if (!registry.containsKey(beanFactory)) {
                    // 使用synchronizedListMultimap同步的map
                    registry.put(beanFactory, Multimaps.synchronizedListMultimap(LinkedListMultimap.<String, SpringValue>create()));
                }
            }
        }

        // 这里put value就不加锁了
        registry.get(beanFactory).put(key, springValue);

        // lazy initialize
        if (initialized.compareAndSet(false, true)) {
            initialize();
        }
    }

    public Collection<SpringValue> get(BeanFactory beanFactory, String key) {
        Multimap<String, SpringValue> beanFactorySpringValues = registry.get(beanFactory);
        if (beanFactorySpringValues == null) {
            return null;
        }
        return beanFactorySpringValues.get(key);
    }

    private void initialize() {
        // 这里创建的是定时的任务
        // 5秒执行一次
        // 这里创建了线程池，这个不是单例对象，不会被回收吗
        Executors.newSingleThreadScheduledExecutor(ApolloThreadFactory.create("SpringValueRegistry", true)).scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            scanAndClean();
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                        }
                    }
                }, CLEAN_INTERVAL_IN_SECONDS, CLEAN_INTERVAL_IN_SECONDS, TimeUnit.SECONDS);
    }

    private void scanAndClean() {
        Iterator<Multimap<String, SpringValue>> iterator = registry.values().iterator();
        // 当前线程运行时
        while (!Thread.currentThread().isInterrupted() && iterator.hasNext()) {
            Multimap<String, SpringValue> springValues = iterator.next();
            Iterator<Entry<String, SpringValue>> springValueIterator = springValues.entries().iterator();
            while (springValueIterator.hasNext()) {
                Entry<String, SpringValue> springValue = springValueIterator.next();
                // 检查bean是否存活
                if (!springValue.getValue().isTargetBeanValid()) {
                    // 移除bean失效的配置项
                    // clear unused spring values
                    springValueIterator.remove();
                }
            }
        }
    }
}
