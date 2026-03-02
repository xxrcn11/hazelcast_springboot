package com.bt.hz.bootstrap;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring의 ApplicationContext를 보관하여 Hazelcast 등 Spring 영역 밖에서도 
 * Bean에 접근할 수 있도록 도와주는 유틸리티 클래스입니다.
 */
@Component
public class SpringContext implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        SpringContext.applicationContext = context;
    }

    public static void init(ApplicationContext context) {
        SpringContext.applicationContext = context;
    }

    public static void remove() {
        SpringContext.applicationContext = null;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (applicationContext != null) {
            return applicationContext.getBean(beanClass);
        }
        return null;
    }
}
