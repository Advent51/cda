/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cda;

import java.io.File;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;


public class CoreBeanFactory implements ICdaBeanFactory {

  
private static final Log logger = LogFactory.getLog(CoreBeanFactory.class);  
  
  protected static ConfigurableApplicationContext context;
  
  protected ConfigurableApplicationContext getSpringBeanFactory() {

    final ClassLoader cl = this.getClass().getClassLoader();
		File f = new File("cda.spring.xml"); //$NON-NLS-1$
		if (f.exists()) {
			logger.debug("Found spring file @ " + f.getAbsolutePath()); //$NON-NLS-1$
			ConfigurableApplicationContext context = new FileSystemXmlApplicationContext(
					"file:" + f.getAbsolutePath()) { //$NON-NLS-1$
				@Override
				protected void initBeanDefinitionReader(
						XmlBeanDefinitionReader beanDefinitionReader) {

					beanDefinitionReader.setBeanClassLoader(cl);
				}

				@Override
				protected void prepareBeanFactory(
						ConfigurableListableBeanFactory clBeanFactory) {
					super.prepareBeanFactory(clBeanFactory);
					clBeanFactory.setBeanClassLoader(cl);
				}

				/**
				 * Critically important to override this and return the desired
				 * CL
				 **/
				@Override
				public ClassLoader getClassLoader() {
					return cl;
				}
			};
			return context;
		}

    logger.fatal("Spring definition file does not exist. There should be a cda.spring.xml file at " + f.getAbsolutePath());
    return null;
        
  }
  
  @Override
  public Object getBean(String id) {
    synchronized(CoreBeanFactory.class) {
      if (context == null)
        context = getSpringBeanFactory();
    }
    return context.getBean(id);
  }
  
}
