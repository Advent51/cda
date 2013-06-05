/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package pt.webdetails.cda;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang.StringUtils;

import pt.webdetails.cda.cache.monitor.CacheMonitorHandler;
import pt.webdetails.cda.dataaccess.AbstractDataAccess;
import pt.webdetails.cda.dataaccess.DataAccessConnectionDescriptor;
import pt.webdetails.cda.discovery.DiscoveryOptions;
import pt.webdetails.cda.exporter.Exporter;
import pt.webdetails.cda.exporter.ExporterEngine;
import pt.webdetails.cda.query.QueryOptions;
import pt.webdetails.cda.settings.CdaSettings;
import pt.webdetails.cda.settings.SettingsManager;
import pt.webdetails.cda.cache.ICacheScheduleManager;

import pt.webdetails.cpf.repository.IRepositoryAccess;
import pt.webdetails.cpf.repository.BaseRepositoryAccess.FileAccess;
import pt.webdetails.cpf.http.ICommonParameterProvider;
import pt.webdetails.cpf.session.ISessionUtils;


public class CdaCoreService 
{

  private static Log logger = LogFactory.getLog(CdaCoreService.class);
  public static final String PLUGIN_NAME = "cda";
  private static final long serialVersionUID = 1L;
  private static final String EDITOR_SOURCE = "/editor/editor.html";
  private static final String EXT_EDITOR_SOURCE = "/editor/editor-cde.html";
  private static final String PREVIEWER_SOURCE = "/previewer/previewer.html";
  private static final String CACHE_MANAGER_PATH = "system/" + PLUGIN_NAME + "/cachemanager/cache.html";
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int DEFAULT_START_PAGE = 0;
  private static final String PREFIX_PARAMETER = "param";
  private static final String PREFIX_SETTING = "setting";
  private static final String JSONP_CALLBACK = "callback";
  public static final String ENCODING = "UTF-8";


