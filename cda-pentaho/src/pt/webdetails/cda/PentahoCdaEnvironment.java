/*!
* Copyright 2002 - 2013 Webdetails, a Pentaho company.  All rights reserved.
* 
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package pt.webdetails.cda;

import java.util.Locale;
import java.util.TimeZone;

import org.pentaho.util.messages.LocaleHelper;

import org.pentaho.reporting.engine.classic.core.DataFactory;
import org.pentaho.reporting.engine.classic.core.ReportDataFactoryException;
import org.pentaho.reporting.engine.classic.core.util.LibLoaderResourceBundleFactory;
import org.pentaho.reporting.libraries.base.config.Configuration;
import org.pentaho.reporting.libraries.resourceloader.ResourceKey;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;


public class PentahoCdaEnvironment extends PentahoBaseCdaEnvironment implements ICdaEnvironment {

  public PentahoCdaEnvironment() throws InitializationException {
    super();
  }

  public void initializeDataFactory( final DataFactory dataFactory, final Configuration configuration,
      final ResourceKey contextKey, final ResourceManager resourceManager ) throws ReportDataFactoryException {
    // not compatible with pentaho5 version init
    dataFactory.initialize( configuration, resourceManager, contextKey, new LibLoaderResourceBundleFactory(
        resourceManager, contextKey, Locale.getDefault(), TimeZone.getDefault() ) );

    dataFactory.open();
  }

  @Override
  public Locale getLocale(){ return LocaleHelper.getLocale(); }
}
