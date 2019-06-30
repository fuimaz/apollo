package com.ctrip.framework.apollo.spring.property;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.spring.util.SpringInjector;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BeanDefinitionRegistryPostProcessor继承自BeanFactoryPostProcessor，是一种比较特殊的BeanFactoryPostProcessor。
 * BeanDefinitionRegistryPostProcessor中定义的postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)方法
 * 可以让我们实现自定义的注册bean定义的逻辑。
 * To process xml config placeholders, e.g.
 *
 * <pre>
 *  &lt;bean class=&quot;com.ctrip.framework.apollo.demo.spring.xmlConfigDemo.bean.XmlBean&quot;&gt;
 *    &lt;property name=&quot;timeout&quot; value=&quot;${timeout:200}&quot;/&gt;
 *    &lt;property name=&quot;batch&quot; value=&quot;${batch:100}&quot;/&gt;
 *  &lt;/bean&gt;
 * </pre>
 */
public class SpringValueDefinitionProcessor implements BeanDefinitionRegistryPostProcessor {
    // BeanDefinitionRegistry其实跟bean关联
    // 这里的map相当于一个bean关联一个Multimap
    private static final Map<BeanDefinitionRegistry, Multimap<String, SpringValueDefinition>> beanName2SpringValueDefinitions =
            Maps.newConcurrentMap();
    private static final Set<BeanDefinitionRegistry> PROPERTY_VALUES_PROCESSED_BEAN_FACTORIES = Sets.newConcurrentHashSet();

    private final ConfigUtil configUtil;
    private final PlaceholderHelper placeholderHelper;

    public SpringValueDefinitionProcessor() {
        configUtil = ApolloInjector.getInstance(ConfigUtil.class);
        placeholderHelper = SpringInjector.getInstance(PlaceholderHelper.class);
    }

    public static Multimap<String, SpringValueDefinition> getBeanName2SpringValueDefinitions(BeanDefinitionRegistry registry) {
        Multimap<String, SpringValueDefinition> springValueDefinitions = beanName2SpringValueDefinitions.get(registry);
        if (springValueDefinitions == null) {
            springValueDefinitions = LinkedListMultimap.create();
        }

        return springValueDefinitions;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (configUtil.isAutoUpdateInjectedSpringPropertiesEnabled()) {
            processPropertyValues(registry);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    private void processPropertyValues(BeanDefinitionRegistry beanRegistry) {
        // add方法还可判断添加对象是否已存在
        if (!PROPERTY_VALUES_PROCESSED_BEAN_FACTORIES.add(beanRegistry)) {
            // already initialized
            return;
        }

        if (!beanName2SpringValueDefinitions.containsKey(beanRegistry)) {
            beanName2SpringValueDefinitions.put(beanRegistry, LinkedListMultimap.<String, SpringValueDefinition>create());
        }


        Multimap<String, SpringValueDefinition> springValueDefinitions = beanName2SpringValueDefinitions.get(beanRegistry);

        // 获取了所有的bean名称列表
        String[] beanNames = beanRegistry.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            BeanDefinition beanDefinition = beanRegistry.getBeanDefinition(beanName);
            // 获取这个bean上的配置项
            MutablePropertyValues mutablePropertyValues = beanDefinition.getPropertyValues();

            List<PropertyValue> propertyValues = mutablePropertyValues.getPropertyValueList();
            for (PropertyValue propertyValue : propertyValues) {
                Object value = propertyValue.getValue();
                // 这里只接受string的value
                if (!(value instanceof TypedStringValue)) {
                    continue;
                }
                String placeholder = ((TypedStringValue) value).getValue();
                // 解析key，可能有嵌套的key
                Set<String> keys = placeholderHelper.extractPlaceholderKeys(placeholder);

                if (keys.isEmpty()) {
                    continue;
                }

                for (String key : keys) {
                    // 三个参数，第二个是value
                    springValueDefinitions.put(beanName, new SpringValueDefinition(key, placeholder, propertyValue.getName()));
                }
            }
        }
    }
}