  //@Exposed(accessLevel = AccessLevel.PUBLIC)
 // @Audited(action = "doQuery")
  public void doQuery(final OutputStream out,ICommonParameterProvider requParams) throws Exception
  {
    final ICommonParameterProvider requestParams = requParams;
        
    final CdaEngine engine = CdaEngine.getInstance();
    final QueryOptions queryOptions = new QueryOptions();

    final String path = getRelativePath(requestParams);
    final CdaSettings cdaSettings = SettingsManager.getInstance().parseSettingsFile(path);

    // Handle paging options
    // We assume that any paging options found mean that the user actively wants paging.
    final long pageSize = requestParams.getLongParameter("pageSize", 0);
    final long pageStart = requestParams.getLongParameter("pageStart", 0);
    final boolean paginate = Boolean.parseBoolean(requestParams.getStringParameter("paginateQuery", "false"));
    if (pageSize > 0 || pageStart > 0 || paginate)
    {
      if (pageSize > Integer.MAX_VALUE || pageStart > Integer.MAX_VALUE)
      {
        throw new ArithmeticException("Paging values too large");
      }
      queryOptions.setPaginate(true);
      queryOptions.setPageSize(pageSize > 0 ? (int) pageSize : paginate ? DEFAULT_PAGE_SIZE : 0);
      queryOptions.setPageStart(pageStart > 0 ? (int) pageStart : paginate ? DEFAULT_START_PAGE : 0);
    }
    
    // Support for bypassCache (we'll maintain the name we use in CDE
    if(requestParams.hasParameter("bypassCache")){
      queryOptions.setCacheBypass(Boolean.parseBoolean(requestParams.getStringParameter("bypassCache","false")));
    }
    
    // Handle the query itself and its output format...
    queryOptions.setOutputType(requestParams.getStringParameter("outputType", "json"));
    queryOptions.setDataAccessId(requestParams.getStringParameter("dataAccessId", "<blank>"));
    try {
      queryOptions.setOutputIndexId(Integer.parseInt(requestParams.getStringParameter("outputIndexId", "1")));
    } catch (NumberFormatException e) {
      logger.error("Illegal outputIndexId '" + requestParams.getStringParameter("outputIndexId", null) + "'" );
    }
    
    final ArrayList<String> sortBy = new ArrayList<String>();
    String[] def =
    {
    };
    for (Object obj : requestParams.getArrayParameter("sortBy", def))
    {
      if (!((String) obj).equals(""))
      {
        sortBy.add((String) obj);
      }
    }
    queryOptions.setSortBy(sortBy);


    // ... and the query parameters
    // We identify any pathParams starting with "param" as query parameters and extra settings prefixed with "setting"
    @SuppressWarnings("unchecked")
    final Iterator<String> params = (Iterator<String>) requestParams.getParameterNames();
    while (params.hasNext())
    {
      final String param = params.next();

      if (param.startsWith(PREFIX_PARAMETER))
      {
        queryOptions.addParameter(param.substring(PREFIX_PARAMETER.length()), requestParams.getParameter(param));
      }
      else if (param.startsWith(PREFIX_SETTING))
      {
        queryOptions.addSetting(param.substring(PREFIX_SETTING.length()), requestParams.getStringParameter(param, ""));
      }
    }

    if(requestParams.getStringParameter("wrapItUp", null) != null) {
      String uuid = engine.wrapQuery(out, cdaSettings, queryOptions);
      logger.debug("doQuery: query wrapped as " + uuid);
      writeOut(out, uuid);
      return;
    }
    
    // we'll allow for the special "callback" param to be used, and passed as settingcallback to jsonp exports
    if (requestParams.hasParameter(JSONP_CALLBACK))
    {
      queryOptions.addSetting(JSONP_CALLBACK, requestParams.getStringParameter(JSONP_CALLBACK, "xxx"));
    }

    Exporter exporter = ExporterEngine.getInstance().getExporter(queryOptions.getOutputType(), queryOptions.getExtraSettings());
    
    String attachmentName = exporter.getAttachmentName();
    String mimeType = (attachmentName == null) ? null : getMimeType(attachmentName);
    if(StringUtils.isEmpty(mimeType)){
      mimeType = exporter.getMimeType();
    }
    
    if (requestParams != null);//XXX  ==  if (this.parameterProviders != null)  
    {
      setResponseHeaders(mimeType, attachmentName);
    }
    // Finally, pass the query to the engine
    engine.doQuery(out, cdaSettings, queryOptions);

  }

  //@Exposed(accessLevel = AccessLevel.PUBLIC)
  public void unwrapQuery(final OutputStream out,ICommonParameterProvider requParam) throws Exception
  {
    final CdaEngine engine = CdaEngine.getInstance();
    final ICommonParameterProvider requestParams = requParam;
    final String path = getRelativePath(requestParams);
    final CdaSettings cdaSettings = SettingsManager.getInstance().parseSettingsFile(path);
    String uuid = requestParams.getStringParameter("uuid", null);

    QueryOptions queryOptions = engine.unwrapQuery(uuid);
    if(queryOptions != null) {
      Exporter exporter = ExporterEngine.getInstance().getExporter(queryOptions.getOutputType(), queryOptions.getExtraSettings());
      
      String attachmentName = exporter.getAttachmentName();
      String mimeType = (attachmentName == null) ? null : getMimeType(attachmentName);
      if(StringUtils.isEmpty(mimeType)){
        mimeType = exporter.getMimeType();
      }
      
      if (requestParams != null);//XXX  ==  if (this.parameterProviders != null)  
      {
        setResponseHeaders(mimeType, attachmentName);
      }
      engine.doQuery(out, cdaSettings, queryOptions);
    }
    else {
      logger.error("unwrapQuery: uuid " + uuid + " not found.");
    }
    
  }

