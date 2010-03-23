/*
 * Copyright 2009-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License i distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.paoding.rose.jade.jadeinterface.app;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * 如果您所写的程序在Spring容器之外，不能用 {@link Autowired} 注入一个Jade DAO，此时可使用
 * {@link JadeFactory} 来使用Jade DAO。
 * <p>
 * 
 * String confLocation = "classpath*:/applicationContext*.xml";<br>
 * JadeFactory jade = new JadeFactory(confLocation);<br>
 * UserDAO userDAO = jade.getDao(UserDAO.class);
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 * @author 廖涵 [in355hz@gmail.com]
 */
public class JadeFactory {

    private ApplicationContext applicationContext;

    public JadeFactory(ApplicationContext applicationContext) {
        assert applicationContext != null;
        this.applicationContext = applicationContext;
    }

    public JadeFactory(String... applicationContextLocations) {
        assert applicationContextLocations.length != 0;
        this.applicationContext = new ClassPathXmlApplicationContext(applicationContextLocations);
    }

    @SuppressWarnings("unchecked")
    public <T> T getDao(Class<T> daoClass) {
        return (T) BeanFactoryUtils.beanOfType(applicationContext, daoClass);
    }

}