 // @Exposed(accessLevel = AccessLevel.PUBLIC)
  public void listQueries(final OutputStream out,ICommonParameterProvider requParam) throws Exception
  {
    final CdaEngine engine = CdaEngine.getInstance();

    final ICommonParameterProvider requestParams = requParam;
    final String path = getRelativePath(requestParams);
    if(StringUtils.isEmpty(path)){
      throw new IllegalArgumentException("No path provided");
    }
    IRepositoryAccess repAccess = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    logger.debug("Do Query: getRelativePath:" + path);
    logger.debug("Do Query: getSolPath:" + repAccess.getSolutionPath(path));//PentahoSystem.getApplicationContext().getSolutionPath(path));
    final CdaSettings cdaSettings = SettingsManager.getInstance().parseSettingsFile(path);

    // Handle the query itself and its output format...
    final DiscoveryOptions discoveryOptions = new DiscoveryOptions();
    discoveryOptions.setOutputType(requestParams.getStringParameter("outputType", "json"));

    String mimeType = ExporterEngine.getInstance().getExporter(discoveryOptions.getOutputType()).getMimeType();
    setResponseHeaders(mimeType);
    engine.listQueries(out, cdaSettings, discoveryOptions);
  }

 // @Exposed(accessLevel = AccessLevel.PUBLIC)
  public void listParameters(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    final CdaEngine engine = CdaEngine.getInstance();
    final ICommonParameterProvider requestParams = requParam;
    final String path = getRelativePath(requestParams);
    IRepositoryAccess repAccess = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    logger.debug("Do Query: getRelativePath:" + path);
    logger.debug("Do Query: getSolPath:" + repAccess.getSolutionPath(path));//PentahoSystem.getApplicationContext().getSolutionPath(path));
    final CdaSettings cdaSettings = SettingsManager.getInstance().parseSettingsFile(path);

    // Handle the query itself and its output format...
    final DiscoveryOptions discoveryOptions = new DiscoveryOptions();
    discoveryOptions.setOutputType(requestParams.getStringParameter("outputType", "json"));
    discoveryOptions.setDataAccessId(requestParams.getStringParameter("dataAccessId", "<blank>"));

    String mimeType = ExporterEngine.getInstance().getExporter(discoveryOptions.getOutputType()).getMimeType();
    setResponseHeaders(mimeType);

    engine.listParameters(out, cdaSettings, discoveryOptions);
  }


  //@Exposed(accessLevel = AccessLevel.PUBLIC, outputType = MimeType.XML)
  public void getCdaFile(final OutputStream out,ICommonParameterProvider requParam) throws Exception
  {
    String document = getResourceAsString(StringUtils.replace(getRelativePath(requParam), "///", "/"), FileAccess.READ);// ISolutionRepository.ACTION_UPDATE);//TODO:check
    writeOut(out, document);
  }

 // @Exposed(accessLevel = AccessLevel.PUBLIC, outputType = MimeType.PLAIN_TEXT)
  public void writeCdaFile(OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    //TODO: Validate the filename in some way, shape or form!
    IRepositoryAccess repository = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");

    final ICommonParameterProvider requestParams = requParam;
    // Check if the file exists and we have permissions to write to it
    String path = getRelativePath(requestParams);

    if (repository.canWrite(path))
    { 
      switch(repository.publishFile(path, ((String) requestParams.getParameter("data")).getBytes(ENCODING), true)){
        case OK:
          SettingsManager.getInstance().clearCache();
          writeOut(out, "File saved.");
          break;
        case FAIL:
          writeOut(out, "Save unsuccessful!");
          logger.error("writeCdaFile: saving " + path);
          break;
      }
    }
    else
    {
      throw new AccessDeniedException(path, null);
    }
  }

 // @Exposed(accessLevel = AccessLevel.PUBLIC)
  public void getCdaList(final OutputStream out,ICommonParameterProvider requParam) throws Exception
  {
    final CdaEngine engine = CdaEngine.getInstance();

    final DiscoveryOptions discoveryOptions = new DiscoveryOptions();
    discoveryOptions.setOutputType(requParam.getStringParameter("outputType", "json"));
    ISessionUtils sessionUtils = (ISessionUtils) CdaEngine.getInstance().getBeanFactory().getBean("ISessionUtils");
    String mimeType = ExporterEngine.getInstance().getExporter(discoveryOptions.getOutputType()).getMimeType();
    setResponseHeaders(mimeType);
    engine.getCdaList(out, discoveryOptions, sessionUtils.getCurrentSession());
  }

 // @Exposed(accessLevel = AccessLevel.ADMIN, outputType = MimeType.PLAIN_TEXT)
  public void clearCache(final OutputStream out) throws Exception
  {
    SettingsManager.getInstance().clearCache();
    AbstractDataAccess.clearCache();

    out.write("Cache cleared".getBytes());
  }
  
 // @Exposed(accessLevel = AccessLevel.ADMIN)
  public void cacheMonitor(final OutputStream out,ICommonParameterProvider requParam){
    CacheMonitorHandler.getInstance().handleCall(requParam, out);
  }


  public void syncronize(final ICommonParameterProvider pathParams, final OutputStream out) throws Exception
  {
    throw new UnsupportedOperationException("Feature not implemented yet");
//    final SyncronizeCdfStructure syncCdfStructure = new SyncronizeCdfStructure();
//    syncCdfStructure.syncronize(userSession, out, pathParams);
  }


 // @Override
  public Log getLogger()
  {
    return logger;
  }


  private String getRelativePath(final ICommonParameterProvider requestParams) throws UnsupportedEncodingException
  {

    String path = URLDecoder.decode(requestParams.getStringParameter("path", ""), ENCODING);

    final String solution = requestParams.getStringParameter("solution", "");
    if (StringUtils.isEmpty(solution))
    {
      return path;
    }
    final String file = requestParams.getStringParameter("file", "");

    return StringUtils.join(new String[] {solution, path, file}, "/" ).replaceAll("//", "/");
  }


  public String getResourceAsString(final String path, final HashMap<String, String> tokens) throws IOException
  {
    // Read file
    IRepositoryAccess repository = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    String resourceContents = StringUtils.EMPTY;
    
    if (repository.resourceExists(path))
    {
      InputStream in = null;
      try
      {
        in = repository.getResourceInputStream(path, FileAccess.READ);
        resourceContents = IOUtils.toString(in);
      }
      finally 
      {
        IOUtils.closeQuietly(in);
      }
    }
    
    // Make replacement of tokens
    if (tokens != null)
    {
      for (final String key : tokens.keySet())
      { 
        resourceContents = StringUtils.replace(resourceContents, key, tokens.get(key));
      }
    }
    return resourceContents;
  }

  
  public String getResourceAsString(final String path, FileAccess access) throws IOException, AccessDeniedException{
    IRepositoryAccess repository = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    if(repository.hasAccess(path, access)){
      HashMap<String, String> keys = new HashMap<String, String>();
      //Locale locale = LocaleHelper.getLocale();
      Locale locale = Locale.getDefault(); //XXX probably not what intended
      if (logger.isDebugEnabled())
      {
        logger.debug("Current Pentaho user locale: " + locale.toString());
      }
      keys.put("#{LANGUAGE_CODE}", locale.toString());
      return getResourceAsString(path, keys);
    }
    else
    {
      throw new AccessDeniedException(path, null);
    }
  }

  //@Exposed(accessLevel = AccessLevel.PUBLIC)
  public void editFile(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    IRepositoryAccess repository = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    
    
    // Check if the file exists and we have permissions to write to it
    String path = getRelativePath(requParam);
    if (repository.canWrite(path))
    {
      boolean hasCde = repository.resourceExists("system/pentaho-cdf-dd");
      
      final String editorPath = "system/" + PLUGIN_NAME + (hasCde? EXT_EDITOR_SOURCE : EDITOR_SOURCE);
      writeOut(out, getResourceAsString(editorPath,FileAccess.EXECUTE));
    }
    else
    {
      setResponseHeaders("text/plain");
      out.write("Access Denied".getBytes());
    }


  }

  //@Exposed(accessLevel = AccessLevel.PUBLIC)
  public void previewQuery(final OutputStream out) throws Exception
  {
    final String previewerPath = "system/" + PLUGIN_NAME + PREVIEWER_SOURCE;
    writeOut(out, getResourceAsString(previewerPath, FileAccess.EXECUTE));
  }


  //@Exposed(accessLevel = AccessLevel.PUBLIC, outputType = MimeType.CSS)
  public void getCssResource(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    getResource(out, requParam);
  }

  //@Exposed(accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JAVASCRIPT)
  public void getJsResource(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    getResource(out, requParam);
  }


  public void getResource(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    String resource = requParam.getStringParameter("resource", null);
    resource = resource.startsWith("/") ? resource : "/" + resource;
    getResource(out, resource);
  }


  private void getResource(final OutputStream out, final String resource) throws IOException
  {
    IRepositoryAccess repAccess = (IRepositoryAccess)CdaEngine.getInstance().getBeanFactory().getBean("IRepositoryAccess");
    final String path = repAccess.getSolutionPath("system/" + PLUGIN_NAME + resource);//PentahoSystem.getApplicationContext().getSolutionPath("system/" + PLUGIN_NAME + resource); //$NON-NLS-1$ //$NON-NLS-2$

    final File file = new File(path);
    final InputStream in = new FileInputStream(file);
    
    try{
      IOUtils.copy(in, out);
    }
    finally {
      IOUtils.closeQuietly(in);
    }
    
  }
  
  //@Exposed(accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON)
  public void listDataAccessTypes(final OutputStream out, ICommonParameterProvider requParam) throws Exception
  {
    boolean refreshCache = Boolean.parseBoolean(requParam.getStringParameter("refreshCache", "false"));
    
    DataAccessConnectionDescriptor[] data = SettingsManager.getInstance().
            getDataAccessDescriptors(refreshCache);

    StringBuilder output = new StringBuilder("");
    if (data != null)
    {
      output.append("{\n");
      for (DataAccessConnectionDescriptor datum : data)
      {
        output.append(datum.toJSON() + ",\n");
      }
      writeOut(out, output.toString().replaceAll(",\n\\z", "\n}"));
    }
  }

  //@Exposed(accessLevel = AccessLevel.PUBLIC)
  public void cacheController(OutputStream out, ICommonParameterProvider requParam)
  {
      ICacheScheduleManager manager = (ICacheScheduleManager) CdaEngine.getInstance().getBeanFactory().getBean("ICacheScheduleManager");
      manager.handleCall(requParam, out);
    //CacheScheduleManager.getInstance().handleCall(requParam, out);
  }

  //@Exposed(accessLevel = AccessLevel.ADMIN)
  public void manageCache(final OutputStream out) throws Exception
  {
    writeOut(out, getResourceAsString(CACHE_MANAGER_PATH, FileAccess.EXECUTE));
  }


  public String getPluginName() {
    return "cda";
  }
  
  
  private void writeOut(OutputStream out,String uuid){//XXX needs checking
      
      try{
      out.write(uuid.getBytes());
      }catch(IOException e){
          logger.error("Failed to write to stream");
      }
  }
  private String getMimeType(String attachmentName){//XXX must be done differently. cda-core --> cpf-core -/-> cpf-pentaho 
      return null;
  }
  private void setResponseHeaders(String mimeType, String attachmentName){//XXX must be done differently. cda-core --> cpf-core -/-> cpf-pentaho 
      
  }
 
  private void setResponseHeaders(String mimeType){
      
  }
  
}
